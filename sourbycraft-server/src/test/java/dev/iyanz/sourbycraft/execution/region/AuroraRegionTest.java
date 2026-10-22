package dev.iyanz.sourbycraft.execution.region;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.execution.ExecutionLane;
import dev.iyanz.sourbycraft.perf.RegionMetricsRegistry;
import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class AuroraRegionTest {

    @Test
    void aRegionCarriesItsGenerationNotJustItsId() {
        // Regions split and merge as players move, so a region id can outlive the region that bore
        // it. Two readings of region 7 are only comparable if the generation matches.
        final List<AuroraRegion> seen = read(
            view(11L, 1L, 7L, true), view(12L, 1L, 7L, false));

        assertEquals(List.of(11L, 12L), seen.stream().map(AuroraRegion::generationId).toList());
        assertTrue(seen.stream().allMatch(region -> region.regionId() == 7L));
    }

    @Test
    void everyRegionRunsOnTheRegionTickLane() {
        // A region is the unit of gameplay ownership, so a region on any other lane would be
        // gameplay mutated by a thread that does not own it. There is deliberately no setter.
        for (final AuroraRegion region : read(view(1L, 1L, 1L, true), view(2L, 1L, 2L, false))) {
            assertEquals(ExecutionLane.REGION_TICK, region.lane());
        }
    }

    @Test
    void retiredRegionsAreReadableButNotCountedAsTicking() {
        // A region that merged away still holds the only record of what those chunks were doing,
        // so it stays readable -- but it is history, not a thread doing work.
        final RegionTopology topology = topology(
            view(1L, 1L, 1L, true), view(2L, 1L, 2L, false), view(3L, 1L, 3L, true));

        assertEquals(2, topology.activeCount(0L));
        assertEquals(3, read(view(1L, 1L, 1L, true), view(2L, 1L, 2L, false), view(3L, 1L, 3L, true)).size());
    }

    @Test
    void anEmptyTopologyHasNoRegions() {
        assertEquals(0, topology().activeCount(0L));
        assertEquals(List.of(), read());
    }

    @Test
    void worldIdentityIsCarriedThrough() {
        final List<AuroraRegion> seen = read(view(1L, 40L, 1L, true), view(2L, 41L, 1L, true));
        assertEquals(List.of(40L, 41L), seen.stream().map(AuroraRegion::worldId).toList());
    }

    private static List<AuroraRegion> read(final RegionMetricsRegistry.GenerationView... views) {
        final List<AuroraRegion> seen = new ArrayList<>();
        topology(views).forEach(0L, seen::add);
        return seen;
    }

    private static RegionTopology topology(final RegionMetricsRegistry.GenerationView... views) {
        final List<RegionMetricsRegistry.GenerationView> copy = List.of(views);
        return new RegionTopology((now, consumer) -> copy.forEach(consumer));
    }

    private static RegionMetricsRegistry.GenerationView view(final long generationId, final long worldId,
                                                             final long regionId, final boolean active) {
        return new RegionMetricsRegistry.GenerationView(generationId, worldId, regionId, active, null, SNAPSHOT);
    }

    private static final RegionTickMetrics.WindowSnapshot WINDOW = new RegionTickMetrics.WindowSnapshot(
        0L, 0L, 0L, 0L, 0L, 0L, 0L, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, false);

    private static final RegionTickMetrics.Snapshot SNAPSHOT = new RegionTickMetrics.Snapshot(
        1L, 0L, RegionTickMetrics.INACTIVE, WINDOW, WINDOW, WINDOW, WINDOW, WINDOW, WINDOW);
}
