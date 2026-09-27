package dev.iyanz.sourbycraft.execution.region;

import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RegionBackend} and {@link CurrentRegion} over the Folia-derived region scheduler.
 *
 * <p>With this in place the region scheduler is named in exactly two files: here, and
 * {@link dev.iyanz.sourbycraft.execution.RegionOwnerHandoff}. One answers "how is the engine
 * ticking" and "which region is this thread ticking", the other "hand this to whoever owns that
 * entity". Nothing else reaches the backend.</p>
 */
public final class FoliaRegionBackend implements RegionBackend, CurrentRegion {

    private static final int MAX_CACHED_ANCHORS = 4096;
    /** Anchors by region id; recomputed when the cached chunk has left the region. */
    private final Map<Long, RegionAnchor> anchors = new ConcurrentHashMap<>();

    @Override
    public RegionTickMetrics.Snapshot globalTickMetrics(final long nowNanos) {
        return RegionizedServer.getGlobalTickData().sourbyTickMetrics.current().refreshSnapshot(nowNanos);
    }

    @Override
    public double tickRateHz() {
        return TickRegionScheduler.getTickRate();
    }

    /**
     * A chunk the calling thread's region owns, for the Aurora Bridge's caller-region route.
     * {@code getCenterChunk} sorts every chunk the region owns, so anchors are cached per region
     * id and recomputed only when the cached chunk has left the region.
     */
    @Override
    public RegionAnchor anchor() {
        final var region = TickRegionScheduler.getCurrentRegion();
        if (region == null) return null;
        final RegionAnchor cached = this.anchors.get(region.id);
        if (cached != null && org.bukkit.Bukkit.isOwnedByCurrentRegion((org.bukkit.World)cached.world(),
            cached.chunkX(), cached.chunkZ())) {
            return cached;
        }
        final net.minecraft.world.level.ChunkPos center = region.getCenterChunk();
        if (center == null) return null;
        final RegionAnchor anchor = new RegionAnchor(region.regioniser.world.getWorld(), center.x(), center.z());
        if (this.anchors.size() >= MAX_CACHED_ANCHORS) this.anchors.clear();
        this.anchors.put(region.id, anchor);
        return anchor;
    }
}
