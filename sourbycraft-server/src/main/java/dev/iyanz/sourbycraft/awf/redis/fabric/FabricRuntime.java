package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.nio.file.Path;

/**
 * Boot lifecycle of the Aurora Redis Fabric.
 *
 * <p>Started before Aurora World Fabric can open a Redis-backed world, so the world backend can share
 * the fabric's STORAGE pool. Disabled means nothing is created. A fabric that cannot be built (bad
 * settings) or cannot reach Redis is reported once and left off; the server keeps running, and
 * distributed MultiProfile refuses to start without it rather than falling back to local ownership.</p>
 *
 * <p>There is one registry ({@link RedisFabric#running()}); this class only owns start and stop, so a
 * fabric can never be "running" for AWF and "disabled" for {@code /awf redis} at the same time.</p>
 */
public final class FabricRuntime {

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.yml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");

    private FabricRuntime() {}

    public static synchronized void start() {
        if (RedisFabric.running() != null) return;
        try {
            final FabricSettings settings = FabricConfig.readEarly(AURORA_FILE, UNIFIED_FILE);
            if (!settings.enabled()) return;
            final RedisSettings redis = RedisSettings.readEarly(AURORA_FILE, UNIFIED_FILE);
            final RedisFabric created = RedisFabric.start(settings, redis, nodeId());
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
            // Unreadable settings, a malformed URI or an unreachable endpoint must not abort world load.
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
     * Stable per-instance node identity: a hash of the host name and the server directory. Two servers
     * in one directory get the same id on purpose; that is the same server.
     */
    public static String nodeId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = java.nio.file.Files.readString(Path.of("/etc/hostname")).trim();
            } catch (final java.io.IOException | RuntimeException none) {
                host = "";
            }
        }
        final String identity = host + "|" + Path.of("").toAbsolutePath().normalize();
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "node-" + java.util.HexFormat.of().formatHex(digest, 0, 6);
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
