package dev.iyanz.sourbycraft.execution.region;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Anchors by region id, held <em>weakly</em> with respect to their world.
 *
 * <p>A region id is never reused, so an entry for a region that has since merged, split or been
 * destroyed is never looked up again, and nothing removes it except the size cap. A cache that held the
 * {@link RegionAnchor} (which holds the Bukkit world) strongly therefore pinned every world a region had
 * ever been anchored in, including one that was unloaded since, until 4096 further anchors forced a
 * clear. Here only the world is weak; the chunk coordinates are two ints.</p>
 *
 * <p>A hit rebuilds the (small, immutable) anchor from the entry. An entry whose world has been
 * collected is a miss and is dropped.</p>
 */
final class AnchorCache {

    private record Entry(WeakReference<Object> world, int chunkX, int chunkZ) {}

    private final int maxEntries;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    AnchorCache(final int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * @param stillOwned whether the cached chunk is still owned by the caller's region; a cached
     *     anchor the region has since lost is a miss
     * @return the cached anchor, or {@code null} when absent, its world is gone, or it is no longer owned
     */
    RegionAnchor get(final long regionId, final Predicate<RegionAnchor> stillOwned) {
        final Entry entry = this.entries.get(regionId);
        if (entry == null) {
            return null;
        }
        final Object world = entry.world().get();
        if (world == null) {
            this.entries.remove(regionId, entry);
            return null;
        }
        final RegionAnchor anchor = new RegionAnchor(world, entry.chunkX(), entry.chunkZ());
        return stillOwned.test(anchor) ? anchor : null;
    }

    void put(final long regionId, final RegionAnchor anchor) {
        if (this.entries.size() >= this.maxEntries) {
            // Entries whose world is gone are dead weight; only if that is not enough, start over.
            this.entries.values().removeIf(entry -> entry.world().get() == null);
            if (this.entries.size() >= this.maxEntries) {
                this.entries.clear();
            }
        }
        this.entries.put(regionId, new Entry(new WeakReference<>(anchor.world()), anchor.chunkX(), anchor.chunkZ()));
    }

    int size() {
        return this.entries.size();
    }
}
