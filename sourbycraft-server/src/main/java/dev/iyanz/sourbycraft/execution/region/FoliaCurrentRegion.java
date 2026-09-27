package dev.iyanz.sourbycraft.execution.region;

import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Which region is this thread ticking?", answered from the Folia-derived scheduler, as a chunk
 * that region owns. Used by the Aurora Bridge to run a legacy sync task on the region that
 * scheduled it.
 *
 * <p>Ledgered upstream dependency: {@code TickRegionScheduler.getCurrentRegion()} and the region's
 * {@code getCenterChunk()}. The latter sorts every chunk the region owns, so anchors are cached
 * per region id and recomputed only when the cached chunk has left the region.</p>
 */
public final class FoliaCurrentRegion implements CurrentRegion {

    private static final int MAX_CACHED_ANCHORS = 4096;
    private final Map<Long, RegionAnchor> anchors = new ConcurrentHashMap<>();

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
