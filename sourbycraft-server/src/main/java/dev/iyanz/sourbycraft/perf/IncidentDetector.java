package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import dev.iyanz.sourbycraft.perf.IncidentLog.Breach;
import dev.iyanz.sourbycraft.perf.IncidentLog.Kind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns telemetry that already exists into {@link IncidentLog} breaches, once per metrics
 * collection (about once a second, on the collector thread; never on a tick thread).
 *
 * <p>Signals and thresholds. Thresholds are fixed starting points, not tuned values, and there is
 * deliberately no configuration key for them or for the history size (PRD §39 asks only for a
 * bounded, lightweight history).</p>
 * <ul>
 *   <li>{@link Kind#REGION_OVER_BUDGET}: the slowest active region's 5 s average MSPT above the tick
 *       budget ({@code 1000 / target TPS} ms, 50 ms at 20 TPS). This is a 5 s average seen once a
 *       second, not a count of consecutive over-budget ticks: per-tick counting would need a hook
 *       on the region tick path.</li>
 *   <li>{@link Kind#GLOBAL_TICK_SLOW}: the global tick's 5 s maximum MSPT (the slowest completed tick,
 *       or the real elapsed time of a global tick still running when that is longer; it is no longer
 *       clamped to the window length) at or above {@link #GLOBAL_TICK_SLOW_MS}. Because it is a 5 s
 *       window maximum, one slow completed tick stays visible for about 5 s, so the incident's
 *       duration is inflated by up to the window length. A running-tick marker cannot outlive the
 *       tick: {@link RegionTickMetrics} drops a marker that a completed tick has covered, so a
 *       marker whose completion was lost no longer produces a standing incident; only a global
 *       tick that really has not returned does, and its observed value is then its true elapsed
 *       time.</li>
 *   <li>{@link Kind#GC_TIME}: cumulative MXBean collection time grew by at least
 *       {@link #GC_TIME_MS} since the previous pass. Collection time includes concurrent collector
 *       work; it is not a stop-the-world pause.</li>
 *   <li>{@link Kind#HEAP_PRESSURE}: heap used at or above {@link #HEAP_PRESSURE_PERCENT} of the
 *       maximum (only when the maximum is defined).</li>
 *   <li>{@link Kind#LANE_REJECTED}: a Resource Governor lane's rejected counter grew (per lane).</li>
 *   <li>{@link Kind#ASYNC_PATH_REFUSED}: the async path pool's refused counter grew.</li>
 *   <li>{@link Kind#STORAGE_BACKLOG}: a world's pending Moonrise I/O tasks at or above
 *       {@link #STORAGE_BACKLOG_TASKS}.</li>
 *   <li>{@link Kind#AWF_COMMIT_FAILURE}: an AWF storage's failure counter grew (commit or read
 *       failures; the counter does not separate them).</li>
 *   <li>{@link Kind#BRIDGE_FATAL_VIOLATION}: a bridged plugin's fatal ownership-violation counter grew.</li>
 * </ul>
 * <p>Counter-based signals compare against the previous pass; the first pass only takes the
 * baseline, and a counter that goes backwards (a restarted subsystem) is re-baselined. A subject
 * missing (or unavailable) for more than {@link #FORGET_AFTER_PASSES} passes in a row (an unloaded
 * AWF world, a removed plugin) is forgotten, so the baselines stay bounded on a server that keeps creating uniquely named
 * worlds (audit PM-308); if it comes back it is baselined again. Short gaps, such as a source that
 * failed for a few passes, keep the baseline, so growth across the gap is still reported.</p>
 */
public final class IncidentDetector {

    public static final double GLOBAL_TICK_SLOW_MS = 250.0;
    public static final double GC_TIME_MS = 200.0;
    public static final double HEAP_PRESSURE_PERCENT = 90.0;
    public static final long STORAGE_BACKLOG_TASKS = 1_000L;
    /** Tick budget used when the snapshot has no valid target TPS. */
    public static final double DEFAULT_TICK_BUDGET_MS = 50.0;
    /** Passes a counter subject may be missing before its baseline is dropped (15 minutes at one pass a second). */
    static final long FORGET_AFTER_PASSES = 900L;

    /** A monotonic counter reading for one subject. */
    public record Counter(String subject, long value) {}

    /** A gauge reading for one subject. */
    public record Gauge(String subject, long value) {}

    /** Readings from subsystems outside the performance snapshot. Each list may be empty. */
    public record External(List<Counter> laneRejections, long asyncPathRefused, List<Gauge> storagePending,
                           List<Counter> awfFailures, List<Counter> bridgeFatalViolations) {
        public static final External NONE = new External(List.of(), -1L, List.of(), List.of(), List.of());
    }

    /** Reads {@link External}; the live one reads the governor, async path pool, Moonrise I/O, AWF and bridge. */
    public interface Sources {
        External read();
    }

    /** The process's detector, feeding {@link IncidentLog#GLOBAL}; called by the metrics collector. */
    public static final IncidentDetector GLOBAL = new IncidentDetector(IncidentLog.GLOBAL, new LiveIncidentSources());

    private final IncidentLog log;
    private final Sources sources;
    /** Counter baselines by signal and subject. Guarded by this detector's monitor, like {@link #pass}. */
    private final Map<String, Baseline> previous = new HashMap<>();
    private long pass;

    /** A counter's last reading and the pass that read it. */
    private static final class Baseline {
        long value;
        long seenAtPass;

        Baseline(final long value, final long seenAtPass) {
            this.value = value;
            this.seenAtPass = seenAtPass;
        }
    }

    public IncidentDetector(final IncidentLog log, final Sources sources) {
        this.log = Objects.requireNonNull(log, "log");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /**
     * One pass. Never throws: a failing source is skipped for this pass so the others still count.
     *
     * @param slowest the slowest active region at this collection, or null
     */
    public void observe(final long nowEpochMillis, final PerformanceSnapshot snapshot, final SlowRegion slowest) {
        External external;
        try {
            external = this.sources.read();
        } catch (final RuntimeException failure) {
            external = External.NONE;
        }
        final List<Breach> breaches;
        synchronized (this) {
            breaches = this.breaches(snapshot, slowest, external);
        }
        this.log.pass(nowEpochMillis, breaches);
    }

    List<Breach> breaches(final PerformanceSnapshot snapshot, final SlowRegion slowest, final External external) {
        ++this.pass;
        final List<Breach> out = new ArrayList<>();
        if (snapshot != null) {
            final double budget = budgetMillis(snapshot.targetTps());
            if (slowest != null && Double.isFinite(slowest.averageMspt()) && slowest.averageMspt() > budget) {
                out.add(new Breach(Kind.REGION_OVER_BUDGET,
                    "world #" + slowest.worldId() + " region #" + slowest.regionId(), slowest.averageMspt(), budget));
            }
            final double globalMax = snapshot.globalWindow(MetricWindow.FIVE_SECONDS).maximumMspt();
            if (Double.isFinite(globalMax) && globalMax >= GLOBAL_TICK_SLOW_MS) {
                out.add(new Breach(Kind.GLOBAL_TICK_SLOW, "global tick", globalMax, GLOBAL_TICK_SLOW_MS));
            }
            final RuntimeMetrics runtime = snapshot.runtime();
            if (runtime != null) {
                final long gcGrowth = this.growth("gc", runtime.gcCollectionTimeMillis());
                if (gcGrowth >= GC_TIME_MS) {
                    out.add(new Breach(Kind.GC_TIME, "all collectors", gcGrowth, GC_TIME_MS));
                }
                final long used = runtime.heapUsedBytes();
                final long max = runtime.heapMaxBytes();
                if (used >= 0L && max > 0L) {
                    final double percent = 100.0 * used / max;
                    if (percent >= HEAP_PRESSURE_PERCENT) {
                        out.add(new Breach(Kind.HEAP_PRESSURE, "heap", percent, HEAP_PRESSURE_PERCENT));
                    }
                }
            }
        }
        for (final Counter lane : external.laneRejections()) {
            this.counter(out, Kind.LANE_REJECTED, lane);
        }
        this.counter(out, Kind.ASYNC_PATH_REFUSED, new Counter("async path pool", external.asyncPathRefused()));
        for (final Gauge world : external.storagePending()) {
            if (world.value() >= STORAGE_BACKLOG_TASKS) {
                out.add(new Breach(Kind.STORAGE_BACKLOG, world.subject(), world.value(), STORAGE_BACKLOG_TASKS));
            }
        }
        for (final Counter storage : external.awfFailures()) {
            this.counter(out, Kind.AWF_COMMIT_FAILURE, storage);
        }
        for (final Counter plugin : external.bridgeFatalViolations()) {
            this.counter(out, Kind.BRIDGE_FATAL_VIOLATION, plugin);
        }
        this.previous.values().removeIf(baseline -> this.pass - baseline.seenAtPass > FORGET_AFTER_PASSES);
        return out;
    }

    private void counter(final List<Breach> out, final Kind kind, final Counter counter) {
        final long growth = this.growth(kind.name() + '/' + counter.subject(), counter.value());
        if (growth >= 1L) {
            out.add(new Breach(kind, counter.subject(), growth, 1.0));
        }
    }

    /**
     * How much a monotonic counter grew since the last pass. Zero on the first reading, for an
     * unavailable reading (negative), and when the counter went backwards (re-baselined).
     */
    private long growth(final String key, final long value) {
        if (value < 0L) {
            return 0L;
        }
        final Baseline baseline = this.previous.get(key);
        if (baseline == null) {
            this.previous.put(key, new Baseline(value, this.pass));
            return 0L;
        }
        final long before = baseline.value;
        baseline.value = value;
        baseline.seenAtPass = this.pass;
        return value < before ? 0L : value - before;
    }

    static double budgetMillis(final double targetTps) {
        return Double.isFinite(targetTps) && targetTps > 0.0 ? 1000.0 / targetTps : DEFAULT_TICK_BUDGET_MS;
    }
}
