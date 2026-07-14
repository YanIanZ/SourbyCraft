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
}
