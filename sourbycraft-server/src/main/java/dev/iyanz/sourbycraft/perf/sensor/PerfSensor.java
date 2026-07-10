package dev.iyanz.sourbycraft.perf.sensor;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Multi-signal load sensor for the SourbyCraft self-tuning perf-engine (Folia F2b port).
 *
 * <p>Reads TPS rolling / MSPT / mem% / GC pause-ms-per-min on a configurable cadence
 * (default 1s at 20 TPS), classifies into a 5-tier scale (GREEN/YELLOW/ORANGE/
 * RED/EMERGENCY) with dwell+band hysteresis, and publishes immutable
 * {@link SensorSnapshot} via a volatile field for pull-API consumers.
 *
 * <h2>Folia adaptation</h2>
 * The Paper-line sensor (tag {@code paper-26.2-pre-folia}) was driven per-tick from
 * {@code MinecraftServer.tickChildren} — a single global main-thread tick loop — and
 * read tick-time from {@code MinecraftServer} NMS fields. <b>Folia has no global tick
 * loop</b>: every region ticks independently on its own thread. So on the Folia base:
 *
 * <ul>
 *   <li><b>Driver:</b> {@link #sample()} is invoked from a Folia global-region-scheduler
 *       fixed-rate task (see {@link #start()}), not from a tick hook. The scheduler fires
 *       every {@code cadenceTicks} server ticks on the global region thread, so one
 *       callback == one sample. The tag's per-tick cadence counter is therefore gone.</li>
 *   <li><b>Metric sources:</b> aggregate, server-wide values via the Bukkit/Folia API
 *       ({@link Bukkit#getTPS()} Folia-aggregate double[3] = 1m/5m/15m,
 *       {@link Bukkit#getAverageTickTime()} Folia-aggregate MSPT in millis) instead of
 *       reading {@code MinecraftServer} tick-time NMS fields. Heap% and GC-pause use the
 *       platform-agnostic {@code java.lang.management} beans (unchanged from the tag).</li>
 *   <li><b>Model:</b> one server-wide aggregate Tier (not per-region), matching the tag's
 *       single-tier model and the aggregate self-tune policy.</li>
 * </ul>
 *
 * The {@link SensorSnapshot} shape, {@link Tier} enum, and the tier-decision / dwell /
 * warmup / hysteresis logic are ported as-is; only the driver and the metric sources changed.
 *
 * <p><b>Thread-safety.</b> {@link #sample()} runs on the global-region scheduler thread and
 * is the sole writer of the runtime-state fields. Configuration is written once at boot
 * (from the config thread, before {@link #start()} is called). The published
 * {@link #lastSnapshot} is a {@code volatile} immutable record, so {@code /perf} reads from
 * any thread see a consistent, latest-visible snapshot lock-free.
 */
public final class PerfSensor {

    private PerfSensor() {}

    // --- Configuration (yml-overridable; written once at boot before start()) ---
    private static volatile boolean enabled = true;
    private static volatile int cadenceTicks = 20;
    private static volatile int dwellSamples = 3;
    private static volatile double recoveryDwellMultiplier = 2.0;
    /**
     * Ticks to skip at server startup before the sensor starts feeding the rolling
     * averages. Expressed in server ticks (for operator-facing config + the /tps warmup
     * label), but consumed in whole sensor samples: warmup completes after
     * {@code ceil(warmupTicks / cadenceTicks)} scheduler callbacks.
     *
     * <p>Default 600 (30s) — the MC 26 plugin-enable phase with 50+ plugins on the heaviest
     * payloads takes 20-25s before the tick loop reaches steady state; sampling during that
     * window pollutes the rolling MSPT/TPS averages and causes a false ORANGE/RED escalation.
     */
    private static volatile int warmupTicks = 600;

    // Threshold arrays indexed by Tier.ordinal(). Index 0 (GREEN) is a sentinel boundary.
    // tpsThresholds: lower value is worse — entry at index N means "value below this -> at least Tier.values()[N]".
    // msptThresholds / memThresholds / gcMsThresholds: higher value is worse.
    private static volatile double[] tpsThresholds   = {Double.MAX_VALUE, 19.5, 18.0, 15.0, 10.0};
    private static volatile double[] msptThresholds  = {Double.MIN_VALUE, 30.0, 40.0, 60.0, 100.0};
    private static volatile double[] memThresholds   = {Double.MIN_VALUE, 75.0, 85.0, 92.0, 97.0};
    private static volatile double[] gcMsThresholds  = {Double.MIN_VALUE, 20.0, 50.0, 100.0, 300.0};

    // --- Runtime state (scheduler-thread writes only; volatile reads for snapshot publication) ---
    private static int warmupSamplesRemaining = -1; // -1 = not yet initialised (set on first sample)
    private static Tier currentTier = Tier.GREEN;
    private static Tier candidateTier = Tier.GREEN;
    private static int dwellCount = 0;
    private static long tierSinceNanos = 0L;
    private static volatile SensorSnapshot lastSnapshot = SensorSnapshot.INITIAL;

    // --- Driver lifecycle (guarded against double-start) ---
    private static volatile boolean started = false;
    private static volatile Object schedulerTask; // io.papermc.paper.threadedregions.scheduler.ScheduledTask

    // --- JMX GC bean cache + per-minute pause ring ---
    private static List<GarbageCollectorMXBean> gcBeans;
    private static long gcPauseTotalMsAtLastSample = 0L;
    private static final long[] gcPauseMsRing = new long[60];
    private static int gcPauseRingIdx = 0;

    /**
     * Load configuration from JAR-baked sourbycraft.yml. Sets defaults before the
     * operator-yml bridge ({@link #applyOperatorConfig}) runs. Called from
     * SourbyCraftConfig.init() immediately after P0 Knobs.loadFromYml().
     */
    public static void loadFromYml() {
        enabled      = SourbyCraftConfig.ymlBool("perf.sensor.enabled", true);
        cadenceTicks = clampInt(SourbyCraftConfig.ymlInt("perf.sensor.cadence-ticks", 20), 1, "cadence-ticks");
        dwellSamples = clampInt(SourbyCraftConfig.ymlInt("perf.sensor.dwell-samples", 3), 1, "dwell-samples");
        warmupTicks  = clampInt(SourbyCraftConfig.ymlInt("perf.sensor.warmup-ticks", 600), 0, "warmup-ticks");
        double mult  = SourbyCraftConfig.ymlDouble("perf.sensor.recovery-dwell-multiplier", 2.0);
        if (Double.isNaN(mult) || mult < 1.0) {
            SourbyLogger.warn("perf sensor recovery-dwell-multiplier " + mult + " < 1.0, clamping to 1.0");
            mult = 1.0;
        }
        recoveryDwellMultiplier = mult;

        loadThresholds("tps",            tpsThresholds,  /*lowerIsWorse*/ true);
        loadThresholds("mspt",           msptThresholds, false);
        loadThresholds("mem",            memThresholds,  false);
        loadThresholds("gc-ms-per-min",  gcMsThresholds, false);
        // Final log is emitted by applyOperatorConfig() which runs after this in SourbyCraftConfig.init().
    }

    private static int clampInt(int v, int min, String name) {
        if (v < min) {
            SourbyLogger.warn("perf sensor " + name + " " + v + " < " + min + ", clamping");
            return min;
        }
        return v;
    }

    private static void loadThresholds(String signal, double[] dst, boolean lowerIsWorse) {
        String[] tierKeys = {null, "yellow", "orange", "red", "emergency"};
        double[] candidate = dst.clone();
        for (int i = 1; i < 5; i++) {
            String path = "perf.sensor.thresholds." + signal + "." + tierKeys[i];
            candidate[i] = SourbyCraftConfig.ymlDouble(path, candidate[i]);
        }
        if (!isMonotonic(candidate, lowerIsWorse)) {
            SourbyLogger.warn("sensor threshold '" + signal + "' non-monotonic, reverting to defaults");
            return; // dst keeps hardcoded defaults
        }
        for (int i = 1; i < 5; i++) dst[i] = candidate[i];
    }

    private static boolean isMonotonic(double[] arr, boolean lowerIsWorse) {
        // For lowerIsWorse: arr[1] (yellow) >= arr[2] (orange) >= arr[3] (red) >= arr[4] (emergency).
        // For higherIsWorse: arr[1] <= arr[2] <= arr[3] <= arr[4].
        // Equal adjacent values are allowed (e.g. orange==red==emergency=1000 to force only YELLOW).
        for (int i = 2; i < 5; i++) {
            if (lowerIsWorse) {
                if (arr[i] > arr[i - 1]) return false;
            } else {
                if (arr[i] < arr[i - 1]) return false;
            }
        }
        return true;
    }

    /**
     * Operator-yml bridge: called from SourbyCraftConfig.init() AFTER loadFromYml() and
     * AFTER all Bukkit-config reads, so operator sourbycraft.yml values override JAR defaults.
     * Parameters that match the JAR default (no operator key present) are effectively no-ops.
     * Non-monotonic threshold sets are rejected with a WARN; other settings are applied directly.
     */
    public static void applyOperatorConfig(
        boolean operatorEnabled, int operatorWarmupTicks, int operatorCadenceTicks,
        int operatorDwellSamples, double operatorRecoveryMult,
        double msptY, double msptO, double msptR, double msptE,
        double tpsY,  double tpsO,  double tpsR,  double tpsE,
        double memY,  double memO,  double memR,  double memE,
        double gcY,   double gcO,   double gcR,   double gcE
    ) {
        if (!operatorEnabled) {
            enabled = false;
            SourbyLogger.info("perf sensor: disabled via operator yml");
            return;
        }
        enabled = true;
        warmupTicks = clampInt(operatorWarmupTicks, 0, "warmup-ticks");
        cadenceTicks = clampInt(operatorCadenceTicks, 1, "cadence-ticks");
        dwellSamples = clampInt(operatorDwellSamples, 1, "dwell-samples");
        double mult = operatorRecoveryMult;
        if (Double.isNaN(mult) || mult < 1.0) {
            SourbyLogger.warn("perf sensor recovery-dwell-multiplier " + mult + " < 1.0, clamping to 1.0");
            mult = 1.0;
        }
        recoveryDwellMultiplier = mult;

        double[] newMspt = {Double.MIN_VALUE, msptY, msptO, msptR, msptE};
        double[] newTps  = {Double.MAX_VALUE, tpsY,  tpsO,  tpsR,  tpsE};
        double[] newMem  = {Double.MIN_VALUE, memY,  memO,  memR,  memE};
        double[] newGc   = {Double.MIN_VALUE, gcY,   gcO,   gcR,   gcE};

        if (isMonotonic(newMspt, false)) { for (int i=1;i<5;i++) msptThresholds[i]=newMspt[i]; }
        else SourbyLogger.warn("sensor threshold 'mspt' non-monotonic, reverting to defaults");
        if (isMonotonic(newTps, true))   { for (int i=1;i<5;i++) tpsThresholds[i]=newTps[i]; }
        else SourbyLogger.warn("sensor threshold 'tps' non-monotonic, reverting to defaults");
        if (isMonotonic(newMem, false))  { for (int i=1;i<5;i++) memThresholds[i]=newMem[i]; }
        else SourbyLogger.warn("sensor threshold 'mem' non-monotonic, reverting to defaults");
        if (isMonotonic(newGc, false))   { for (int i=1;i<5;i++) gcMsThresholds[i]=newGc[i]; }
        else SourbyLogger.warn("sensor threshold 'gc-ms-per-min' non-monotonic, reverting to defaults");

        SourbyLogger.info("perf sensor: cadence=" + cadenceTicks + " dwell=" + dwellSamples
            + " recovery-mult=" + recoveryDwellMultiplier);
    }

    /**
     * Start the Folia global-region-scheduler sampler. Idempotent — a second call is a
     * no-op (guarded by {@link #started}). Called once from the boot hook after
     * {@code SourbyCraftConfig.init()} has loaded the sensor config.
     *
     * <p>Uses {@code Bukkit.getGlobalRegionScheduler().runAtFixedRate(...)} owned by the
     * internal {@code Minecraft} plugin ({@link org.leavesmc.leaves.plugin.MinecraftInternalPlugin#INSTANCE}),
     * which is always {@code enabled} — the scheduler requires an enabled owning plugin, and
     * an SDK plugin handle is not available for server-internal code. The task fires every
     * {@code cadenceTicks} server ticks on the global region thread; each fire is one sample.
     *
     * <p>If the sensor is disabled via config, this logs and returns without scheduling.
     */
    public static synchronized void start() {
        if (started) return;
        if (!enabled) {
            SourbyLogger.info("perf sensor: disabled — sampler not started");
            started = true; // mark started so a re-invocation of the boot hook is still a no-op
            return;
        }
        try {
            org.bukkit.plugin.Plugin owner = org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE;
            // initialDelay must be > 0 for the Folia scheduler; use one cadence period.
            long period = Math.max(1, cadenceTicks);
            schedulerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                owner,
                task -> sample(),
                period,   // initial delay (ticks)
                period    // period (ticks)
            );
            started = true;
            SourbyLogger.info("perf sensor: started (Folia global-region sampler, cadence="
                + cadenceTicks + "t, warmup=" + warmupTicks + "t)");
        } catch (Throwable t) {
            SourbyLogger.error("perf sensor: failed to start Folia global-region sampler", t);
        }
    }

    /** True once {@link #start()} has run (whether or not a task was actually scheduled). */
    public static boolean isStarted() { return started; }

    /**
     * One sensor sample. Invoked on the global-region scheduler thread every
     * {@code cadenceTicks} ticks. Sole writer of runtime-state fields; publishes a fresh
     * {@link SensorSnapshot} to the volatile {@link #lastSnapshot}.
     */
    private static void sample() {
        if (!enabled) return;
        // Warmup guard: skip feeding the averages for the first ceil(warmupTicks/cadenceTicks)
        // samples so the boot MSPT/TPS dip does not trigger a false escalation.
        if (warmupSamplesRemaining < 0) {
            warmupSamplesRemaining = warmupSampleCount();
        }
        if (warmupSamplesRemaining > 0) { warmupSamplesRemaining--; return; }

        double[] tps = aggregateTps();          // Folia-aggregate {1m, 5m, 15m}
        double tps1m  = tps[0];
        double tps5m  = tps[1];
        double mspt   = aggregateMspt();        // Folia-aggregate MSPT (millis)
        double memPct = memUsagePercent();
        double gcMs   = gcMsLastMinute();

        Tier reading = classifyAll(tps1m, mspt, memPct, gcMs);

        // Hysteresis: dwell + band (recovery requires multiplier × dwell samples)
        if (reading == currentTier) {
            candidateTier = currentTier;
            dwellCount = 0;
        } else if (reading == candidateTier) {
            int requiredDwell = reading.isWorseThan(currentTier)
                ? dwellSamples
                : (int) Math.ceil(dwellSamples * recoveryDwellMultiplier);
            dwellCount++;
            if (dwellCount >= requiredDwell) transition(reading);
        } else {
            candidateTier = reading;
            dwellCount = 1;
        }

        // SensorSnapshot shape preserved from the tag: tps1s slot carries the 1m aggregate
        // (Folia's finest-grained public TPS), tps30s carries 1m too (no sub-minute window on
        // the Folia aggregate), tps5m carries the 5m aggregate.
        lastSnapshot = new SensorSnapshot(
            System.nanoTime(), tps1m, tps1m, tps5m, mspt, memPct, gcMs,
            currentTier, candidateTier, dwellCount
        );
    }

    /** Whole-sample warmup budget derived from the tick-denominated warmupTicks. */
    private static int warmupSampleCount() {
        int cadence = Math.max(1, cadenceTicks);
        return (warmupTicks + cadence - 1) / cadence; // ceil division
    }

    // --- Classifier (ported verbatim from the tag) ---
    private static Tier classifyAll(double tps, double mspt, double memPct, double gcMs) {
        Tier t = classifySignal(tps,    tpsThresholds,   /*lowerIsWorse*/ true);
        t = t.worse(classifySignal(mspt,   msptThresholds,  false));
        t = t.worse(classifySignal(memPct, memThresholds,   false));
        t = t.worse(classifySignal(gcMs,   gcMsThresholds,  false));
        return t;
    }

    private static Tier classifySignal(double value, double[] thresholds, boolean lowerIsWorse) {
        if (Double.isNaN(value)) return Tier.GREEN;
        for (int i = 4; i >= 1; i--) {
            boolean exceed = lowerIsWorse ? (value < thresholds[i]) : (value > thresholds[i]);
            if (exceed) return Tier.values()[i];
        }
        return Tier.GREEN;
    }

    private static void transition(Tier newTier) {
        Tier old = currentTier;
        currentTier = newTier;
        dwellCount = 0;
        tierSinceNanos = System.nanoTime();
        SourbyLogger.info("perf tier transition: " + old + " -> " + newTier
            + " (after dwell=" + dwellSamples + " sample(s))");
        // SourbyCraft perf-engine — Self-Tune Controller policy hook (F2b).
        try {
            dev.iyanz.sourbycraft.perf.SelfTuneController.onTierChange(old, newTier);
        } catch (Throwable t) {
            SourbyLogger.error("SelfTuneController.onTierChange failed", t);
        }
        // TierBossBar (P8 telemetry) is an actuator-layer concern — not ported to Folia yet (F2c).
    }

    // --- Signal readers (Folia-aggregate Bukkit API) ---

    /**
     * Folia-aggregate rolling TPS {1m, 5m, 15m} from {@link Bukkit#getTPS()}. On Folia this
     * is the server-wide aggregate across all region threads. Returns {20,20,20} defensively
     * if the API returns an unexpected shape (e.g. very early boot).
     */
    private static double[] aggregateTps() {
        try {
            double[] r = Bukkit.getTPS();
            if (r == null || r.length < 3) return new double[]{20.0, 20.0, 20.0};
            return new double[]{clampTps(r[0]), clampTps(r[1]), clampTps(r[2])};
        } catch (Throwable ignored) {
            return new double[]{20.0, 20.0, 20.0};
        }
    }

    private static double clampTps(double v) {
        if (Double.isNaN(v) || v < 0.0) return 20.0;
        return Math.min(20.0, v);
    }

    /**
     * Folia-aggregate average MSPT (millis) from {@link Bukkit#getAverageTickTime()}. On Folia
     * this aggregates tick time across region threads. Returns 0 defensively on failure so the
     * classifier treats MSPT as GREEN rather than escalating on a transient API error.
     */
    private static double aggregateMspt() {
        try {
            double mspt = Bukkit.getAverageTickTime();
            if (Double.isNaN(mspt) || mspt < 0.0) return 0.0;
            return mspt;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static double memUsagePercent() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        return max > 0 ? 100.0 * used / max : 0.0;
    }

    private static double gcMsLastMinute() {
        if (gcBeans == null) gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long total = 0;
        for (GarbageCollectorMXBean b : gcBeans) total += b.getCollectionTime();
        long delta = total - gcPauseTotalMsAtLastSample;
        gcPauseTotalMsAtLastSample = total;
        if (delta < 0) delta = 0; // defensive: handles unlikely bean reset
        gcPauseMsRing[gcPauseRingIdx] = delta;
        gcPauseRingIdx = (gcPauseRingIdx + 1) % gcPauseMsRing.length;
        long sum = 0;
        for (long v : gcPauseMsRing) sum += v;
        return sum;
    }

    // --- Public read API ---
    public static Tier currentTier() { return lastSnapshot.tier(); }
    public static SensorSnapshot snapshot() { return lastSnapshot; }
    public static boolean isEnabled() { return enabled; }

    /**
     * Number of server ticks remaining in the boot-warmup window before the sensor starts
     * feeding rolling averages. Reported in ticks (not samples) for the /tps warmup label.
     * Returns 0 once warmup is complete; returns the configured total when sampling has not
     * yet started (sensor not yet ticked).
     */
    public static int warmupRemainingTicks() {
        if (warmupSamplesRemaining < 0) return warmupTicks;
        return warmupSamplesRemaining * Math.max(1, cadenceTicks);
    }

    public static int warmupTotalTicks() { return warmupTicks; }

    /** Time in nanoseconds since the last tier transition. 0 if no transition has occurred yet. */
    public static long timeInTierNanos() {
        return tierSinceNanos == 0 ? 0 : System.nanoTime() - tierSinceNanos;
    }

    /** Returns a defensive clone of the threshold array for the named signal. */
    public static double[] thresholdsFor(String signal) {
        return switch (signal) {
            case "tps"             -> tpsThresholds.clone();
            case "mspt"            -> msptThresholds.clone();
            case "mem"             -> memThresholds.clone();
            case "gc-ms-per-min"   -> gcMsThresholds.clone();
            default -> throw new IllegalArgumentException("unknown signal: " + signal);
        };
    }
}
