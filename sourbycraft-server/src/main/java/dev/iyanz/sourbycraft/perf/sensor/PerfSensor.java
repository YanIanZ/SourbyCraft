package dev.iyanz.sourbycraft.perf.sensor;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import net.minecraft.server.MinecraftServer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Multi-signal load sensor for the SourbyCraft self-tuning perf-engine.
 * Reads TPS rolling / MSPT / mem% / GC pause-ms-per-min on a configurable cadence
 * (default 1s at 20 TPS), classifies into a 5-tier scale (GREEN/YELLOW/ORANGE/
 * RED/EMERGENCY) with dwell+band hysteresis, and publishes immutable
 * {@link SensorSnapshot} via a volatile field for pull-API consumers.
 *
 * <p>Sensor writes only from the main server thread ({@code tick(server)} is called
 * from {@code MinecraftServer.tickChildren}). All read paths are volatile-published
 * and lock-free.
 */
public final class PerfSensor {

    private PerfSensor() {}

    // --- Configuration (yml-overridable; written once at boot) ---
    private static volatile boolean enabled = true;
    private static volatile int cadenceTicks = 20;
    private static volatile int dwellSamples = 3;
    private static volatile double recoveryDwellMultiplier = 2.0;
    /** Ticks to skip at server startup before the sensor starts sampling (default 200 = 10s). */
    private static volatile int warmupTicks = 200;

    // Threshold arrays indexed by Tier.ordinal(). Index 0 (GREEN) is a sentinel boundary.
    // tpsThresholds: lower value is worse — entry at index N means "value below this -> at least Tier.values()[N]".
    // msptThresholds / memThresholds / gcMsThresholds: higher value is worse.
    private static volatile double[] tpsThresholds   = {Double.MAX_VALUE, 19.5, 18.0, 15.0, 10.0};
    private static volatile double[] msptThresholds  = {Double.MIN_VALUE, 30.0, 40.0, 60.0, 100.0};
    private static volatile double[] memThresholds   = {Double.MIN_VALUE, 75.0, 85.0, 92.0, 97.0};
    private static volatile double[] gcMsThresholds  = {Double.MIN_VALUE, 20.0, 50.0, 100.0, 300.0};

    // --- Runtime state (main-thread writes only; volatile reads for snapshot publication) ---
    private static int tickCounter = 0;
    private static int warmupRemaining = -1; // -1 = not yet initialised (set on first tick)
    private static Tier currentTier = Tier.GREEN;
    private static Tier candidateTier = Tier.GREEN;
    private static int dwellCount = 0;
    private static long tierSinceNanos = 0L;
    private static volatile SensorSnapshot lastSnapshot = SensorSnapshot.INITIAL;

    // --- JMX GC bean cache + per-minute pause ring ---
    private static List<GarbageCollectorMXBean> gcBeans;
    private static long gcPauseTotalMsAtLastSecond = 0L;
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
        warmupTicks  = clampInt(SourbyCraftConfig.ymlInt("perf.sensor.warmup-ticks", 200), 0, "warmup-ticks");
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

    /** Main-thread entry. Called every server tick from MinecraftServer.tickChildren. */
    public static void tick(MinecraftServer server) {
        if (!enabled) return;
        // Warmup guard: skip sampling for warmupTicks ticks after first call to let
        // tick-time ring buffers stabilise (avoids false escalation on slow boot ticks).
        if (warmupRemaining < 0) warmupRemaining = warmupTicks; // initialise on first tick
        if (warmupRemaining > 0) { warmupRemaining--; return; }
        if (++tickCounter < cadenceTicks) return;
        tickCounter = 0;

        double tps1s  = tpsFromNanos(server.getAverageTickTimeNanos());
        double tps30s = paperTpsAvg(server, 30);
        double tps5m  = paperTpsAvg(server, 300);
        double mspt   = server.getAverageTickTimeNanos() / 1_000_000.0;
        double memPct = memUsagePercent();
        double gcMs   = gcMsLastMinute();

        Tier reading = classifyAll(tps1s, mspt, memPct, gcMs);

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

        lastSnapshot = new SensorSnapshot(
            System.nanoTime(), tps1s, tps30s, tps5m, mspt, memPct, gcMs,
            currentTier, candidateTier, dwellCount
        );
    }

    // --- Classifier ---
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
    }

    // --- Signal readers ---
    private static double tpsFromNanos(long nanos) {
        return nanos > 0 ? Math.min(20.0, 1_000_000_000.0 / nanos) : 20.0;
    }

    private static double paperTpsAvg(MinecraftServer server, int windowSeconds) {
        // Paper exposes getTPS() as {tps1m, tps5m, tps15m}. Map windowSeconds approximately.
        try {
            double[] r = server.getTPS();
            if (r == null || r.length < 3) return tpsFromNanos(server.getAverageTickTimeNanos());
            if (windowSeconds <= 60)  return r[0];
            if (windowSeconds <= 300) return r[1];
            return r[2];
        } catch (Throwable ignored) {
            return tpsFromNanos(server.getAverageTickTimeNanos());
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
        long delta = total - gcPauseTotalMsAtLastSecond;
        gcPauseTotalMsAtLastSecond = total;
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
