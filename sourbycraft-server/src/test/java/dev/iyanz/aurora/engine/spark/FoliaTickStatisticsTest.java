package dev.iyanz.aurora.engine.spark;

import dev.iyanz.aurora.engine.spark.plugin.FoliaTickStatistics;
import dev.iyanz.aurora.engine.threadedregions.profiler.RegionProfiler;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import dev.iyanz.sourbycraft.perf.RegionTickMetricsHolder;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.lang.reflect.Field;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine spark: {@link FoliaTickStatistics}, the TPS and tick duration figures a spark report
 * carries. Outside a region profile every figure is the server's WORST region (the same figure
 * {@code /tps}, {@code /mspt} and the bars show); inside one it is the pinned region's own window,
 * and a window with no samples reads as unknown (NaN), with TPS falling back to the target rate.
 */
class FoliaTickStatisticsTest {

    private RegionProfiler.ProfilingState previousState;

    @BeforeEach
    void noRegionProfileRuns() {
        this.previousState = RegionProfiler.STATE.getAndSet(null);
    }

    @AfterEach
    void restoreProfilingState() {
        RegionProfiler.STATE.set(this.previousState);
    }

    private static SourbyMetrics metricsWith(final MetricWindow window, final WindowMetrics metrics) {
        final PerformanceSnapshot snapshot = mock(PerformanceSnapshot.class);
        when(snapshot.window(window)).thenReturn(metrics);
        final SourbyMetrics source = mock(SourbyMetrics.class);
        when(source.snapshot()).thenReturn(snapshot);
        return source;
    }

    private static WindowMetrics window(final double worstTps, final double worstMspt, final double min, final double max,
                                        final double median, final double p95) {
        final WindowMetrics window = mock(WindowMetrics.class);
        when(window.worstTps()).thenReturn(worstTps);
        when(window.worstAverageMspt()).thenReturn(worstMspt);
        when(window.minimumMspt()).thenReturn(min);
        when(window.maximumMspt()).thenReturn(max);
        when(window.medianMspt()).thenReturn(median);
        when(window.estimatedP95Mspt()).thenReturn(p95);
        return window;
    }

    @Test
    void tpsOfEveryWindowComesFromThatWindowsWorstRegion() {
        for (final MetricWindow w : MetricWindow.values()) {
            final FoliaTickStatistics stats = new FoliaTickStatistics(metricsWith(w, window(17.25, 0, 0, 0, 0, 0)));
            final double tps = switch (w) {
                case FIVE_SECONDS -> stats.tps5Sec();
                case TEN_SECONDS -> stats.tps10Sec();
                case ONE_MINUTE -> stats.tps1Min();
                case FIVE_MINUTES -> stats.tps5Min();
                case FIFTEEN_MINUTES -> stats.tps15Min();
            };
            assertEquals(17.25, tps, 0.0, w.name());
        }
    }

    @Test
    void durationsCarryTheWorstAverageAndTheWindowsExtremes() {
        final WindowMetrics ten = window(20, 31.5, 2.0, 80.0, 25.0, 60.0);
        final FoliaTickStatistics stats = new FoliaTickStatistics(metricsWith(MetricWindow.TEN_SECONDS, ten));

        final DoubleAverageInfo info = stats.duration10Sec();

        assertEquals(31.5, info.mean(), 0.0, "the worst region's average, not a mean across regions");
        assertEquals(2.0, info.min(), 0.0);
        assertEquals(80.0, info.max(), 0.0);
        assertEquals(25.0, info.median(), 0.0);
        assertEquals(60.0, info.percentile95th(), 0.0);
        assertEquals(25.0, info.percentile(0.5), 0.0);
        assertEquals(60.0, info.percentile(0.95), 0.0);
        assertTrue(Double.isNaN(info.percentile(0.99)), "only the median and p95 are known");
        assertTrue(Double.isNaN(info.percentile(0.0)));
    }

    @Test
    void oneAndFiveMinuteDurationsReadTheirOwnWindows() {
        final FoliaTickStatistics oneMinute = new FoliaTickStatistics(metricsWith(MetricWindow.ONE_MINUTE, window(20, 11, 1, 2, 3, 4)));
        assertEquals(11.0, oneMinute.duration1Min().mean(), 0.0);
        final FoliaTickStatistics fiveMinutes = new FoliaTickStatistics(metricsWith(MetricWindow.FIVE_MINUTES, window(20, 22, 1, 2, 3, 4)));
        assertEquals(22.0, fiveMinutes.duration5Min().mean(), 0.0);
    }

    @Test
    void durationIsSupported() {
        assertTrue(new FoliaTickStatistics(mock(SourbyMetrics.class)).isDurationSupported());
    }

    // gameTargetTps() reads TickRegionScheduler, whose static initialiser needs a running server and, once it has
    // failed, fails every later use in the same JVM. It is therefore never called from a unit test, and the paths
    // that fall back to it (a pinned window without samples, TPS only) are not covered.

    // ---- inside a region profile

    private static RegionTickMetrics.WindowSnapshot sampled(final long count, final double tps, final double mspt) {
        return new RegionTickMetrics.WindowSnapshot(count, 0L, 0L, 2_000_000L, 25_000_000.0, 80_000_000L, 0L,
            tps, mspt, 0.0, 60.0, 90.0, false, false);
    }

    private static RegionTickMetrics.WindowSnapshot empty() {
        return sampled(0L, Double.NaN, Double.NaN);
    }

    private static void pinRegionWith(final RegionTickMetrics.Snapshot snapshot) throws Exception {
        final RegionTickMetrics metrics = mock(RegionTickMetrics.class);
        when(metrics.snapshot(anyLong())).thenReturn(snapshot);
        final RegionTickMetricsHolder holder = mock(RegionTickMetricsHolder.class);
        when(holder.current()).thenReturn(metrics);
        final TickRegionScheduler.RegionScheduleHandle handle = mock(TickRegionScheduler.RegionScheduleHandle.class);
        final Field field = TickRegionScheduler.RegionScheduleHandle.class.getField("sourbyTickMetrics");
        field.setAccessible(true);
        field.set(handle, holder);
        RegionProfiler.STATE.set(new RegionProfiler.ProfilingState(handle, null, null));
    }

    private static RegionTickMetrics.Snapshot snapshotWith(final RegionTickMetrics.WindowSnapshot fiveSeconds, final RegionTickMetrics.WindowSnapshot tenSeconds,
                                                           final RegionTickMetrics.WindowSnapshot oneMinute, final RegionTickMetrics.WindowSnapshot fiveMinutes,
                                                           final RegionTickMetrics.WindowSnapshot fifteenMinutes) {
        return new RegionTickMetrics.Snapshot(1L, 0L, 0L, fiveSeconds, tenSeconds, empty(), oneMinute, fiveMinutes, fifteenMinutes);
    }

    @Test
    void insideARegionProfileEachWindowReadsThePinnedRegionsOwnFigures() throws Exception {
        // note the record order: (sequence, sampledAt, activeStart, 5s, 10s, 15s, 1m, 5m, 15m)
        final RegionTickMetrics.Snapshot snapshot = new RegionTickMetrics.Snapshot(1L, 0L, 0L,
            sampled(5, 5.0, 5.5), sampled(10, 10.0, 10.5), empty(), sampled(60, 1.0, 1.5), sampled(300, 5.0, 5.5), sampled(900, 15.0, 15.5));
        pinRegionWith(snapshot);
        final FoliaTickStatistics stats = new FoliaTickStatistics(mock(SourbyMetrics.class));

        assertEquals(5.0, stats.tps5Sec(), 0.0);
        assertEquals(10.0, stats.tps10Sec(), 0.0);
        assertEquals(1.0, stats.tps1Min(), 0.0);
        assertEquals(5.0, stats.tps5Min(), 0.0);
        assertEquals(15.0, stats.tps15Min(), 0.0);
        assertEquals(10.5, stats.duration10Sec().mean(), 0.0);
        assertEquals(1.5, stats.duration1Min().mean(), 0.0);
        assertEquals(5.5, stats.duration5Min().mean(), 0.0);
    }

    @Test
    void aPinnedWindowsDurationConvertsNanosecondsToMilliseconds() throws Exception {
        pinRegionWith(snapshotWith(empty(), sampled(10, 20.0, 12.0), empty(), empty(), empty()));

        final DoubleAverageInfo info = new FoliaTickStatistics(mock(SourbyMetrics.class)).duration10Sec();

        assertEquals(12.0, info.mean(), 0.0);
        assertEquals(2.0, info.min(), 1e-9, "2_000_000 ns");
        assertEquals(80.0, info.max(), 1e-9, "80_000_000 ns");
        assertEquals(25.0, info.median(), 1e-9, "25_000_000 ns");
        assertEquals(60.0, info.percentile95th(), 0.0);
    }

    @Test
    void aPinnedWindowWithoutSamplesHasUnknownDurations() throws Exception {
        pinRegionWith(snapshotWith(empty(), empty(), empty(), empty(), empty()));

        final DoubleAverageInfo info = new FoliaTickStatistics(mock(SourbyMetrics.class)).duration10Sec();

        assertTrue(Double.isNaN(info.mean()) && Double.isNaN(info.min()) && Double.isNaN(info.max())
            && Double.isNaN(info.median()) && Double.isNaN(info.percentile95th()));
    }
}
