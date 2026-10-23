package dev.iyanz.sourbycraft.awf.redis.fabric;

import java.util.Map;

/**
 * One point-in-time health view of the fabric.
 *
 * @param reachable whether the last probe got {@code PONG}
 * @param latencyMicros round trip of the probe (the {@code INFO} call included)
 * @param redisVersion the server's {@code redis_version}, or {@code unknown}
 * @param nodeId this node's identity
 * @param prefix the key namespace
 * @param pools per-workload pool size and use
 * @param metrics aggregate counters
 */
public record FabricHealth(boolean reachable, long latencyMicros, String redisVersion, String nodeId, String prefix,
                           Map<RedisWorkload, PoolStat> pools, FabricMetrics.Snapshot metrics) {

    /** @param size connections the pool may open @param inUse connections currently checked out */
    public record PoolStat(int size, int inUse) {}
}
