package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.awf.multiprofile.ProfileConfig;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSettings;
import dev.iyanz.sourbycraft.awf.multiprofile.StoreFormat;
import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The Build 51 downgrade gate of the auto-updater (BUILD-51-55-PLAN owner decision OD-2).
 *
 * <p>The AWF MultiProfile format-2 store is one way: a build older than Build 51 does not know the
 * format marker, reads format-2 records as corrupt (the FILE backend sets format-2 journals aside as
 * {@code .corrupt}) and writes format-1 accounts beside the format-2 ones. Those old builds have no
 * gate of their own, so the gate lives here, in the build that can still read the store: while the
 * configured MultiProfile store is format 2, this updater refuses to copy {@code <jar>.prev} back
 * ({@link UpdateRollbackGuard}) and refuses to stage or swap in a release older than Build 51
 * ({@link SourbyUpdater}, {@link UpdateApplier}). Downgrading such a server is a manual operation:
 * restore the pre-migration backup the migration wrote, then install the older jar.</p>
 *
 * <p>Fail closed: a target build that cannot be read counts as older than 51, and a Redis store whose
 * marker cannot be read at that early point (no reachable Redis, invalid URI, unknown marker) refuses
 * the downgrade and says why. The FILE marker {@code <directory>/format.awfm} is read whenever it
 * exists, whether or not MultiProfile is enabled; the Redis marker is read only while MultiProfile is
 * enabled with {@code repository: redis}, because reading it needs a connection of its own.</p>
 */
public final class StoreDowngradeGuard {

    /** The first build that reads and writes the format-2 (co-op) MultiProfile store. */
    public static final BuildVersion FIRST_FORMAT_2_BUILD = BuildVersion.parse("51");
    /** Every refusal reason starts with this, so the boot banner can tell an OD-2 refusal from a failed copy. */
    public static final String REASON_TAG = "[OD-2]";

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.yml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");
    /** Longest the early Redis marker read may take; the updater runs it on its own thread or at boot. */
    private static final int REDIS_TIMEOUT_MILLIS = 5_000;

    /** A reason to refuse installing {@code target} over this server, or empty. */
    @FunctionalInterface
    public interface Gate {
        Optional<String> refuse(BuildVersion target);
    }

    /** No gate: for unit tests of the pure file logic. */
    public static final Gate NONE = target -> Optional.empty();

    /** The gate of this server (configuration and store under the working directory). */
    public static final Gate SERVER = target -> refuse(target, Path.of(""));

    private StoreDowngradeGuard() {}

    /**
     * Refuses {@code target} when it is older than Build 51 (or unknown) and the MultiProfile store
     * configured under {@code serverDir} is format 2 or cannot be classified.
     */
    static Optional<String> refuse(final BuildVersion target, final Path serverDir) {
        if (!target.isUnknown() && target.compareTo(FIRST_FORMAT_2_BUILD) >= 0) return Optional.empty();
        final Optional<String> store = format2Store(serverDir);
        if (store.isEmpty()) return Optional.empty();
        return Optional.of(REASON_TAG + " Build " + (target.isUnknown() ? "<unreadable build number>" : target.toString())
            + " is older than Build 51 and the AWF MultiProfile store is format 2 (" + store.get() + "). Owner decision OD-2:"
            + " the format-2 store is one way and builds older than Build 51 corrupt it, so this updater refuses the downgrade."
            + " To run an older build, restore the pre-migration backup the migration wrote (see"
            + " docs/awf/multiprofile/OPERATIONS.md) and install the older jar by hand.");
    }

    /**
     * Describes the configured store when it is format 2 (or cannot be read: fail closed); empty when
     * it is format 1 or there is none.
     */
    static Optional<String> format2Store(final Path serverDir) {
        final ProfileSettings settings;
        try {
            settings = ProfileConfig.readEarly(serverDir.resolve(AURORA_FILE), serverDir.resolve(UNIFIED_FILE));
        } catch (final RuntimeException unreadable) {
            return Optional.of("the MultiProfile configuration could not be read: " + unreadable.getMessage());
        }
        final Path marker = serverDir.resolve(settings.directory()).resolve(StoreFormat.FILE_NAME);
        if (Files.isRegularFile(marker)) {
            try {
                final StoreFormat.Marker stored = StoreFormat.decode(Files.readAllBytes(marker), marker.toString());
                if (!stored.legacyFormat()) {
                    return Optional.of("FILE store " + marker + " is format " + stored.format() + ", migration "
                        + stored.migration().name().toLowerCase(java.util.Locale.ROOT) + ", written by " + stored.writtenBy());
                }
            } catch (final IOException | RuntimeException unreadable) {
                return Optional.of("the FILE store marker " + marker + " cannot be read: " + unreadable.getMessage());
            }
        }
        if (settings.enabled() && settings.redisRepository()) {
            return redisFormat2(serverDir);
        }
        return Optional.empty();
    }

    /** The Redis marker, read with a short-lived connection of its own; any failure is a reason to refuse. */
    private static Optional<String> redisFormat2(final Path serverDir) {
        final RedisSettings redis;
        try {
            redis = RedisSettings.readEarly(serverDir.resolve(AURORA_FILE), serverDir.resolve(UNIFIED_FILE));
        } catch (final RuntimeException unreadable) {
            return Optional.of("repository: redis is configured but its settings could not be read: " + unreadable.getMessage());
        }
        if (redis.uri() == null || redis.invalidReason().isPresent()) {
            return Optional.of("repository: redis is configured but its marker cannot be read: "
                + redis.invalidReason().orElse("no Redis URI"));
        }
        final String key = FabricSettings.namespace(redis.keyPrefix()) + "mp:meta";
        RedisClient client = null;
        try {
            client = new RedisClient(RedisClient.Endpoint.parse(redis.uri()), 1, Math.min(REDIS_TIMEOUT_MILLIS,
                Math.max(1, redis.timeoutMillis())));
            final String format = RedisClient.text(client.call("HGET", key, StoreFormat.REDIS_FORMAT_FIELD));
            if (format == null || Integer.toString(StoreFormat.LEGACY).equals(format)) return Optional.empty();
            return Optional.of("Redis store " + RedisClient.Endpoint.redact(redis.uri()) + " key " + key + " says format " + format);
        } catch (final IOException | RuntimeException unreadable) {
            return Optional.of("repository: redis is configured but the store marker " + key + " at "
                + RedisClient.Endpoint.redact(redis.uri()) + " could not be read (" + unreadable.getMessage()
                + "); the downgrade is refused until it can be");
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (final RuntimeException ignored) {
                    // the connection was short-lived; nothing depends on it after the read
                }
            }
        }
    }
}
