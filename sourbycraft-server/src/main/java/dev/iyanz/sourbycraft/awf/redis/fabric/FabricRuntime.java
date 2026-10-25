package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Boot lifecycle of the Aurora Redis Fabric.
 *
 * <p>Started before Aurora World Fabric can open a Redis-backed world, so the world backend can share
 * the fabric's STORAGE pool. Disabled means nothing is created. A fabric that cannot be built (bad
 * settings) or cannot reach Redis is reported once and left off; the server keeps running, and
 * distributed MultiProfile refuses to start without it rather than falling back to local ownership.
 * An unusable {@code aurora.awf.redis.uri} (or {@code SOURBYCRAFT_AWF_REDIS_URI}) is one of those
 * settings: the fabric stays off and never connects to the default server instead.</p>
 *
 * <p>There is one registry ({@link RedisFabric#running()}); this class only owns start and stop, so a
 * fabric can never be "running" for AWF and "disabled" for {@code /awf redis} at the same time.</p>
 */
public final class FabricRuntime {

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.yml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");

    private FabricRuntime() {}

    /**
     * The boot stage. Also tells {@link NodeIdentity} that the working directory is the server
     * directory, enabled fabric or not: the AWF Redis backend and MultiProfile use the same identity.
     */
    public static synchronized void start() {
        NodeIdentity.useServerDirectory();
        if (RedisFabric.running() != null) return;
        try {
            final FabricSettings settings = FabricConfig.readEarly(AURORA_FILE, UNIFIED_FILE);
            if (!settings.enabled()) return;
            final RedisSettings redis = RedisSettings.readEarly(AURORA_FILE, UNIFIED_FILE);
            final Optional<String> invalid = redis.invalidReason();
            if (invalid.isPresent()) {
                SourbyLogger.error("Aurora Redis Fabric could not start: the Redis connection is misconfigured: "
                    + invalid.get() + ". The fabric is off (no default server is used) and distributed MultiProfile"
                    + " will refuse to start until the setting is fixed");
                return;
            }
            final RedisFabric created = RedisFabric.start(settings, redis, NodeIdentity.current());
            final FabricHealth health = created.probe();
            if (!health.reachable()) {
                SourbyLogger.error("Aurora Redis Fabric: Redis is not reachable at "
                    + RedisClient.Endpoint.redact(redis.uri())
                    + "; the fabric is off and distributed MultiProfile will refuse to start");
                created.close(1_000L);
                return;
            }
            SourbyLogger.info(created.describe() + " redis " + health.redisVersion() + " (" + health.latencyMicros()
                + " us)");
        } catch (final RuntimeException failed) {
            // Unreadable settings or an unusable fabric configuration must not abort world load.
            SourbyLogger.error("Aurora Redis Fabric could not start: " + failed.getMessage(), failed);
        }
    }

    /** The running fabric, or {@code null}. */
    public static RedisFabric running() {
        return RedisFabric.running();
    }

    public static synchronized void stop(final long budgetMillis) {
        final RedisFabric current = RedisFabric.running();
        if (current != null) current.close(budgetMillis);
    }

    /**
     * This server's stable node id ({@link NodeIdentity#nodeId()}): a hash of a random id persisted in
     * {@code awf/node-id}, the host name and the server directory. It names the server; it does not
     * prove two claims come from one process, which is what the incarnation is for.
     */
    public static String nodeId() {
        return NodeIdentity.current().nodeId();
    }
}
