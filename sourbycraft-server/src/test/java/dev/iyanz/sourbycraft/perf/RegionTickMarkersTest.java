package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.spottedleaf.common.time.TickTime;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The two lock-free tick markers the stall recorder reads, and the registry walk that finds them. */
class RegionTickMarkersTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1L);

    private static TickTime tick(final long start, final long duration) {
        return new TickTime(start - 50 * MS, start, start, 0L, start + duration, 0L, 0L, 0L, false);
    }

    @Test
    void markersStartEmptyFollowATickAndSurviveSealing() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        assertEquals(0L, metrics.lastCompletedEndNanos());
        assertEquals(RegionTickMetrics.INACTIVE, metrics.activeTickStartNanos());

        metrics.tickStarted(1_000L * MS);
        assertEquals(1_000L * MS, metrics.activeTickStartNanos());
        assertEquals(0L, metrics.lastCompletedEndNanos(), "a tick in progress has not completed");

        metrics.tickCompleted(tick(1_000L * MS, 10L * MS), 50L * MS);
        assertEquals(1_010L * MS, metrics.lastCompletedEndNanos());
        assertEquals(RegionTickMetrics.INACTIVE, metrics.activeTickStartNanos());

        metrics.tickStarted(1_050L * MS);
        metrics.seal(1_060L * MS);
        assertEquals(RegionTickMetrics.INACTIVE, metrics.activeTickStartNanos(), "a sealed generation reports no tick in progress");
        assertEquals(1_010L * MS, metrics.lastCompletedEndNanos());
    }

    @Test
    void markersNeverTakeTheMetricsMonitor() throws Exception {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickStarted(5L * MS);
        final long[] seen = new long[2];
        final Thread reader;
        synchronized (metrics) { // a stuck tick thread holding the monitor must not block the watchdog
            reader = new Thread(() -> {
                seen[0] = metrics.activeTickStartNanos();
                seen[1] = metrics.lastCompletedEndNanos();
            });
            reader.start();
            reader.join(10_000L);
            assertFalse(reader.isAlive(), "reader blocked on the metrics monitor");
        }
        assertEquals(5L * MS, seen[0]);
        assertEquals(0L, seen[1]);
        assertFalse(Modifier.isSynchronized(RegionTickMetrics.class.getMethod("lastCompletedEndNanos").getModifiers()));
        assertFalse(Modifier.isSynchronized(RegionTickMetrics.class.getMethod("activeTickStartNanos").getModifiers()));
    }

    @Test
    void forEachActiveSkipsRetiredGenerationsAndNeverBlocksOnAMetricsMonitor() throws Exception {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        final RegionTickMetricsHolder live = new RegionTickMetricsHolder();
        final RegionTickMetricsHolder retiring = new RegionTickMetricsHolder();
        registry.activate(world, 1L, live, 0L);
        final long retired = registry.activate(world, 2L, retiring, 0L);
        registry.retire(retired, RegionMetricsRegistry.RetirementReason.DESTROYED, 10L);
        live.tickStarted(7L * MS);

        final List<RegionMetricsRegistry.ActiveGeneration> seen = new ArrayList<>();
        final Thread reader;
        synchronized (live.current()) {
            reader = new Thread(() -> registry.forEachActive(seen::add));
            reader.start();
            reader.join(10_000L);
            assertFalse(reader.isAlive(), "forEachActive blocked on a region's metrics monitor");
        }
        assertEquals(1, seen.size());
        assertEquals(1L, seen.get(0).regionId());
        assertEquals(world, seen.get(0).worldId());
        assertTrue(seen.get(0).metrics() == live.current());
        assertEquals(7L * MS, seen.get(0).metrics().activeTickStartNanos());
    }
}
