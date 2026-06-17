package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-tick counters for the projectile chunk-load throttle. Counter is
 * reset on every server tickChildren so the {@code LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK}
 * knob caps the per-tick fan-out rather than the total.
 *
 * <p>Counter is a {@link AtomicInteger} so projectile tick paths that may
 * run on worker threads (Folia/region scheduler) increment safely. The
 * reset path runs on the main server thread only.
 */
public final class LagMachineCounters {

    private static final AtomicInteger PROJECTILE_CHUNK_LOADS = new AtomicInteger();

    private LagMachineCounters() {}

    /** Called from {@code MinecraftServer.tickChildren} once per server tick. */
    public static void resetTickCounters() {
        PROJECTILE_CHUNK_LOADS.set(0);
    }

    public static int projectileChunkLoadsThisTick() {
        return PROJECTILE_CHUNK_LOADS.get();
    }

    public static void incrementProjectileChunkLoad() {
        PROJECTILE_CHUNK_LOADS.incrementAndGet();
    }
}
