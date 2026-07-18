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
     * Whether an explicit {@code System.gc()} will actually do anything, decided ONCE from the JVM's
     * own launch args + collector. Two ways it is useless or harmful:
     * <ul>
     *   <li>{@code -XX:+DisableExplicitGC} (the SourbyBootstrap fork flags + docker/entrypoint.sh set
     *       it) turns {@code System.gc()} into a complete no-op — the r26 "force a cycle" path then
     *       did nothing while still logging success.</li>
     *   <li>On a STOP-THE-WORLD collector (G1/Parallel/Serial) that has NOT been told to run explicit
     *       GC concurrently, {@code System.gc()} is a full STW pause — firing it every couple of
     *       minutes UNDER load amplifies the very lag we are reacting to.</li>
     * </ul>
     * So we only fire it when it is both permitted AND cheap: explicit GC not disabled, and either a
     * concurrent collector (ZGC/Shenandoah — uncommits pages with a ms-scale pause) or the operator
     * asked for {@code ExplicitGCInvokesConcurrent}. Otherwise the cache trims still run and we rely on
     * the ZGC {@code ZUncommitDelay}/{@code SoftMaxHeapSize} flags (in the egg + bootstrap) to return
     * pages passively. Computed lazily; RuntimeMXBean args are stable for the JVM lifetime.
     */
    private static volatile Boolean explicitGcUseful;

    private static boolean explicitGcUseful() {
        Boolean cached = explicitGcUseful;
        if (cached != null) return cached;
        boolean useful;
        try {
            final java.util.List<String> args = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
            final boolean disabled = args.stream().anyMatch(a -> a.equals("-XX:+DisableExplicitGC"));
            final boolean invokesConcurrent = args.stream().anyMatch(a -> a.equals("-XX:+ExplicitGCInvokesConcurrent"));
            boolean concurrentCollector = false;
            for (final var b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                final String n = b.getName();
                if (n.startsWith("ZGC") || n.contains("Z ") || n.contains("Shenandoah")) { concurrentCollector = true; break; }
            }
            useful = !disabled && (concurrentCollector || invokesConcurrent);
        } catch (Throwable t) {
            useful = false; // unknown -> the safe default is "do not fire a possibly-STW gc"
        }
        explicitGcUseful = useful;
        return useful;
    }

    /**
     * Container-kill last resort, called on a memory-EMERGENCY sample (r26; corrected r28). Trimming
     * caches frees heap OBJECTS but does not shrink the CONTAINER footprint — the panel kills on RSS,
     * and freed heap stays committed until the GC hands pages back. When an explicit GC is useful (see
     * {@link #explicitGcUseful()}) we fire one so a concurrent collector uncommits now; otherwise we do
     * NOT call {@code System.gc()} (it would be a no-op or a harmful STW) and instead log the real
     * lever. Either way the passive ZGC uncommit flags carry the defense. Throttled to once per 2 min.
     */
    public static void emergencyHeapRelief() {
        trimSoftCaches();
        final long now = System.nanoTime();
        final long last = LAST_GC.get();
        if (now - last < GC_THROTTLE_NANOS || !LAST_GC.compareAndSet(last, now)) return;
        if (explicitGcUseful()) {
            SourbyLogger.warn("memory EMERGENCY: trimmed caches + requesting a concurrent GC so committed "
                + "pages return to the OS before the container limit is hit (throttled to once per 2 min)");
            System.gc();
        } else {
            SourbyLogger.warn("memory EMERGENCY: trimmed caches. Explicit GC is disabled or the collector "
                + "is stop-the-world, so no GC was forced — if container RSS keeps climbing, lower "
                + "-XX:SoftMaxHeapSize / -Xmx (leave ~30% of the container for off-heap). (once per 2 min)");
        }
    }
}
