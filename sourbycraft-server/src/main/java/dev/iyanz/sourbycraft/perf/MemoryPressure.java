package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.util.SourbyLogger;

/**
 * Soft-cache trimmer for memory-pressure tiers. When the sensor escalates to RED/EMERGENCY the
 * self-tune controller calls {@link #trimSoftCaches()}: every rebuildable cache (anti-xray scan
 * cache, entity verdicts, geo lookups) is dropped so the next GC cycle can actually return pages
 * to the OS instead of holding warm-but-idle data. All targets rebuild lazily and bounded — the
 * cost is a brief warm-up, never correctness.
 *
 * <p>Throttled to once per 5 minutes: applyTier can re-fire on tier bounces and repeated trims
 * inside one pressure episode would only cause rebuild churn.
 */
public final class MemoryPressure {

    private static final long THROTTLE_NANOS = 300_000_000_000L; // 5 min
    private static final java.util.concurrent.atomic.AtomicLong LAST_TRIM = new java.util.concurrent.atomic.AtomicLong();

    private MemoryPressure() {}

    public static void trimSoftCaches() {
        final long now = System.nanoTime();
        final long last = LAST_TRIM.get();
        if (now - last < THROTTLE_NANOS || !LAST_TRIM.compareAndSet(last, now)) return;
        try {
            dev.iyanz.sourbycraft.antixray.OreReveal.trimCachesForMemoryPressure();
            dev.iyanz.sourbycraft.antixray.EntityVisibilityCheck.trimForMemoryPressure();
            dev.iyanz.sourbycraft.util.GeoUtil.trimForMemoryPressure();
            SourbyLogger.info("memory pressure: trimmed soft caches (antixray scan/verdicts, geo)");
        } catch (Throwable t) {
            SourbyLogger.warn("memory pressure trim failed: " + t.getMessage());
        }
    }

    private static final long GC_THROTTLE_NANOS = 120_000_000_000L; // 2 min
    private static final java.util.concurrent.atomic.AtomicLong LAST_GC = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Container-kill last resort, called on a memory-EMERGENCY sample (r26). Trimming caches alone
     * does not shrink the CONTAINER footprint — with ZGC the freed heap stays committed until a GC
     * cycle hands pages back to the OS, and the panel kills on RSS, not on live heap. An explicit
     * {@code System.gc()} forces that cycle now (ZGC honors it concurrently — a pause of ms, not a
     * stop-the-world stall), so committed pages uncommit BEFORE the cgroup limit is hit. Throttled
     * hard: repeated forced cycles inside one pressure episode would burn CPU for nothing.
     */
    public static void emergencyHeapRelief() {
        trimSoftCaches();
        final long now = System.nanoTime();
        final long last = LAST_GC.get();
        if (now - last < GC_THROTTLE_NANOS || !LAST_GC.compareAndSet(last, now)) return;
        SourbyLogger.warn("memory EMERGENCY: forcing a GC cycle so committed pages return to the OS "
            + "before the container limit is hit (throttled to once per 2 min)");
        System.gc();
    }
}
