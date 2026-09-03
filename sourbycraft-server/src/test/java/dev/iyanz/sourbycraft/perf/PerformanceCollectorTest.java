package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.RegionTickMetrics;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceCollectorTest {

    private static final long MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);

    @Test
    void unequalCoverageUsesAdditiveAggregateAndActiveWorstAndMedian() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final PerformanceCollector collector = collector(provider, source(
            view(1L, true, window(1L, 100L * MILLISECOND, 10L * MILLISECOND, 0.1)),
            view(2L, true, window(6L, 300L * MILLISECOND, 60L * MILLISECOND, 0.2)),
            view(3L, true, window(4L, 250L * MILLISECOND, 80L * MILLISECOND, 0.4))
        ));

        collector.collect(SECOND, 1_000L, 0L);
        final WindowMetrics result = provider.snapshot().window(MetricWindow.FIVE_SECONDS);

        assertEquals(10.0, result.worstTps(), 1.0E-9);
        assertEquals(16.0, result.medianTps(), 1.0E-9);
        assertEquals(16.923076923076923, result.aggregateTps(), 1.0E-9);
        assertEquals(20.0, result.worstAverageMspt(), 1.0E-9);
        assertEquals(150.0 / 11.0, result.aggregateAverageMspt(), 1.0E-9);
        assertEquals(10.0, result.medianAverageMspt(), 1.0E-9);
    }

    @Test
    void targetRateIsSampledOnEveryCollectionAndInvalidValuesStayUnavailable() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final AtomicReference<Double> target = new AtomicReference<>(10.0);
        final PerformanceCollector collector = new PerformanceCollector(provider, source(),
            PerformanceCollectorTest::emptyGlobal, PerformanceCollectorTest::runtime,
            System::nanoTime, System::currentTimeMillis, target::get);

        for (final double expected : new double[] {10.0, 20.0, 40.0}) {
            target.set(expected);
            collector.collect(SECOND, 1_000L, 0L);
            assertEquals(expected, provider.snapshot().targetTps());
        }
        for (final double invalid : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            target.set(invalid);
            collector.collect(SECOND, 1_000L, 0L);
            assertTrue(Double.isNaN(provider.snapshot().targetTps()));
        }
    }

    @Test
    void pooledHistogramAndAggregateMeanWeightEveryCompletedTick() {
        final RegionTickMetrics manyFast = metricsWithDurations(100, MILLISECOND);
        final RegionTickMetrics oneSlow = metricsWithDurations(1, 100L * MILLISECOND);
        final PerformanceCollector.GenerationSource generations = (now, consumer) -> {
            consumer.accept(new RegionMetricsRegistry.GenerationView(
                1L, 1L, 1L, true, manyFast, manyFast.refreshSnapshot(now)));
            consumer.accept(new RegionMetricsRegistry.GenerationView(
                2L, 1L, 2L, true, oneSlow, oneSlow.refreshSnapshot(now)));
        };
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();

        collector(provider, generations).collect(2L * SECOND, 2_000L, 0L);
        final WindowMetrics result = provider.snapshot().window(MetricWindow.FIVE_SECONDS);

        assertEquals(100.0, result.worstAverageMspt(), 1.0E-9);
        assertEquals(200.0 / 101.0, result.aggregateAverageMspt(), 1.0E-9);
        assertTrue(result.medianMspt() < 2.0, "pooled median must follow the 100 fast samples");
        assertTrue(result.estimatedP95Mspt() < 2.0, "pooled p95 must not be the worst region p95");
        assertEquals(101L, result.sampleCount());
    }

    @Test
    void requiredUnequalCoverageExampleProducesSeventeenPointFiveAggregate() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final PerformanceCollector collector = collector(provider, source(
            view(1L, true, window(1L, 100L * MILLISECOND, 10L * MILLISECOND, 0.1)),
            view(2L, true, window(6L, 300L * MILLISECOND, 60L * MILLISECOND, 0.2))
        ));

        collector.collect(SECOND, 1_000L, 0L);
        final WindowMetrics result = provider.snapshot().window(MetricWindow.FIVE_SECONDS);

        assertEquals(17.5, result.aggregateTps(), 1.0E-9);
        assertEquals(10.0, result.worstTps(), 1.0E-9);
    }

    @Test
    void retiredGenerationContributesHistoryButNotActiveTopology() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final PerformanceCollector collector = collector(provider, source(
            view(1L, true, window(1L, 50L * MILLISECOND, 5L * MILLISECOND, 0.1)),
            view(2L, false, window(1L, 100L * MILLISECOND, 40L * MILLISECOND, 0.8))
        ));

        collector.collect(SECOND, 1_000L, 0L);
        final PerformanceSnapshot snapshot = provider.snapshot();
        final WindowMetrics result = snapshot.window(MetricWindow.FIVE_SECONDS);

        assertEquals(1, snapshot.activeRegionCount());
        assertEquals(2, snapshot.retainedGenerationCount());
        assertEquals(20.0, result.worstTps(), 1.0E-9);
        assertEquals(13.333333333333334, result.aggregateTps(), 1.0E-9);
        assertEquals(40.0, result.maximumMspt(), 1.0E-9);
        assertEquals(22.5, result.totalMissingCpuMs(), 1.0E-9);
    }

    @Test
    void collectorUsesCanonicalRegistryIterationAndDoesNotInventGlobalRegion() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final RegionTickMetricsHolder holder = new RegionTickMetricsHolder();
        holder.tickCompleted(new TickTime(TimeUtil.DEADLINE_NOT_SET, SECOND - MILLISECOND,
            SECOND - MILLISECOND, 0L, SECOND, 0L, 0L, 0L, false), 50L * MILLISECOND);
        registry.activate(registry.newWorldId(), 7L, holder, SECOND);
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();

        new PerformanceCollector(provider, registry::forEachUnexpired,
            PerformanceCollectorTest::emptyGlobal, PerformanceCollectorTest::runtime,
            System::nanoTime, System::currentTimeMillis, () -> 20.0).collect(SECOND, 1_000L, 0L);

        assertEquals(1, provider.snapshot().activeRegionCount());
        assertEquals(1, provider.snapshot().retainedGenerationCount());
    }

    @Test
    void globalSnapshotIsRetainedSeparatelyFromSpatialRegionTopology() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final RegionTickMetrics.Snapshot global = snapshot(
            window(2L, 100L * MILLISECOND, 80L * MILLISECOND, 0.8), RegionTickMetrics.INACTIVE);
        final PerformanceCollector collector = new PerformanceCollector(provider,
            source(view(1L, true, window(1L, 100L * MILLISECOND, 10L * MILLISECOND, 0.1))),
            now -> global, PerformanceCollectorTest::runtime,
            System::nanoTime, System::currentTimeMillis, () -> 20.0);

        collector.collect(SECOND, 1_000L, 0L);
        final ImmutablePerformanceSnapshot snapshot = (ImmutablePerformanceSnapshot)provider.snapshot();

        assertEquals(1, snapshot.activeRegionCount());
        assertEquals(1, snapshot.retainedGenerationCount());
        assertEquals(10.0, snapshot.window(MetricWindow.FIVE_SECONDS).worstTps(), 1.0E-9);
        assertEquals(20.0, snapshot.global().window(MetricWindow.FIVE_SECONDS).aggregateTps(), 1.0E-9);
        assertEquals(40.0, snapshot.global().window(MetricWindow.FIVE_SECONDS).maximumMspt(), 1.0E-9);
    }

    @Test
    void stalledTickOverlaysActiveMsptMaximumAndUtilisationWithoutFabricatingCompletion() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final RegionTickMetrics.WindowSnapshot completed = window(
            1L, 50L * MILLISECOND, 10L * MILLISECOND, 0.1);
        final RegionTickMetrics.Snapshot stalled = snapshot(completed, SECOND - 500L * MILLISECOND);
        final PerformanceCollector collector = collector(provider,
            (now, consumer) -> consumer.accept(new RegionMetricsRegistry.GenerationView(1L, 1L, 1L, true, null, stalled)));

        collector.collect(SECOND, 1_000L, 0L);
        final WindowMetrics result = provider.snapshot().window(MetricWindow.FIVE_SECONDS);

        assertEquals(1L, result.sampleCount());
        assertEquals(255.0, result.worstAverageMspt(), 1.0E-9);
        assertEquals(500.0, result.maximumMspt(), 1.0E-9);
        assertEquals(0.2, result.busiestUtilisation(), 1.0E-9);
    }

    @Test
    void inProgressOnlyTickPublishesMaximumWithoutFabricatingCompletedAverage() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final RegionTickMetrics.WindowSnapshot empty = window(0L, 0L, 0L, 0.0);
        final RegionTickMetrics.Snapshot stalled = snapshot(empty, SECOND - 500L * MILLISECOND);

        collector(provider, (now, consumer) -> consumer.accept(
            new RegionMetricsRegistry.GenerationView(1L, 1L, 1L, true, null, stalled)))
            .collect(SECOND, 1_000L, 0L);
        final WindowMetrics result = provider.snapshot().window(MetricWindow.FIVE_SECONDS);

        assertEquals(0L, result.sampleCount());
        assertEquals(500.0, result.maximumMspt(), 1.0E-9);
        assertTrue(Double.isNaN(result.worstAverageMspt()));
        assertTrue(Double.isNaN(result.medianAverageMspt()));
    }

    @Test
    void truncationAndOverflowPropagateUnavailableAdditiveAndQuantileValues() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final RegionTickMetrics.WindowSnapshot overflow = new RegionTickMetrics.WindowSnapshot(
            2L, Long.MAX_VALUE, Long.MAX_VALUE, MILLISECOND, Double.NaN, 10L * MILLISECOND,
            Long.MAX_VALUE, Double.NaN, Double.NaN, 0.5, Double.NaN, Double.NaN, true, true);

        collector(provider, source(view(1L, true, overflow))).collect(SECOND, 1_000L, 0L);
        final WindowMetrics result = provider.snapshot().window(MetricWindow.FIVE_SECONDS);

        assertTrue(result.truncated());
        assertTrue(Double.isNaN(result.aggregateTps()));
        assertTrue(Double.isNaN(result.medianMspt()));
        assertTrue(Double.isNaN(result.estimatedP95Mspt()));
        assertTrue(Double.isNaN(result.totalMissingCpuMs()));
    }

    @Test
    void emptyHistoryIsWarmingAndNeverFabricatesHealthyZeros() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();

        collector(provider, source()).collect(SECOND, 1_000L, 0L);
        final PerformanceSnapshot snapshot = provider.snapshot();

        assertEquals(MetricState.WARMING, snapshot.freshness().state());
        assertTrue(Double.isNaN(snapshot.window(MetricWindow.FIVE_SECONDS).worstTps()));
        assertTrue(Double.isNaN(snapshot.window(MetricWindow.FIVE_SECONDS).aggregateTps()));
    }

    @Test
    void completeCoverageBecomesAvailable() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final long fifteenMinutes = TimeUnit.MINUTES.toNanos(15L);

        collector(provider, source(view(1L, true,
            window(1L, fifteenMinutes, MILLISECOND, 0.1)))).collect(fifteenMinutes, 900_000L, 0L);

        assertEquals(MetricState.AVAILABLE, provider.snapshot().freshness().state());
    }

    @Test
    void activeGenerationWithoutSamplesDoesNotFabricateHealthyUtilisation() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final RegionTickMetrics.WindowSnapshot empty = new RegionTickMetrics.WindowSnapshot(
            0L, 0L, 0L, 0L, Double.NaN, 0L, 0L,
            Double.NaN, Double.NaN, 0.0, Double.NaN, Double.NaN, true, false);

        collector(provider, source(view(1L, true, empty))).collect(SECOND, 1_000L, 0L);
        final PerformanceSnapshot snapshot = provider.snapshot();

        assertEquals(1, snapshot.activeRegionCount());
        assertTrue(Double.isNaN(snapshot.window(MetricWindow.FIVE_SECONDS).busiestUtilisation()));
        assertTrue(Double.isNaN(snapshot.window(MetricWindow.FIVE_SECONDS).averageUtilisation()));
    }

    @Test
    void failureRetainsPriorValuesAndPublishesStaleTimingDiagnostic() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final AtomicBoolean fail = new AtomicBoolean();
        final PerformanceCollector.GenerationSource source = (now, consumer) -> {
            consumer.accept(view(1L, true, window(1L, 50L * MILLISECOND, 5L * MILLISECOND, 0.1)));
            if (fail.get()) throw new IllegalStateException("source failed");
        };
        final long[] clockValues = {100L, 137L, 200L, 245L};
        final AtomicInteger clockIndex = new AtomicInteger();
        final PerformanceCollector collector = new PerformanceCollector(provider, source,
            PerformanceCollectorTest::emptyGlobal, PerformanceCollectorTest::runtime,
            () -> clockValues[clockIndex.getAndIncrement()],
            System::currentTimeMillis, () -> 20.0);
        collector.collect(SECOND, 1_000L, 7L * MILLISECOND);
        final WindowMetrics previous = provider.snapshot().window(MetricWindow.FIVE_SECONDS);

        fail.set(true);
        collector.collect(3L * SECOND, 3_000L, 2L * SECOND);
        final PerformanceSnapshot stale = provider.snapshot();

        assertSame(previous, stale.window(MetricWindow.FIVE_SECONDS));
        assertEquals(MetricState.STALE, stale.freshness().state());
        assertEquals(2_000L, stale.freshness().ageMillis());
        assertEquals(2_000L, stale.freshness().collectorLatenessMillis());
        assertEquals(45L, stale.freshness().scanDurationNanos());
        assertTrue(stale.freshness().diagnostic().contains("source failed"));
    }

    @Test
    void repeatedCollectionFailuresAreLoggedAtMostOncePerMinute() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final AtomicInteger logs = new AtomicInteger();
        final PerformanceCollector collector = new PerformanceCollector(provider,
            (now, consumer) -> { throw new IllegalStateException("broken source"); },
            PerformanceCollectorTest::emptyGlobal, PerformanceCollectorTest::runtime,
            System::nanoTime, System::currentTimeMillis, () -> 20.0,
            (message, failure) -> logs.incrementAndGet());

        collector.collect(SECOND, 1_000L, 0L);
        collector.collect(2L * SECOND, 2_000L, 0L);
        collector.collect(61L * SECOND, 61_000L, 0L);

        assertEquals(2, logs.get());
        assertEquals(MetricState.STALE, provider.snapshot().freshness().state());
    }

    @Test
    void overPeriodLatenessRetainsPriorValuesAsStale() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final AtomicBoolean newer = new AtomicBoolean();
        final PerformanceCollector.GenerationSource source = (now, consumer) -> consumer.accept(
            view(1L, true, window(1L, newer.get() ? 100L * MILLISECOND : 50L * MILLISECOND,
                5L * MILLISECOND, 0.1)));
        final PerformanceCollector collector = collector(provider, source);
        collector.collect(SECOND, 1_000L, 0L);
        final WindowMetrics previous = provider.snapshot().window(MetricWindow.FIVE_SECONDS);

        newer.set(true);
        collector.collect(3L * SECOND, 3_000L, SECOND + 1L);
        final PerformanceSnapshot stale = provider.snapshot();

        assertSame(previous, stale.window(MetricWindow.FIVE_SECONDS));
        assertEquals(MetricState.STALE, stale.freshness().state());
        assertEquals(2_000L, stale.freshness().ageMillis());
        assertEquals(1_000L, stale.freshness().collectorLatenessMillis());
    }

    @Test
    void overPeriodScanRetainsPriorValuesAndNanoWrapProducesFiniteDuration() {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final AtomicBoolean newer = new AtomicBoolean();
        final long scanStart = Long.MAX_VALUE - 100L;
        final long scanEnd = scanStart + SECOND + 1L;
        final long[] clockValues = {10L, 20L, scanStart, scanEnd};
        final AtomicInteger clockIndex = new AtomicInteger();
        final PerformanceCollector collector = new PerformanceCollector(provider,
            (now, consumer) -> consumer.accept(view(1L, true,
                window(1L, newer.get() ? 100L * MILLISECOND : 50L * MILLISECOND,
                    5L * MILLISECOND, 0.1))),
            PerformanceCollectorTest::emptyGlobal, PerformanceCollectorTest::runtime,
            () -> clockValues[clockIndex.getAndIncrement()], System::currentTimeMillis, () -> 20.0);
        collector.collect(SECOND, 1_000L, 0L);
        final WindowMetrics previous = provider.snapshot().window(MetricWindow.FIVE_SECONDS);

        newer.set(true);
        collector.collect(3L * SECOND, 3_000L, 0L);
        final PerformanceSnapshot stale = provider.snapshot();

        assertSame(previous, stale.window(MetricWindow.FIVE_SECONDS));
        assertEquals(MetricState.STALE, stale.freshness().state());
        assertEquals(3_000L, stale.freshness().ageMillis());
        assertEquals(SECOND + 1L, stale.freshness().scanDurationNanos());
    }

    @Test
    void nanoDeadlineMathCrossesSignedWrapWithoutLongWaitOrCatchUp() {
        final LongSupplierClock clock = new LongSupplierClock(
            Long.MAX_VALUE - 100L, Long.MAX_VALUE - 110L, Long.MIN_VALUE + 100L);
        final long deadline = clock.next();

        assertEquals(10L, PerformanceCollector.delayUntil(deadline, clock.next()));
        final long next = PerformanceCollector.advanceDeadline(deadline, clock.next());
        assertEquals(SECOND - 201L, PerformanceCollector.delayUntil(next, Long.MIN_VALUE + 100L));
    }

    @Test
    void closePreventsLaterPublicationAndWorkerIsDaemonAndTerminatesBoundedly() throws Exception {
        final SourbyMetricsProvider provider = new SourbyMetricsProvider();
        final PerformanceCollector collector = collector(provider,
            source(view(1L, true, window(1L, 50L * MILLISECOND, 5L * MILLISECOND, 0.1))));

        collector.start();
        assertTrue(collector.worker().isDaemon());
        collector.close();
        final PerformanceSnapshot closed = provider.snapshot();
        collector.collect(10L * SECOND, 10_000L, 0L);

        assertFalse(collector.worker().isAlive());
        assertSame(closed, provider.snapshot());
    }

    private static PerformanceCollector collector(final SourbyMetricsProvider provider,
                                                  final PerformanceCollector.GenerationSource source) {
        return new PerformanceCollector(provider, source, PerformanceCollectorTest::emptyGlobal,
            PerformanceCollectorTest::runtime, System::nanoTime, System::currentTimeMillis, () -> 20.0);
    }

    private static PerformanceCollector.GenerationSource source(final RegionMetricsRegistry.GenerationView... views) {
        final List<RegionMetricsRegistry.GenerationView> copy = List.of(views);
        return (now, consumer) -> copy.forEach(consumer);
    }

    private static RegionMetricsRegistry.GenerationView view(final long id, final boolean active,
                                                             final RegionTickMetrics.WindowSnapshot window) {
        return new RegionMetricsRegistry.GenerationView(id, 1L, id, active, null,
            snapshot(window, RegionTickMetrics.INACTIVE));
    }

    private static RegionTickMetrics.Snapshot snapshot(final RegionTickMetrics.WindowSnapshot window,
                                                       final long activeTickStart) {
        return new RegionTickMetrics.Snapshot(1L, SECOND, activeTickStart,
            window, window, window, window, window, window);
    }

    private static RegionTickMetrics.WindowSnapshot window(final long count, final long interval,
                                                           final long execution, final double utilisation) {
        final long average = count == 0L ? 0L : execution / count;
        return new RegionTickMetrics.WindowSnapshot(count, interval, execution, average, average, average,
            execution / 2L, count == 0L ? Double.NaN : count * 1.0E9 / interval,
            count == 0L ? Double.NaN : (double)execution / count * 1.0E-6, utilisation,
            average * 1.0E-6, average * 1.0E-6, true, false);
    }

    private static ImmutableRuntimeMetrics runtime() {
        return new ImmutableRuntimeMetrics(100L, 200L, 25.0, 1.0, 2.0, 3.0);
    }

    private static RegionTickMetrics.Snapshot emptyGlobal(final long nowNanos) {
        return snapshot(window(0L, 0L, 0L, 0.0), RegionTickMetrics.INACTIVE);
    }

    private static RegionTickMetrics metricsWithDurations(final int count, final long duration) {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        long previous = TimeUtil.DEADLINE_NOT_SET;
        for (int i = 0; i < count; ++i) {
            final long start = (long)i * 10L * MILLISECOND;
            metrics.tickCompleted(new TickTime(previous, start, start, 0L, start + duration,
                0L, 0L, 0L, false), 10L * MILLISECOND);
            previous = start;
        }
        return metrics;
    }

    private static final class LongSupplierClock {
        private final long[] values;
        private int index;

        private LongSupplierClock(final long... values) {
            this.values = values;
        }

        private long next() {
            return this.values[this.index++];
        }
    }
}
