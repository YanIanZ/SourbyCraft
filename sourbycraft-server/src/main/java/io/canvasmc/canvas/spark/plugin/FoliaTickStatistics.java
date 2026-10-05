package io.canvasmc.canvas.spark.plugin;

import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import dev.iyanz.sourbycraft.perf.MetricsRuntime;
import io.canvasmc.canvas.threadedregions.profiler.RegionProfiler;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.paper.common.monitor.tick.TickStatistics;

public class FoliaTickStatistics implements TickStatistics {
    private final SourbyMetrics metrics;

    public FoliaTickStatistics() {
        this(MetricsRuntime.provider());
    }

    public FoliaTickStatistics(final SourbyMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public int gameTargetTps() {
        // rounding is probably easier and more accurate tbh
        return Math.round(TickRegionScheduler.getTickRate());
    }

    @Override
    public double tps5Sec() {
        return this.tps(MetricWindow.FIVE_SECONDS);
    }

    @Override
    public double tps10Sec() {
        return this.tps(MetricWindow.TEN_SECONDS);
    }

    @Override
    public double tps1Min() {
        return this.tps(MetricWindow.ONE_MINUTE);
    }

    @Override
    public double tps5Min() {
        return this.tps(MetricWindow.FIVE_MINUTES);
    }

    @Override
    public double tps15Min() {
        return this.tps(MetricWindow.FIFTEEN_MINUTES);
    }

    @Override
    public boolean isDurationSupported() {
        return true;
    }

    @Override
    public DoubleAverageInfo duration10Sec() {
        return this.duration(MetricWindow.TEN_SECONDS);
    }

    @Override
    public DoubleAverageInfo duration1Min() {
        return this.duration(MetricWindow.ONE_MINUTE);
    }

    @Override
    public DoubleAverageInfo duration5Min() {
        return this.duration(MetricWindow.FIVE_MINUTES);
    }

    private double tps(final MetricWindow window) {
        final RegionProfiler.ProfilingState profilingState = RegionProfiler.STATE.get();
        if (profilingState != null) {
            final double local = selectedWindow(profilingState.regionScheduleHandle(), window).tps();
            return Double.isNaN(local) ? this.gameTargetTps() : local;
        }

        // SourbyCraft - worst region, matching MSPT above and the headline figure /tps, /perf
        // and the bars all show. A mean across regions reads healthy while one region is not.
        final PerformanceSnapshot snapshot = this.metrics.snapshot();
        return snapshot.window(window).worstTps();
    }

    private DoubleAverageInfo duration(final MetricWindow window) {
        final RegionProfiler.ProfilingState profilingState = RegionProfiler.STATE.get();
        if (profilingState != null) {
            return AverageInfo.from(selectedWindow(profilingState.regionScheduleHandle(), window));
        }
        final PerformanceSnapshot snapshot = this.metrics.snapshot();
        return AverageInfo.from(snapshot.window(window));
    }

    private static RegionTickMetrics.WindowSnapshot selectedWindow(
        final TickRegionScheduler.RegionScheduleHandle handle, final MetricWindow window
    ) {
        final RegionTickMetrics.Snapshot snapshot = handle.sourbyTickMetrics.current().snapshot(System.nanoTime());
        return switch (window) {
            case FIVE_SECONDS -> snapshot.fiveSeconds();
            case TEN_SECONDS -> snapshot.tenSeconds();
            case ONE_MINUTE -> snapshot.oneMinute();
            case FIVE_MINUTES -> snapshot.fiveMinutes();
            case FIFTEEN_MINUTES -> snapshot.fifteenMinutes();
        };
    }

    private record AverageInfo(double mean, double min, double max, double median,
                               double percentile95th) implements DoubleAverageInfo {

        private static AverageInfo from(final WindowMetrics window) {
            // SourbyCraft - report the worst region's average, not the mean across regions.
            // /tps, /mspt and the TPS/RAM bars all read worstAverageMspt, and a Spark report
            // reading aggregateAverageMspt disagreed with them on the same server at the same
            // moment — 2.13 ms against 5 ms with fifty regions, because the mean hides a single
            // loaded region. PRD section 81 requires every surface to answer from one source.
            // The worst region is also the honest answer to "is anyone lagging": on a
            // region-threaded server there is no single tick duration, and players in the
            // slowest region feel that region, not the average.
            return new AverageInfo(window.worstAverageMspt(), window.minimumMspt(), window.maximumMspt(),
                window.medianMspt(), window.estimatedP95Mspt());
        }

        private static AverageInfo from(final RegionTickMetrics.WindowSnapshot window) {
            if (window.sampleCount() == 0L) {
                return new AverageInfo(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            return new AverageInfo(window.mspt(), window.minimumNanos() * 1.0E-6,
                window.maximumNanos() * 1.0E-6, window.medianNanos() * 1.0E-6,
                window.estimatedP95Mspt());
        }

        @Override
        public double percentile(final double percentile) {
            if (percentile == 0.5) return this.median;
            if (percentile == 0.95) return this.percentile95th;
            return Double.NaN;
        }
    }
}
