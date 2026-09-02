package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.RegionTickMetrics;
import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTickMetricsTest {

    private static final long MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);
    private static final long TARGET_20_TPS = 50L * MILLISECOND;

    @Test
    void steadyTwentyTpsUsesIntervalsForTpsAndExecutionForMspt() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        appendTicks(metrics, 100, 0L, TARGET_20_TPS, 10L * MILLISECOND);
        metrics.tickCompleted(tick(99L * TARGET_20_TPS, 5L * SECOND, 10L * MILLISECOND), TARGET_20_TPS);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(5L * SECOND + 10L * MILLISECOND).fiveSeconds();

        assertWindow(window, 101L, 5_050L * MILLISECOND, 1_010L * MILLISECOND);
        assertEquals(20.0, window.tps(), 1.0E-9);
        assertEquals(10.0, window.mspt(), 1.0E-9);
        assertEquals(0.2, window.utilisation(), 1.0E-9);
        assertEquals(10L * MILLISECOND, window.minimumNanos());
        assertEquals(10L * MILLISECOND, window.medianNanos());
        assertEquals(10L * MILLISECOND, window.maximumNanos());
        assertTrue(window.approximate());
        assertFalse(window.truncated());
    }

    @Test
    void mixedDurationsAndCpuAccountingPreserveOrderedStatistics() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(cpuTick(TimeUtil.DEADLINE_NOT_SET, 0L, 49L * MILLISECOND, 40L * MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(cpuTick(0L, SECOND, 51L * MILLISECOND, 30L * MILLISECOND), TARGET_20_TPS);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(SECOND + 51L * MILLISECOND).fiveSeconds();

        assertWindow(window, 2L, 1_050L * MILLISECOND, 100L * MILLISECOND);
        assertEquals(50.0, window.mspt(), 1.0E-9);
        assertEquals(30L * MILLISECOND, window.missingCpuNanos());
        assertEquals(49L * MILLISECOND, window.minimumNanos());
        assertTrue(window.minimumNanos() <= window.medianNanos());
        assertTrue(window.medianNanos() <= window.maximumNanos());
        assertEquals(51L * MILLISECOND, window.maximumNanos());
    }

    @Test
    void multiSecondTickClipsWindowUtilisation() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 8L * SECOND), TARGET_20_TPS);

        final RegionTickMetrics.WindowSnapshot fiveSeconds = metrics.snapshot(8L * SECOND).fiveSeconds();

        assertEquals(1L, fiveSeconds.sampleCount());
        assertEquals(1.0, fiveSeconds.utilisation(), 1.0E-9);
        assertEquals(8_000.0, fiveSeconds.mspt(), 1.0E-9);
    }

    @Test
    void hourGapExpiresAllOldRawAndBucketStateWithoutCatchUp() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, MILLISECOND), TARGET_20_TPS);
        final long hour = TimeUnit.HOURS.toNanos(1L);
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, hour, MILLISECOND), TARGET_20_TPS);

        final RegionTickMetrics.Snapshot snapshot = metrics.snapshot(hour + MILLISECOND);

        assertEquals(1L, snapshot.fiveSeconds().sampleCount());
        assertEquals(1L, snapshot.oneMinute().sampleCount());
        assertEquals(1L, snapshot.fifteenMinutes().sampleCount());
    }

    @Test
    void targetIntervalIsRetainedPerSampleWhenRateChanges() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, MILLISECOND), 50L * MILLISECOND);
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, SECOND, MILLISECOND), 25L * MILLISECOND);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(SECOND + MILLISECOND).fiveSeconds();

        assertEquals(2L, window.sampleCount());
        assertEquals(75L * MILLISECOND, window.intervalNanos());
        assertEquals(2.0 * 1_000_000_000.0 / (75L * MILLISECOND), window.tps(), 1.0E-9);
    }

    @Test
    void shortWindowIncludesExactBoundaryAndExcludesOneNanosecondOlderSample() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 1L), TARGET_20_TPS);
        metrics.tickCompleted(tick(0L, 1L, 1L), TARGET_20_TPS);

        assertEquals(2, metrics.report(5L * SECOND, null, 5L * SECOND + 1L, TARGET_20_TPS).collectedTicks());
        assertEquals(1, metrics.report(5L * SECOND, null, 5L * SECOND + 2L, TARGET_20_TPS).collectedTicks());
    }

    @Test
    void longWindowsUseDeclaredOneAndFiveSecondAlignment() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 999L * MILLISECOND, MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(tick(999L * MILLISECOND, SECOND, MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(tick(SECOND, 61L * SECOND, 0L), TARGET_20_TPS);

        final RegionTickMetrics.Snapshot atBoundary = metrics.snapshot(61L * SECOND);
        metrics.tickCompleted(tick(61L * SECOND, 62L * SECOND + 1L, 0L), TARGET_20_TPS);
        final RegionTickMetrics.Snapshot pastBoundary = metrics.snapshot(62L * SECOND + 1L);

        assertEquals(3L, atBoundary.oneMinute().sampleCount());
        assertEquals(2L, pastBoundary.oneMinute().sampleCount());
        assertTrue(atBoundary.oneMinute().approximate());
        assertTrue(atBoundary.fiveMinutes().approximate());
        assertTrue(atBoundary.fifteenMinutes().approximate());
    }

    @Test
    void arithmeticOverflowSaturatesAndMarksWindowTruncated() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(new TickTime(TimeUtil.DEADLINE_NOT_SET, 0L, 0L, 0L, 1L, 0L,
            Long.MAX_VALUE, 0L, false), TARGET_20_TPS);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(1L).fiveSeconds();

        assertEquals(Long.MAX_VALUE, window.executionNanos());
        assertTrue(window.truncated());
        assertTrue(Double.isNaN(window.mspt()));
    }

    @Test
    void rawRingHardCapIsExplicitlyReportedAsTruncation() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        appendTicks(metrics, 1_000, 0L, MILLISECOND, 1L);
        metrics.tickCompleted(tick(999L * MILLISECOND, SECOND, 1L), MILLISECOND);

        final RegionTickMetrics.WindowSnapshot window = metrics.snapshot(SECOND + 1L).fifteenSeconds();

        assertTrue(window.sampleCount() < 1_001L);
        assertTrue(window.truncated());
    }

    @Test
    void compatibilityReportSortsExactShortWindowValues() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 3L * MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(tick(0L, TARGET_20_TPS, MILLISECOND), TARGET_20_TPS);
        metrics.tickCompleted(tick(TARGET_20_TPS, 2L * TARGET_20_TPS, 2L * MILLISECOND), TARGET_20_TPS);

        final TickData.TickReportData report = metrics.report(5L * SECOND, null, 103L * MILLISECOND, TARGET_20_TPS);

        assertEquals(3, report.collectedTicks());
        assertArrayEquals(new long[] {MILLISECOND, 2L * MILLISECOND, 3L * MILLISECOND}, report.timePerTickData().rawData());
        assertEquals(2.0, metrics.mspt(5L * SECOND, null, TARGET_20_TPS).avg(), 1.0E-9);
        assertEquals(20.0, metrics.tps(5L * SECOND, null, TARGET_20_TPS), 1.0E-9);
    }

    private static void assertWindow(final RegionTickMetrics.WindowSnapshot window, final long count,
                                     final long intervalNanos, final long executionNanos) {
        assertEquals(count, window.sampleCount());
        assertEquals(intervalNanos, window.intervalNanos());
        assertEquals(executionNanos, window.executionNanos());
        assertEquals(count * 1_000_000_000.0 / intervalNanos, window.tps(), 1.0E-9);
        assertEquals((double)executionNanos / count / 1_000_000.0, window.mspt(), 1.0E-9);
    }

    private static void appendTicks(final RegionTickMetrics metrics, final int count, final long firstStart,
                                    final long interval, final long duration) {
        long previous = TimeUtil.DEADLINE_NOT_SET;
        long start = firstStart;
        for (int i = 0; i < count; ++i) {
            metrics.tickCompleted(tick(previous, start, duration), interval);
            previous = start;
            start += interval;
        }
    }

    private static TickTime tick(final long previousStart, final long start, final long duration) {
        return new TickTime(previousStart, start, start, 0L, start + duration, 0L, 0L, 0L, false);
    }

    private static TickTime cpuTick(final long previousStart, final long start, final long duration, final long cpuDuration) {
        return new TickTime(previousStart, start, start, 0L, start + duration, cpuDuration, 0L, 0L, true);
    }
}
