package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-tick counter for the projectile chunk-load throttle. Counter is
 * reset once per server tick so the {@code LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK}
 * knob caps the per-tick fan-out rather than the total.
 *
 * <p><b>Folia adaptation (F2c).</b> The Paper tag reset this counter from
 * {@code MinecraftServer.tickChildren} — a single global tick loop. Folia has no
 * global tick loop; every region ticks independently. To keep the "reset once
 * per tick" cadence without touching NMS patches, {@link #resetTickCounters()}
 * is instead driven from the perf-engine bootstrap's global-region scheduler
 * task ({@code runAtFixedRate(..., 1, 1)}), which fires every server tick on the
 * global region thread.
 *
 * <p>The counter is an {@link AtomicInteger} so projectile tick paths that run on
 * per-region worker threads increment safely; the once-per-tick reset from the
 * global-region thread is a benign racy write against those increments (the same
 * semantics the tag had — the cap is best-effort, not exact).
 *
 * <p><b>Note:</b> {@link #incrementProjectileChunkLoad()} and
 * {@link #projectileChunkLoadsThisTick()} are the in-tick enforcement hooks that
 * the vanilla projectile chunk-load path must call. Wiring those into the NMS
 * projectile tick requires a minecraft-patch and is <b>deferred to F2e</b>; the
 * counter + reset infrastructure ported here is live and ready for that hook.
 */
public final class LagMachineCounters {

    private static final AtomicInteger PROJECTILE_CHUNK_LOADS = new AtomicInteger();

    private LagMachineCounters() {}

    /**
     * Reset the per-tick counter. On Folia this is driven once per server tick from
     * the perf-engine bootstrap's global-region scheduler task (not from an NMS tick hook).
     */
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
