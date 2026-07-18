package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.util.SourbyLogger;

/**
 * SmartSwap — adaptive, trend-aware heap reclamation.
 *
 * <p>Fed the live memory usage percent (max of heap% and container-RSS%) once per sensor sample, it
 * decides how hard to reclaim memory so RSS stays under the container limit WITHOUT touching TPS:
 * the only actions are soft-cache trims (rebuildable, cheap) and a CONCURRENT GC (ZGC/Shenandoah —
 * a ms-scale pause) to hand freed pages back to the OS. It never unloads chunks, throttles mobs, or
 * changes gameplay — that decoupling is deliberate (see PerfSensor: memory no longer drives the CPU
 * tier).
 *
 * <p><b>Trend-aware.</b> It tracks an EMA of the usage percent and the per-sample slope, and acts on
 * a LOOK-AHEAD estimate (current + a few samples of the current slope). So when RSS is climbing fast
 * toward the limit it escalates EARLY, before the hard threshold — the "smart" part — instead of
 * waiting for a fixed line and then scrambling.
 *
 * <p><b>Progressive.</b> Three levels, each throttled independently so a pinned-high RSS does not
 * thrash the GC:
 * <ul>
 *   <li>L1 SOFT (look-ahead ≥ soft%): trim soft caches.</li>
 *   <li>L2 MEDIUM (≥ medium%): trim + request a concurrent GC (uncommit).</li>
 *   <li>L3 HARD (≥ hard%): trim + a more insistent concurrent GC (shorter throttle).</li>
 * </ul>
 * Below the soft line it idles (near-zero cost: one comparison per sample). Config-gated + reloadable
 * via {@code perf.smart-swap.*}. Called only from the sensor sample thread, so no locking is needed
 * on the trend state.
 */
public final class SmartSwap {

    // Config (mirrored from SourbyCraftConfig on load/reload).
    public static volatile boolean enabled = true;
    public static volatile double softPct = 82.0;   // start trimming caches
    public static volatile double mediumPct = 90.0;  // trim + concurrent GC
    public static volatile double hardPct = 95.0;    // trim + insistent GC

    // Trend state (sensor-thread only).
    private static double ema = Double.NaN;
    private static double lastPct = Double.NaN;
    private static final double EMA_ALPHA = 0.35;    // responsiveness of the smoothed usage
    private static final double LOOKAHEAD_SAMPLES = 3.0; // project the slope this many samples ahead

    // Per-level throttles (nanos).
    private static long lastTrim = 0L;
    private static long lastGc = 0L;
    private static final long TRIM_THROTTLE = 25_000_000_000L;  // 25 s
    private static final long GC_MEDIUM_THROTTLE = 60_000_000_000L; // 60 s
    private static final long GC_HARD_THROTTLE = 40_000_000_000L;   // 40 s (act more often when critical)

    private static int lastLevelLogged = -1;

    private SmartSwap() {}

    /**
     * One sample of the live usage percent (0–100). Updates the trend and reclaims proportionally.
     * @param now monotonic nanos (passed in so the sensor's clock is the single source of time).
     */
    public static void onSample(final double usagePct, final long now) {
        if (!enabled || Double.isNaN(usagePct)) return;

        // Update EMA + slope.
        final double slope = Double.isNaN(lastPct) ? 0.0 : (usagePct - lastPct);
        lastPct = usagePct;
        ema = Double.isNaN(ema) ? usagePct : (EMA_ALPHA * usagePct + (1 - EMA_ALPHA) * ema);
        // Look-ahead: where usage is heading if the current (positive) slope holds. Only rising trends
        // pull the estimate UP — a falling RSS should not trigger reclamation early.
        final double lookahead = usagePct + Math.max(0.0, slope) * LOOKAHEAD_SAMPLES;
        final double effective = Math.max(usagePct, Math.max(ema, lookahead));

        final int level = effective >= hardPct ? 3 : effective >= mediumPct ? 2 : effective >= softPct ? 1 : 0;
        if (level == 0) { lastLevelLogged = 0; return; }

        boolean trimmed = false, gced = false;
        if (now - lastTrim >= TRIM_THROTTLE) {
            MemoryPressure.trimNow();
            lastTrim = now;
            trimmed = true;
        }
        if (level >= 2) {
            final long gcThrottle = level >= 3 ? GC_HARD_THROTTLE : GC_MEDIUM_THROTTLE;
            if (now - lastGc >= gcThrottle) {
                if (MemoryPressure.requestConcurrentGc()) gced = true;
                lastGc = now;
            }
        }

        // Log on a level change or when we actually did meaningful work, so operators can see it
        // working without per-sample spam.
        if ((trimmed || gced) || level != lastLevelLogged) {
            SourbyLogger.info(String.format(java.util.Locale.ROOT,
                "SmartSwap L%d: usage %.0f%% (ema %.0f%%, look-ahead %.0f%%)%s%s",
                level, usagePct, ema, lookahead,
                trimmed ? " -> trimmed caches" : "",
                gced ? " + concurrent GC (uncommit)" : ""));
        }
        lastLevelLogged = level;
    }

    /** Reload the tunables from config (reflected next sample). */
    public static void configure(final boolean on, final double soft, final double medium, final double hard) {
        enabled = on;
        // Keep the ladder monotonic + clamped even if an operator inverts the values.
        softPct = clamp(soft, 50, 99);
        mediumPct = clamp(Math.max(medium, softPct), 50, 99);
        hardPct = clamp(Math.max(hard, mediumPct), 50, 99);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
