package dev.iyanz.sourbycraft.awf.redis.fabric;

import java.util.Objects;

/**
 * Test-only: several "processes" on the one {@link RedisFabric} a JVM may run ({@link RedisFabric#start} is a
 * process-wide singleton by design). A {@link SessionFabric} built here shares the fabric's coordination pool,
 * namespace, metrics and lease length but claims as its own incarnation, so to {@link SessionFabric#foreignHolder}
 * and to the claim scripts it is another process: what a second node of a multi-node case needs.
 */
public final class FabricTestNodes {

    private FabricTestNodes() {}

    /**
     * A session fabric on {@code fabric}'s connection claiming as {@code incarnation} with no proven-stopped
     * predecessor. Its {@link SessionFabric#coopClaims()} carry the same incarnation.
     */
    public static SessionFabric sessionsAs(final RedisFabric fabric, final String incarnation) {
        Objects.requireNonNull(fabric, "fabric");
        return new SessionFabric(fabric.client(RedisWorkload.COORDINATION), fabric.prefix(), fabric.metrics(),
            fabric.settings().sessions().ttlSeconds(), Objects.requireNonNull(incarnation, "incarnation"), null);
    }
}
