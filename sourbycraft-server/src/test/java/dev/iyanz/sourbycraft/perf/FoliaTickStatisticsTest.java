package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.TickTime;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import io.canvasmc.canvas.spark.plugin.FoliaTickStatistics;
import io.canvasmc.canvas.threadedregions.profiler.RegionProfiler;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaTickStatisticsTest {

    @AfterEach
    void clearState() {
        provider().overrideSnapshotsForTesting(null);
        RegionProfiler.STATE.set(null);
    }

    @Test
    void normalStatisticsMapEveryWindowToOneCachedAggregateRead() {
        final AtomicInteger reads = new AtomicInteger();
        provider().overrideSnapshotsForTesting(() -> {
            reads.incrementAndGet();
            return snapshot();
        });
        final FoliaTickStatistics statistics = new FoliaTickStatistics();

        assertEquals(5.0, statistics.tps5Sec());
        assertEquals(1, reads.get());
        assertEquals(10.0, statistics.tps10Sec());
        assertEquals(2, reads.get());
        assertEquals(60.0, statistics.tps1Min());
        assertEquals(3, reads.get());
        assertEquals(300.0, statistics.tps5Min());
        assertEquals(4, reads.get());
        assertEquals(900.0, statistics.tps15Min());
        assertEquals(5, reads.get());
    }

    @Test
    void durationStatisticsExposeMatchingWindowDistribution() {
        final AtomicInteger reads = new AtomicInteger();
        provider().overrideSnapshotsForTesting(() -> {
            reads.incrementAndGet();
            return snapshot();
        });
        final FoliaTickStatistics statistics = new FoliaTickStatistics();

        assertTrue(statistics.isDurationSupported());
        assertDuration(statistics.duration10Sec(), 11.0, 12.0, 13.0, 14.0, 15.0);
        assertEquals(1, reads.get());
        assertDuration(statistics.duration1Min(), 61.0, 62.0, 63.0, 64.0, 65.0);
        assertEquals(2, reads.get());
        assertDuration(statistics.duration5Min(), 301.0, 302.0, 303.0, 304.0, 305.0);
        assertEquals(3, reads.get());
    }

    @Test
    void unavailableDurationStatisticsRemainNan() {
        provider().overrideSnapshotsForTesting(FoliaTickStatisticsTest::unavailableSnapshot);

        final DoubleAverageInfo duration = new FoliaTickStatistics().duration10Sec();

        assertTrue(Double.isNaN(duration.mean()));
        assertTrue(Double.isNaN(duration.min()));
        assertTrue(Double.isNaN(duration.max()));
        assertTrue(Double.isNaN(duration.median()));
        assertTrue(Double.isNaN(duration.percentile95th()));
    }

    @Test
    void selectedRegionUsesHandleMetricsWithoutReadingGlobalProvider() {
        final TestRegionScheduleHandle handle = new TestRegionScheduleHandle();
        final long interval = 100_000_000L;
        long previous = Long.MIN_VALUE;
        for (int i = 1; i <= 120; ++i) {
            final long start = i * interval;
            handle.sourbyTickMetrics.tickCompleted(
                new TickTime(previous, start, start, 0L, start + 10_000_000L, 0L, 0L, 0L, false), interval);
            previous = start;
        }
        RegionProfiler.STATE.set(new RegionProfiler.ProfilingState(handle, null, null));
        provider().overrideSnapshotsForTesting(() -> {
            throw new AssertionError("selected-region statistics must not read the global provider");
        });

        assertEquals(10.0, new FoliaTickStatistics().tps10Sec(), 1.0E-9);
    }

    @Test
    void emptySelectedRegionDurationStatisticsRemainNan() {
        final TestRegionScheduleHandle handle = new TestRegionScheduleHandle();
        RegionProfiler.STATE.set(new RegionProfiler.ProfilingState(handle, null, null));
        provider().overrideSnapshotsForTesting(() -> {
            throw new AssertionError("selected-region statistics must not read the global provider");
        });

        final DoubleAverageInfo duration = new FoliaTickStatistics().duration10Sec();

        assertTrue(Double.isNaN(duration.mean()));
        assertTrue(Double.isNaN(duration.min()));
        assertTrue(Double.isNaN(duration.max()));
        assertTrue(Double.isNaN(duration.median()));
        assertTrue(Double.isNaN(duration.percentile95th()));
    }

    @Test
    void compiledAdapterContainsNoGlobalScanOrReportReferences() throws IOException {
        final String resource = "/" + FoliaTickStatistics.class.getName().replace('.', '/') + ".class";
        try (InputStream input = FoliaTickStatistics.class.getResourceAsStream(resource)) {
            assertTrue(input != null);
            for (final PoolEntry entry : ClassFile.of().parse(input.readAllBytes()).constantPool()) {
                if (entry instanceof ClassEntry classEntry) {
                    assertFalse(classEntry.asInternalName().equals("io/papermc/paper/threadedregions/RegionizedServer"));
                }
                if (entry instanceof MemberRefEntry member) {
                    assertFalse(Set.of("computeForAllRegions", "computeForAllRegionsUnsynchronised",
                        "getTPSAverage", "generateTickReport").contains(member.name().stringValue()));
                }
            }
        }
    }

    private static void assertDuration(final DoubleAverageInfo actual, final double mean,
                                       final double minimum, final double maximum,
                                       final double median, final double p95) {
        assertEquals(mean, actual.mean());
        assertEquals(minimum, actual.min());
        assertEquals(maximum, actual.max());
        assertEquals(median, actual.median());
        assertEquals(p95, actual.percentile95th());
    }

    private static ImmutablePerformanceSnapshot snapshot() {
        return snapshot(
            window(5.0, 6.0, 7.0, 8.0, 9.0, 5.0),
            window(10.0, 11.0, 12.0, 13.0, 14.0, 15.0),
            window(60.0, 61.0, 62.0, 63.0, 64.0, 65.0),
            window(300.0, 301.0, 302.0, 303.0, 304.0, 305.0),
            window(900.0, 901.0, 902.0, 903.0, 904.0, 905.0));
    }

    private static ImmutablePerformanceSnapshot unavailableSnapshot() {
        return snapshot(ImmutableWindowMetrics.EMPTY, ImmutableWindowMetrics.EMPTY,
            ImmutableWindowMetrics.EMPTY, ImmutableWindowMetrics.EMPTY, ImmutableWindowMetrics.EMPTY);
    }

    private static ImmutablePerformanceSnapshot snapshot(final ImmutableWindowMetrics fiveSeconds,
                                                         final ImmutableWindowMetrics tenSeconds,
                                                         final ImmutableWindowMetrics oneMinute,
                                                         final ImmutableWindowMetrics fiveMinutes,
                                                         final ImmutableWindowMetrics fifteenMinutes) {
        return new ImmutablePerformanceSnapshot(1L, 1_000L, 20.0, 2, 2,
            new ImmutableFreshness(MetricState.AVAILABLE, 0L, 0L, 1L, ""),
            fiveSeconds, tenSeconds, oneMinute, fiveMinutes, fifteenMinutes,
            ImmutableRuntimeMetrics.UNAVAILABLE, ImmutableGlobalMetrics.EMPTY);
    }

    private static ImmutableWindowMetrics window(final double aggregateTps, final double mean,
                                                  final double minimum, final double maximum,
                                                  final double median, final double p95) {
        return new ImmutableWindowMetrics(5_000L, 100L, false, false,
            aggregateTps - 1.0, aggregateTps - 0.5, aggregateTps, mean, mean - 0.5,
            minimum, maximum, median, p95, p95 + 1.0, 0.5, 0.25, 0.0);
    }

    private static SourbyMetricsProvider provider() {
        return (SourbyMetricsProvider)MetricsRuntime.provider();
    }

    private static final class TestRegionScheduleHandle extends TickRegionScheduler.RegionScheduleHandle {
        private TestRegionScheduleHandle() {
            super(null, Long.MIN_VALUE);
        }

        @Override
        protected boolean tryMarkTicking() {
            return true;
        }

        @Override
        protected boolean markNotTicking() {
            return true;
        }

        @Override
        protected void tickRegion(final long tickCount, final long startTime, final long scheduledEnd) {}

        @Override
        protected void runRegionTasks(final BooleanSupplier canContinue) {}

        @Override
        protected boolean hasIntermediateTasks() {
            return false;
        }
    }
}
