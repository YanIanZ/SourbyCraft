package dev.iyanz.sourbycraft.execution.region;

import java.util.List;

/**
 * Loaded chunks, entities and players per world, summed from the engine's own per-region counters,
 * for {@code /perf chunks} and {@code /perf entities}. {@link FoliaRegionBackend} supplies it.
 *
 * <p>The counters are refreshed by each region as it ticks, so a figure can be up to one tick old
 * and a region that has stopped ticking keeps its last value.</p>
 */
public interface RegionPopulation {

    record WorldCounts(String world, int regions, long chunks, long entities, long players) {}

    /** One entry per loaded world. Safe from any thread; touches no world state. */
    List<WorldCounts> population();
}
