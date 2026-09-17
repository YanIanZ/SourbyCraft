package dev.iyanz.sourbycraft.execution.region;

import dev.iyanz.sourbycraft.execution.ExecutionLane;
import dev.iyanz.sourbycraft.perf.RegionMetricsRegistry;
import dev.iyanz.sourbycraft.perf.RegionTickMetrics;

/**
 * One region, as SourbyCraft accounts for it.
 *
 * <p>Identity is a triple, not a single id, and the distinction matters. A world and region id say
 * <em>where</em>; the generation says <em>which incarnation</em>. Regions split and merge as players
 * move, so a region id can outlive the region that bore it — the generation is what makes two
 * readings comparable.</p>
 *
 * <p>Every region runs on {@link ExecutionLane#REGION_TICK}. There is deliberately no way to place
 * one elsewhere: a region <em>is</em> the unit of gameplay ownership, so a region on another lane
 * would be gameplay mutated by a thread that does not own it.</p>
 *
 * @param worldId      the world this region belongs to
 * @param regionId     the region's id within that world
 * @param generationId this incarnation of that region
 * @param active       whether it is still ticking, as opposed to retired but retained for history
 * @param tick         its tick history at the moment of reading
 */
public record AuroraRegion(long worldId, long regionId, long generationId, boolean active,
                           RegionTickMetrics.Snapshot tick) {

    /** The lane every region runs on. Regions define gameplay ownership; they do not move. */
    public ExecutionLane lane() {
        return ExecutionLane.REGION_TICK;
    }

    /**
     * Reads one of the registry's generation views as a region.
     *
     * @param view a generation view from {@link RegionMetricsRegistry}
     * @return the same region, stated in the region system's own terms
     */
    public static AuroraRegion of(final RegionMetricsRegistry.GenerationView view) {
        return new AuroraRegion(view.worldId(), view.regionId(), view.generationId(),
            view.active(), view.snapshot());
    }
}
