package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.RegionTickMetrics;
import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickDataBinaryCompatibilityTest {

    private static final long MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final long TARGET_INTERVAL = 50L * MILLISECOND;

    @Test
    void regionHandleKeepsTickDataFieldDescriptors() throws Exception {
        for (final String name : List.of("tickTimes5s", "tickTimes15s", "tickTimes1m", "tickTimes5m", "tickTimes15m")) {
            assertEquals(TickData.class, TickRegionScheduler.RegionScheduleHandle.class.getField(name).getType());
        }
        assertEquals(1L, List.of(TickRegionScheduler.RegionScheduleHandle.class.getDeclaredFields()).stream()
            .filter(field -> field.getType() == RegionTickMetricsHolder.class)
            .count());
        assertTrue(Modifier.isFinal(TickData.class.getModifiers()));
    }

    @Test
    void oneOwnerSampleAppearsExactlyOnceInEveryCompatibilityView() {
        final RegionTickMetrics owner = new RegionTickMetrics();
        final List<TickData> views = views(owner);

        owner.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 3L * MILLISECOND), TARGET_INTERVAL);

        for (final TickData view : views) {
            final TickData.TickReportData report = view.generateTickReport(null, 3L * MILLISECOND, TARGET_INTERVAL);
            assertNotNull(report);
            assertEquals(1, report.collectedTicks());
            assertEquals(3L * MILLISECOND, report.totalTimeTicking());
        }
    }

    @Test
    void shortCompatibilityViewsReturnExactSortedRawArrays() {
        final RegionTickMetrics owner = new RegionTickMetrics();
        owner.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 3L * MILLISECOND), TARGET_INTERVAL);
        owner.tickCompleted(tick(0L, TARGET_INTERVAL, MILLISECOND), TARGET_INTERVAL);
        owner.tickCompleted(tick(TARGET_INTERVAL, 2L * TARGET_INTERVAL, 2L * MILLISECOND), TARGET_INTERVAL);

        for (final long window : new long[] {5L, 15L}) {
            final TickData.TickReportData report = new TickData(owner, TimeUnit.SECONDS.toNanos(window))
                .generateTickReport(null, 102L * MILLISECOND, TARGET_INTERVAL);
            assertArrayEquals(new long[] {TARGET_INTERVAL, TARGET_INTERVAL, TARGET_INTERVAL}, report.tpsData().rawData());
            assertArrayEquals(new long[] {MILLISECOND, 2L * MILLISECOND, 3L * MILLISECOND}, report.timePerTickData().rawData());
            assertEquals(2L * MILLISECOND, report.timePerTickData().segmentAll().average());
        }
    }

    @Test
    void truncatedShortCompatibilityViewsReturnNullInsteadOfPartialRawArrays() {
        final RegionTickMetrics owner = new RegionTickMetrics();
        long previous = TimeUtil.DEADLINE_NOT_SET;
        for (int i = 0; i < 500; ++i) {
            final long start = i * MILLISECOND;
            owner.tickCompleted(tick(previous, start, 100_000L), MILLISECOND);
            previous = start;
        }
        owner.tickCompleted(tick(previous, TimeUnit.SECONDS.toNanos(1L), 100_000L), MILLISECOND);

        assertTrue(owner.snapshot(TimeUnit.SECONDS.toNanos(1L) + 100_000L).fiveSeconds().truncated());
        assertTrue(owner.snapshot(TimeUnit.SECONDS.toNanos(1L) + 100_000L).fifteenSeconds().truncated());
        for (final long window : new long[] {5L, 15L}) {
            final TickData view = new TickData(owner, TimeUnit.SECONDS.toNanos(window));
            assertNull(view.generateTickReport(null, TimeUnit.SECONDS.toNanos(1L) + 100_000L, MILLISECOND));
            assertNull(view.getMSPTData(null, MILLISECOND));
            assertNull(view.getTPSAverage(null, MILLISECOND));
        }
    }

    @Test
    void directSharedViewInsertionMatchesStandaloneFirstAndContinuingTickTps() {
        final RegionTickMetrics owner = new RegionTickMetrics();
        final TickData shared = new TickData(owner, TimeUnit.SECONDS.toNanos(5L));
        final TickData standalone = new TickData(TimeUnit.SECONDS.toNanos(5L));
        final TickTime first = tick(TimeUtil.DEADLINE_NOT_SET, 0L, 2L * MILLISECOND);
        shared.addDataFrom(first);
        standalone.addDataFrom(first);

        assertEquals(20.0, shared.getTPSAverage(null, TARGET_INTERVAL), 0.0);
        assertEquals(standalone.getTPSAverage(null, TARGET_INTERVAL), shared.getTPSAverage(null, TARGET_INTERVAL));
        assertArrayEquals(standalone.generateTickReport(null, 2L * MILLISECOND, TARGET_INTERVAL).tpsData().rawData(),
            shared.generateTickReport(null, 2L * MILLISECOND, TARGET_INTERVAL).tpsData().rawData());

        final TickTime second = tick(0L, TARGET_INTERVAL, 3L * MILLISECOND);
        shared.addDataFrom(second);
        standalone.addDataFrom(second);

        assertEquals(20.0, shared.getTPSAverage(null, TARGET_INTERVAL), 0.0);
        assertEquals(standalone.getTPSAverage(null, TARGET_INTERVAL), shared.getTPSAverage(null, TARGET_INTERVAL));
        assertArrayEquals(standalone.generateTickReport(null, 53L * MILLISECOND, TARGET_INTERVAL).tpsData().rawData(),
            shared.generateTickReport(null, 53L * MILLISECOND, TARGET_INTERVAL).tpsData().rawData());
    }

    @Test
    void directSharedFirstIntervalIsResolvedInsideLongBucketReports() {
        final RegionTickMetrics owner = new RegionTickMetrics();
        final TickData shared = new TickData(owner, TimeUnit.MINUTES.toNanos(1L));
        final TickData standalone = new TickData(TimeUnit.MINUTES.toNanos(1L));
        long previous = TimeUtil.DEADLINE_NOT_SET;
        for (int i = 0; i < 500; ++i) {
            final long start = i * MILLISECOND;
            final TickTime sample = tick(previous, start, 100_000L);
            shared.addDataFrom(sample);
            standalone.addDataFrom(sample);
            previous = start;
        }
        final long now = 499L * MILLISECOND + 100_000L;
        final TickData.TickReportData expected = standalone.generateTickReport(null, now, TARGET_INTERVAL);
        final TickData.TickReportData actual = shared.generateTickReport(null, now, TARGET_INTERVAL);

        assertNotNull(actual);
        assertEquals(500, actual.collectedTicks());
        assertEquals(expected.tpsData().segmentAll().average(), actual.tpsData().segmentAll().average(), 1.0E-9);
        assertEquals(expected.tpsData().segmentAll().average(), shared.getTPSAverage(null, TARGET_INTERVAL), 1.0E-9);
        assertEquals(0, actual.tpsData().rawData().length);
    }

    @Test
    void mixedTargetlessSamplesAcrossLongBucketTiersMatchStandaloneCountsAndTps() {
        for (final long windowMinutes : new long[] {1L, 5L, 15L}) {
            final long window = TimeUnit.MINUTES.toNanos(windowMinutes);
            final RegionTickMetrics owner = new RegionTickMetrics();
            final TickData shared = new TickData(owner, window);
            final TickData standalone = new TickData(window);
            final long olderOffset = TimeUnit.SECONDS.toNanos(windowMinutes == 1L ? 40L : 70L);
            final TickTime[] samples = {
                tick(TimeUtil.DEADLINE_NOT_SET, 0L, 20L * MILLISECOND),
                tick(TimeUtil.DEADLINE_NOT_SET, window - olderOffset, 80L * MILLISECOND),
                tick(TimeUtil.DEADLINE_NOT_SET, window - TimeUnit.SECONDS.toNanos(30L), 40L * MILLISECOND),
                tick(TimeUtil.DEADLINE_NOT_SET, window - TimeUnit.SECONDS.toNanos(1L), 120L * MILLISECOND)
            };
            for (final TickTime sample : samples) {
                shared.addDataFrom(sample);
                standalone.addDataFrom(sample);
            }
            final long now = samples[samples.length - 1].tickEnd();
            final TickData.TickReportData expected = standalone.generateTickReport(null, now, TARGET_INTERVAL);
            final TickData.TickReportData actual = shared.generateTickReport(null, now, TARGET_INTERVAL);

            assertNotNull(actual);
            assertEquals(expected.collectedTicks(), actual.collectedTicks());
            assertEquals(expected.tpsData().segmentAll().average(), actual.tpsData().segmentAll().average(), 0.0);
            assertEquals(expected.tpsData().segmentAll().average(), shared.getTPSAverage(null, TARGET_INTERVAL), 0.0);
        }
    }

    @Test
    void unresolvedSampleRingOverflowMakesLongCompatibilityViewsUnavailable() {
        final RegionTickMetrics owner = new RegionTickMetrics();
        final TickData shared = new TickData(owner, TimeUnit.MINUTES.toNanos(5L));
        final TickData standalone = new TickData(TimeUnit.MINUTES.toNanos(5L));
        for (int i = 0; i < 1_000; ++i) {
            final TickTime sample = tick(TimeUtil.DEADLINE_NOT_SET, i * MILLISECOND, MILLISECOND);
            shared.addDataFrom(sample);
            standalone.addDataFrom(sample);
        }
        final long now = TimeUnit.SECONDS.toNanos(1L);
        final TickData.TickReportData standaloneReport = standalone.generateTickReport(null, now, TARGET_INTERVAL);
        final Double standaloneTps = standalone.getTPSAverage(null, TARGET_INTERVAL);

        assertNotNull(standaloneReport);
        assertEquals(1_000, standaloneReport.collectedTicks());
        assertNotNull(standaloneTps);
        assertTrue(Double.isFinite(standaloneTps));
        assertNull(shared.generateTickReport(null, now, TARGET_INTERVAL));
        assertNull(shared.getTPSAverage(null, TARGET_INTERVAL));
    }

    @Test
    void targetlessCompatibilityRingIsExactThroughThirtyTwoAndUnavailableOnOverflow() {
        final RegionTickMetrics owner = new RegionTickMetrics();
        final TickData shared = new TickData(owner, TimeUnit.MINUTES.toNanos(5L));
        final TickData standalone = new TickData(TimeUnit.MINUTES.toNanos(5L));
        for (int i = 0; i < 32; ++i) {
            final TickTime sample = tick(TimeUtil.DEADLINE_NOT_SET, i * MILLISECOND, MILLISECOND);
            shared.addDataFrom(sample);
            standalone.addDataFrom(sample);
        }
        final long now = 32L * MILLISECOND;
        assertEquals(standalone.getTPSAverage(null, TARGET_INTERVAL),
            shared.getTPSAverage(null, TARGET_INTERVAL), 0.0);

        final TickTime overflow = tick(TimeUtil.DEADLINE_NOT_SET, now, MILLISECOND);
        shared.addDataFrom(overflow);
        assertNull(shared.getTPSAverage(null, TARGET_INTERVAL));
    }

    @Test
    void bucketedLongViewsKeepAggregateCountsAndAveragesWithoutFabricatedRawSamples() {
        final RegionTickMetrics owner = new RegionTickMetrics();
        final long interval = MILLISECOND;
        final long duration = 100_000L;
        long previous = TimeUtil.DEADLINE_NOT_SET;
        for (int i = 0; i < 500; ++i) {
            final long start = i * interval;
            owner.tickCompleted(tick(previous, start, duration), interval);
            previous = start;
        }
        final long now = 499L * interval + duration;

        for (final long window : new long[] {1L, 5L, 15L}) {
            final TickData.TickReportData report = new TickData(owner, TimeUnit.MINUTES.toNanos(window))
                .generateTickReport(null, now, interval);
            assertNotNull(report);
            assertEquals(500, report.collectedTicks());
            assertEquals(500L * duration, report.totalTimeTicking());
            assertEquals(500, report.tpsData().segmentAll().count());
            assertEquals(1_000.0, report.tpsData().segmentAll().average(), 1.0E-9);
            assertEquals(500, report.timePerTickData().segmentAll().count());
            assertEquals((double)duration, report.timePerTickData().segmentAll().average(), 0.0);
            assertEquals(0, report.tpsData().rawData().length);
            assertEquals(0, report.timePerTickData().rawData().length);
            assertEquals(0, report.missingCPUTimeData().rawData().length);
        }
    }

    @Test
    void nonuniformLongViewsExposeOnlyTheAggregateSegment() {
        final RegionTickMetrics owner = new RegionTickMetrics();
        long previous = TimeUtil.DEADLINE_NOT_SET;
        long totalExecution = 0L;
        for (int i = 0; i < 500; ++i) {
            final long start = i * MILLISECOND;
            final long duration = (i & 1) == 0 ? 100_000L : 900_000L;
            owner.tickCompleted(tick(previous, start, duration), MILLISECOND);
            previous = start;
            totalExecution += duration;
        }

        final TickData.TickReportData report = new TickData(owner, TimeUnit.MINUTES.toNanos(5L))
            .generateTickReport(null, 499L * MILLISECOND + 900_000L, MILLISECOND);
        assertNotNull(report);
        assertEquals((double)totalExecution / 500.0, report.timePerTickData().segmentAll().average(), 0.0);
        assertUnavailableTail(report.tpsData(), 495, 475, 25, 5);
        assertUnavailableTail(report.timePerTickData(), 495, 475, 25, 5);
        assertUnavailableTail(report.missingCPUTimeData(), 495, 475, 25, 5);
    }

    @Test
    void sharedViewsPreserveEmptyAndInProgressBehavior() {
        final TickData view = new TickData(new RegionTickMetrics(), TimeUnit.MINUTES.toNanos(1L));

        assertNull(view.getTPSAverage(null, TARGET_INTERVAL));
        assertNull(view.getMSPTData(null, TARGET_INTERVAL));
        assertNull(view.generateTickReport(null, 0L, TARGET_INTERVAL));

        final TickData.TickReportData report = view.generateTickReport(
            tick(TimeUtil.DEADLINE_NOT_SET, 10L * MILLISECOND, 5L * MILLISECOND),
            15L * MILLISECOND,
            TARGET_INTERVAL
        );
        assertNotNull(report);
        assertEquals(1, report.collectedTicks());
    }

    @Test
    void standaloneConstructorRetainsIndependentCollectorBehavior() {
        final TickData standalone = new TickData(TimeUnit.MINUTES.toNanos(1L));
        standalone.addDataFrom(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 2L * MILLISECOND));

        final TickData.TickReportData report = standalone.generateTickReport(null, 2L * MILLISECOND, TARGET_INTERVAL);
        assertNotNull(report);
        assertEquals(1, report.collectedTicks());
        assertArrayEquals(new long[] {2L * MILLISECOND}, report.timePerTickData().rawData());
    }

    private static List<TickData> views(final RegionTickMetrics owner) {
        return List.of(
            new TickData(owner, TimeUnit.SECONDS.toNanos(5L)),
            new TickData(owner, TimeUnit.SECONDS.toNanos(15L)),
            new TickData(owner, TimeUnit.MINUTES.toNanos(1L)),
            new TickData(owner, TimeUnit.MINUTES.toNanos(5L)),
            new TickData(owner, TimeUnit.MINUTES.toNanos(15L))
        );
    }

    private static void assertUnavailableTail(final TickData.SegmentedAverage data, final int best99,
                                              final int best95, final int worst5, final int worst1) {
        assertUnavailableSegment(data.segment99PercentBest(), best99);
        assertUnavailableSegment(data.segment95PercentBest(), best95);
        assertUnavailableSegment(data.segment5PercentWorst(), worst5);
        assertUnavailableSegment(data.segment1PercentWorst(), worst1);
        assertEquals(0, data.rawData().length);
    }

    private static void assertUnavailableSegment(final TickData.SegmentData segment, final int count) {
        assertEquals(count, segment.count());
        assertTrue(Double.isNaN(segment.average()));
        assertTrue(Double.isNaN(segment.median()));
        assertTrue(Double.isNaN(segment.least()));
        assertTrue(Double.isNaN(segment.greatest()));
    }

    private static TickTime tick(final long previousStart, final long start, final long duration) {
        return new TickTime(previousStart, start, start, 0L, start + duration, 0L, 0L, 0L, false);
    }
}
