package dev.iyanz.sourbycraft.awf.redis.fabric;

/**
 * The independent connection pools of the Aurora Redis Fabric.
 *
 * <p>Each workload gets its own bounded {@link dev.iyanz.sourbycraft.awf.redis.RedisClient}, so a
 * long-running event consumer or a burst of coordination calls cannot starve world-storage commits.
 * A workload is a statement about <em>which</em> pool a call uses, not a thread; callers still must
 * be on an I/O worker (never a region, entity, global-tick or Netty thread).</p>
 */
public enum RedisWorkload {
    /** The existing AWF world store; sized by {@code aurora.awf.redis.pool-size} unless overridden. */
    STORAGE,
    /** Short, latency-sensitive ownership and session scripts. */
    COORDINATION,
    /** Stream producers and consumers, including blocking reads. */
    EVENTS,
    /** Health probes, cache version checks and administrative reads. */
    CONTROL
}
