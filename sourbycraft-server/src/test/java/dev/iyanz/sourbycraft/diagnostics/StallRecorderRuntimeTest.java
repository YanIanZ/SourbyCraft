package dev.iyanz.sourbycraft.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.execution.region.RegionBackend;
import dev.iyanz.sourbycraft.perf.RegionMetricsRegistry;
import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the recorder reads from the engine, through the {@link RegionBackend} contract and the registry. */
class StallRecorderRuntimeTest {

    private record FakeBackend(RegionTickMetrics global) implements RegionBackend {
        @Override
        public RegionTickMetrics.Snapshot globalTickMetrics(final long nowNanos) {
            throw new AssertionError("the recorder must not use the locking snapshot path");
        }

        @Override
        public RegionTickMetrics globalTickMetricsLive() {
            return this.global;
        }

        @Override
        public String globalTickFrameMarker() {
            return "G";
        }

        @Override
        public String regionTickFrameMarker() {
            return "R";
        }

        @Override
        public double tickRateHz() {
            return 20.0;
        }
    }

    @Test
    void readsTheGlobalTickThenEveryLiveRegionFromTheirVolatileMarkers() {
        final RegionTickMetrics global = new RegionTickMetrics();
        global.tickStarted(500L);
        final RegionTickMetrics region = new RegionTickMetrics();
        region.tickStarted(800L);
        region.tickCompleted(new ca.spottedleaf.common.time.TickTime(0L, 800L, 800L, 0L, 950L, 0L, 0L, 0L, false), 50_000_000L);

        final List<StallRecorder.Reading> readings = new ArrayList<>();
        StallRecorderRuntime.liveSources(new FakeBackend(global),
            walk -> walk.accept(new RegionMetricsRegistry.ActiveGeneration(4L, 9L, region))).read(readings::add);

        assertEquals(2, readings.size());
        assertEquals("global tick", readings.get(0).label());
        assertTrue(readings.get(0).global());
        assertEquals(500L, readings.get(0).activeTickStartNanos());
        assertEquals(0L, readings.get(0).lastTickEndNanos());
        assertFalse(readings.get(1).global());
        assertEquals("region #9 (world id 4)", readings.get(1).label());
        assertEquals(950L, readings.get(1).lastTickEndNanos());
        assertEquals(StallRecorder.NO_ACTIVE_TICK, readings.get(1).activeTickStartNanos(),
            "the engine's 'no tick' marker is the recorder's");
    }

    @Test
    void aMissingGlobalHandleIsSkipped() {
        final List<StallRecorder.Reading> readings = new ArrayList<>();
        StallRecorderRuntime.liveSources(new FakeBackend(null), walk -> { }).read(readings::add);
        assertTrue(readings.isEmpty());
        assertNull(new FakeBackend(null).global());
    }
}
