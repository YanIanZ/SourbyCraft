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
            .filter(field -> field.getType() == RegionTickMetrics.class)
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

    private static TickTime tick(final long previousStart, final long start, final long duration) {
        return new TickTime(previousStart, start, start, 0L, start + duration, 0L, 0L, 0L, false);
    }
}
