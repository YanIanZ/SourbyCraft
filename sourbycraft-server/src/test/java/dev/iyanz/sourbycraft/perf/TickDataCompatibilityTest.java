package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TickDataCompatibilityTest {

    private static final long TICK_INTERVAL = TimeUnit.MILLISECONDS.toNanos(50L);

    @Test
    void TestV1_PercentileSegmentsMatchPopulation() {
        final TickData.TickReportData report = reportForOrderedDurations(100);

        assertEquals(5, report.timePerTickData().segment5PercentWorst().count());
        assertEquals(1, report.timePerTickData().segment1PercentWorst().count());
        assertEquals(95_000_000.0, report.timePerTickData().rawData()[94]);
    }

    @Test
    void emptyStandaloneCollectorReturnsNullReports() {
        final TickData data = new TickData(TimeUnit.MINUTES.toNanos(1L));

        assertNull(data.getTPSAverage(null, TICK_INTERVAL));
        assertNull(data.getMSPTData(null, TICK_INTERVAL));
        assertNull(data.generateTickReport(null, 0L, TICK_INTERVAL));
    }

    @Test
    void inProgressTickAloneProducesAReport() {
        final TickData data = new TickData(TimeUnit.MINUTES.toNanos(1L));
        final TickTime inProgress = tick(TimeUtil.DEADLINE_NOT_SET, 10_000_000L, 5_000_000L);

        final TickData.TickReportData report = data.generateTickReport(inProgress, 15_000_000L, TICK_INTERVAL);

        assertEquals(1, report.collectedTicks());
        assertArrayEquals(new long[] {5_000_000L}, report.timePerTickData().rawData());
    }

    @Test
    void reportSortsRawDurationsAndKeepsNanosecondUnits() {
        final TickData data = new TickData(TimeUnit.MINUTES.toNanos(1L));
        data.addDataFrom(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 3_000_000L));
        data.addDataFrom(tick(0L, 50_000_000L, 1_000_000L));
        data.addDataFrom(tick(50_000_000L, 100_000_000L, 2_000_000L));

        final TickData.SegmentedAverage durations = data.generateTickReport(null, 102_000_000L, TICK_INTERVAL).timePerTickData();

        assertArrayEquals(new long[] {1_000_000L, 2_000_000L, 3_000_000L}, durations.rawData());
        assertEquals(2_000_000.0, durations.segmentAll().average());
    }

    @Test
    void inverseTpsExtremaFollowFastestAndSlowestIntervals() {
        final TickData data = new TickData(TimeUnit.MINUTES.toNanos(1L));
        data.addDataFrom(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 1_000_000L));
        data.addDataFrom(tick(0L, 40_000_000L, 1_000_000L));
        data.addDataFrom(tick(40_000_000L, 140_000_000L, 1_000_000L));

        final TickData.SegmentData tps = data.generateTickReport(null, 141_000_000L, TICK_INTERVAL).tpsData().segmentAll();

        assertEquals(10.0, tps.least(), 1.0E-9);
        assertEquals(25.0, tps.greatest(), 1.0E-9);
    }

    @Test
    void addTimeExpiryRetainsTickAtInclusiveBoundary() {
        final TickData data = new TickData(100L);
        data.addDataFrom(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 10L));
        data.addDataFrom(tick(0L, 110L, 5L));

        final TickData.TickReportData report = data.generateTickReport(null, 115L, 50L);

        assertEquals(2, report.collectedTicks());
        assertArrayEquals(new long[] {5L, 10L}, report.timePerTickData().rawData());
    }

    @Test
    void mutatingReturnedRawArrayDoesNotChangeRetainedHistory() {
        final TickData data = new TickData(TimeUnit.MINUTES.toNanos(1L));
        data.addDataFrom(tick(TimeUtil.DEADLINE_NOT_SET, 0L, 1_000_000L));
        data.addDataFrom(tick(0L, 50_000_000L, 2_000_000L));
        final TickData.TickReportData first = data.generateTickReport(null, 52_000_000L, TICK_INTERVAL);

        first.timePerTickData().rawData()[0] = Long.MAX_VALUE;

        final TickData.TickReportData second = data.generateTickReport(null, 52_000_000L, TICK_INTERVAL);
        assertArrayEquals(new long[] {1_000_000L, 2_000_000L}, second.timePerTickData().rawData());
    }

    private static TickData.TickReportData reportForOrderedDurations(final int count) {
        final TickData data = new TickData(TimeUnit.MINUTES.toNanos(1L));
        long previousStart = TimeUtil.DEADLINE_NOT_SET;
        long start = 0L;
        for (int i = 1; i <= count; ++i) {
            final long duration = TimeUnit.MILLISECONDS.toNanos(i);
            data.addDataFrom(new TickTime(previousStart, start, start, 0L, start + duration, 0L, 0L, 0L, false));
            previousStart = start;
            start += TICK_INTERVAL;
        }
        return data.generateTickReport(null, start, TICK_INTERVAL);
    }

    private static TickTime tick(final long previousStart, final long start, final long duration) {
        return new TickTime(previousStart, start, start, 0L, start + duration, 0L, 0L, 0L, false);
    }
}
