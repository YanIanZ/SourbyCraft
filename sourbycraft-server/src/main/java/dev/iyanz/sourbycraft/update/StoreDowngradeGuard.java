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
 * The downgrade gate of the auto-updater (BUILD-51-55-PLAN owner decision OD-2), Build 51 and 52.
 *
 * <p>The AWF MultiProfile format-2 store is one way: a build older than Build 51 does not know the
 * format marker, reads format-2 records as corrupt (the FILE backend sets format-2 journals aside as
 * {@code .corrupt}) and writes format-1 accounts beside the format-2 ones. Those old builds have no
 * gate of their own, so the gate lives here, in the build that can still read the store.</p>
 *
 * <p>Since Build 52 the store also records the oldest build that may open it
 * ({@link StoreFormat.Marker#requiredBuild()}): 51 for a format-2 store without co-op records, 52 once the
 * first co-op record was written (the marker's payload schema is then 2 and its {@code minimumBuild} 52;
 * Build 51 refuses such a store at startup, see {@link StoreFormat}). While the configured store requires
 * build {@code N}, this updater refuses to copy {@code <jar>.prev} back ({@link UpdateRollbackGuard}) and
 * refuses to stage or swap in a release older than {@code N} ({@link SourbyUpdater}, {@link UpdateApplier}).
 * Downgrading such a server is a manual operation: restore the pre-migration backup the migration wrote
 * (which predates every co-op record), then install the older jar.</p>
 *
 * <p>Fail closed: a target build that cannot be read counts as older than any requirement, and a store
 * whose marker cannot be read at that early point (no reachable Redis, invalid URI, unknown or malformed
 * marker) requires {@link StoreFormat#READS_UP_TO_BUILD}, so every older target is refused, and says why.
 * The FILE marker {@code <directory>/format.awfm} is read whenever it exists, whether or not MultiProfile
 * is enabled; the Redis marker is read only while MultiProfile is enabled with {@code repository: redis},
 * because reading it needs a connection of its own.</p>
 */
public final class StoreDowngradeGuard {

    /** The first build that reads and writes the format-2 MultiProfile store. */
    public static final BuildVersion FIRST_FORMAT_2_BUILD = BuildVersion.parse(Integer.toString(StoreFormat.FIRST_FORMAT_2_BUILD));
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

    /** What the configured store requires: the oldest build that may open it, and a description for the reason. */
    record Requirement(int build, String description) {}

    /**
     * Refuses {@code target} when it is older than the build the MultiProfile store configured under
     * {@code serverDir} requires (51 for format 2, 52 once it holds co-op records), or unknown while the store
     * requires any build, or when the store cannot be classified and the target is older than this build reads.
     */
    static Optional<String> refuse(final BuildVersion target, final Path serverDir) {
        // Below 51 nothing can be required; a target at or above the newest level this code knows passes untouched.
        if (!target.isUnknown() && target.compareTo(BuildVersion.parse(Integer.toString(StoreFormat.READS_UP_TO_BUILD))) >= 0) {
            return Optional.empty();
        }
        final Optional<Requirement> store = requirement(serverDir);
        if (store.isEmpty()) return Optional.empty();
        final int required = store.get().build();
        if (!target.isUnknown() && target.compareTo(BuildVersion.parse(Integer.toString(required))) >= 0) return Optional.empty();
        return Optional.of(REASON_TAG + " Build " + (target.isUnknown() ? "<unreadable build number>" : target.toString())
            + " is older than Build " + required + ", which the AWF MultiProfile store requires (" + store.get().description()
            + "). Owner decision OD-2: the format-2 store is one way (builds older than 51 corrupt it; a store holding co-op"
            + " records is refused by Build 51), so this updater refuses the downgrade. To run an older build, restore the"
            + " pre-migration backup the migration wrote (see docs/awf/multiprofile/OPERATIONS.md) and install the older jar"
            + " by hand.");
    }

    /**
     * Describes the configured store when it is format 2 (or cannot be read: fail closed); empty when
     * it is format 1 or there is none.
     */
    static Optional<String> format2Store(final Path serverDir) {
        return requirement(serverDir).map(Requirement::description);
    }

    /** What the configured store requires; empty for format 1 or no store. Unreadable: {@link StoreFormat#READS_UP_TO_BUILD}. */
    static Optional<Requirement> requirement(final Path serverDir) {
        final ProfileSettings settings;
        try {
            settings = ProfileConfig.readEarly(serverDir.resolve(AURORA_FILE), serverDir.resolve(UNIFIED_FILE));
        } catch (final RuntimeException unreadable) {
            return unreadable("the MultiProfile configuration could not be read: " + unreadable.getMessage());
        }
        final Path marker = serverDir.resolve(settings.directory()).resolve(StoreFormat.FILE_NAME);
        if (Files.isRegularFile(marker)) {
            try {
                final StoreFormat.Marker stored = StoreFormat.decode(Files.readAllBytes(marker), marker.toString());
                if (!stored.legacyFormat()) {
                    return Optional.of(new Requirement(Math.max(StoreFormat.FIRST_FORMAT_2_BUILD, stored.requiredBuild()),
                        "FILE store " + marker + " is format " + stored.format() + ", schema " + stored.schema() + ", migration "
                            + stored.migration().name().toLowerCase(java.util.Locale.ROOT) + ", written by " + stored.writtenBy()
                            + ", requires Build " + stored.requiredBuild()));
                }
            } catch (final IOException | RuntimeException unreadable) {
                return unreadable("the FILE store marker " + marker + " cannot be read: " + unreadable.getMessage());
            }
        }
        if (settings.enabled() && settings.redisRepository()) {
            return redisRequirement(serverDir);
        }
        return Optional.empty();
    }

    private static Optional<Requirement> unreadable(final String why) {
        return Optional.of(new Requirement(StoreFormat.READS_UP_TO_BUILD, why));
    }

    /** The Redis marker, read with a short-lived connection of its own; any failure is a reason to refuse. */
    private static Optional<Requirement> redisRequirement(final Path serverDir) {
        final RedisSettings redis;
        try {
            redis = RedisSettings.readEarly(serverDir.resolve(AURORA_FILE), serverDir.resolve(UNIFIED_FILE));
        } catch (final RuntimeException unreadable) {
            return unreadable("repository: redis is configured but its settings could not be read: " + unreadable.getMessage());
        }
        if (redis.uri() == null || redis.invalidReason().isPresent()) {
            return unreadable("repository: redis is configured but its marker cannot be read: "
                + redis.invalidReason().orElse("no Redis URI"));
        }
        final String key = FabricSettings.namespace(redis.keyPrefix()) + "mp:meta";
        RedisClient client = null;
        try {
            client = new RedisClient(RedisClient.Endpoint.parse(redis.uri()), 1, Math.min(REDIS_TIMEOUT_MILLIS,
                Math.max(1, redis.timeoutMillis())));
            final Object reply = client.call("HMGET", key, StoreFormat.REDIS_FORMAT_FIELD, StoreFormat.REDIS_SCHEMA_FIELD,
                StoreFormat.REDIS_MINIMUM_BUILD_FIELD);
            final java.util.List<?> fields = reply instanceof java.util.List<?> list ? list : java.util.List.of();
            final String format = fields.isEmpty() ? null : RedisClient.text(fields.get(0));
            if (format == null || Integer.toString(StoreFormat.LEGACY).equals(format)) return Optional.empty();
            final String schema = fields.size() > 1 ? RedisClient.text(fields.get(1)) : null;
            final String minimum = fields.size() > 2 ? RedisClient.text(fields.get(2)) : null;
            final int schemaNumber = schema == null ? 0 : Integer.parseInt(schema);
            final int recorded = minimum == null ? 0 : Integer.parseInt(minimum);
            final int required = Math.max(Math.max(StoreFormat.FIRST_FORMAT_2_BUILD, recorded),
                schemaNumber >= StoreFormat.COOP_SCHEMA ? StoreFormat.COOP_MINIMUM_BUILD : 0);
            return Optional.of(new Requirement(required, "Redis store " + RedisClient.Endpoint.redact(redis.uri()) + " key " + key
                + " says format " + format + ", schema " + (schema == null ? "-" : schema) + ", requires Build " + required));
        } catch (final IOException | RuntimeException unreadable) {
            return unreadable("repository: redis is configured but the store marker " + key + " at "
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
