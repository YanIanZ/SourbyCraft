package dev.iyanz.sourbycraft.execution.region;

import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegionScheduler;

/**
 * {@link RegionBackend} over the Folia-derived region scheduler.
 *
 * <p>With this in place the region scheduler is named in exactly two files: here, and
 * {@link dev.iyanz.sourbycraft.execution.RegionOwnerHandoff}. One answers "how is the engine
 * ticking", the other "hand this to whoever owns that entity". Nothing else reaches the backend.</p>
 */
public final class FoliaRegionBackend implements RegionBackend {

    @Override
    public RegionTickMetrics.Snapshot globalTickMetrics(final long nowNanos) {
        return RegionizedServer.getGlobalTickData().sourbyTickMetrics.current().refreshSnapshot(nowNanos);
    }

    @Override
    public double tickRateHz() {
        return TickRegionScheduler.getTickRate();
    }
}
