package dev.iyanz.sourbycraft.perf.spark;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.gc.GarbageCollector;
import me.lucko.spark.api.statistic.StatisticWindow;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lazy adapter around the bundled {@code spark-api}.
 *
 * <p>SourbyCraft already ships {@code spark-paper-1.10.152} as a runtime
 * library, so the {@link Spark} singleton becomes reachable as soon as
 * spark's own Bukkit plugin entry hands itself off to
 * {@link SparkProvider}. That handoff happens during plugin enable,
 * meaning the SourbyCraft config / sensor boot path cannot rely on
 * {@code SparkProvider.get()} immediately. {@code SparkBridge} caches
 * the singleton on first successful resolve and exposes a small set of
 * read-only snapshot helpers backed by the spark statistic API.
 *
 * <p>Operator opt-out is honoured via the {@code spark.enabled} key in
 * the unified config: setting it to {@code false} short-circuits
 * every accessor here so /sparkview and the PerfSensor fallback path
 * never touch the spark API at all (useful for paranoia setups where
 * the spark plugin is intentionally pinned off).
 */
public final class SparkBridge {

    private static final AtomicReference<Spark> CACHED = new AtomicReference<>();

    private SparkBridge() {}

    /** Returns {@code true} when the operator has not disabled the spark bridge. */
    public static boolean enabled() {
        try {
            return SourbyCraftConfig.cfgBool("spark.enabled", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Returns the spark singleton if reachable, or {@code null} when
     * the spark plugin has not finished initialising (typical at server
     * boot, before plugin enable) or the operator disabled the bridge.
     */
    public static @Nullable Spark spark() {
        if (!enabled()) return null;
        Spark cached = CACHED.get();
        if (cached != null) return cached;
        try {
            Spark s = SparkProvider.get();
            CACHED.compareAndSet(null, s);
            return s;
        } catch (Throwable t) {
            // SparkProvider.get() throws IllegalStateException until spark
            // calls SparkProvider.set() during its plugin enable. Treat
            // every failure as a soft miss so the caller can fall back to
            // the bukkit / NMS statistic source.
            return null;
        }
    }

    public static boolean isReady() {
        return spark() != null;
    }

    /** Snapshot of TPS rolling windows (5s, 10s, 1m, 5m, 15m). */
    public static double[] tpsWindows() {
        Spark s = spark();
        if (s == null) return null;
        try {
            DoubleStatistic<StatisticWindow.TicksPerSecond> stat = s.tps();
            return stat == null ? null : stat.poll();
        } catch (Throwable t) {
            SourbyLogger.warn("spark.tps() failed: " + t.getMessage());
            return null;
        }
    }

    /** Snapshot of MSPT averages keyed by spark window (10s, 1m, 5m). */
    public static DoubleAverageInfo[] msptWindows() {
        Spark s = spark();
        if (s == null) return null;
        try {
            GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> stat = s.mspt();
            return stat == null ? null : stat.poll();
        } catch (Throwable t) {
            SourbyLogger.warn("spark.mspt() failed: " + t.getMessage());
            return null;
        }
    }

    public static double[] cpuProcess() {
        Spark s = spark();
        if (s == null) return null;
        try { return s.cpuProcess().poll(); } catch (Throwable t) { return null; }
    }

    public static double[] cpuSystem() {
        Spark s = spark();
        if (s == null) return null;
        try { return s.cpuSystem().poll(); } catch (Throwable t) { return null; }
    }

    public static @Nullable Map<String, GarbageCollector> garbageCollectors() {
        Spark s = spark();
        if (s == null) return null;
        try { return s.gc(); } catch (Throwable t) { return null; }
    }
}
