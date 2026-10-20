package dev.iyanz.sourbycraft.execution.region;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.retention.Reachability;
import dev.iyanz.sourbycraft.retention.Reachability.Tracker;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The caller-region anchor cache of {@link FoliaRegionBackend} (keyed by region id) must not keep an
 * unloaded world alive.
 *
 * <p>Leak found by this inventory: the cache used to hold {@link RegionAnchor}s, which hold the Bukkit
 * world, strongly, and region ids are never reused, so the entry of a retired region was never read
 * again and nothing removed it except a clear once 4096 anchors had piled up. A world unloaded since
 * stayed reachable (CraftWorld, therefore its ServerLevel) through any such entry. The cache now holds
 * the world weakly; see {@link AnchorCache}.</p>
 */
class AnchorCacheRetentionTest {

    private static Tracker cacheAnAnchorFor(final AnchorCache cache, final long regionId) {
        final Object world = new Object();
        cache.put(regionId, new RegionAnchor(world, 3, -4));
        return Reachability.track("world of region " + regionId, world);
    }

    @Test
    void aWorldUnloadedWhileItsRegionsStayCachedBecomesCollectable() throws Exception {
        final AnchorCache cache = new AnchorCache(4096);
        final Tracker unloaded = cacheAnAnchorFor(cache, 1L);
        assertEquals(1, cache.size());

        // nothing else references the world any more (the engine dropped it on unload)
        unloaded.assertCollected();

        assertNull(cache.get(1L, anchor -> true), "a collected world is a miss, never a stale anchor");
        assertEquals(0, cache.size(), "and the dead entry is dropped by the lookup");
    }

    @Test
    void aLiveWorldKeepsServingItsAnchorAndTheCacheDoesNotKeepItAlive() throws Exception {
        final AnchorCache cache = new AnchorCache(4096);
        final Object world = new Object();
        cache.put(7L, new RegionAnchor(world, 10, 20));

        final RegionAnchor hit = cache.get(7L, anchor -> true);
        assertNotNull(hit);
        assertSame(world, hit.world());
        assertEquals(10, hit.chunkX());
        assertEquals(20, hit.chunkZ());
        assertNull(cache.get(8L, anchor -> true), "other regions are misses");
        assertEquals(10, cache.get(7L, anchor -> true).chunkX(), "repeated hits are stable");
        java.lang.ref.Reference.reachabilityFence(world);
    }

    @Test
    void anAnchorTheRegionNoLongerOwnsIsAMissSoItIsRecomputed() {
        final AnchorCache cache = new AnchorCache(4096);
        final Object world = new Object();
        cache.put(7L, new RegionAnchor(world, 10, 20));
        final AtomicInteger asked = new AtomicInteger();
        assertNull(cache.get(7L, anchor -> {
            asked.incrementAndGet();
            assertEquals(10, anchor.chunkX());
            return false;
        }));
        assertEquals(1, asked.get());
        // The replacement overwrites the entry for the same region id.
        cache.put(7L, new RegionAnchor(world, 11, 21));
        assertEquals(11, cache.get(7L, anchor -> true).chunkX());
        assertEquals(1, cache.size());
    }

    @Test
    void whenFullEntriesOfCollectedWorldsGoFirstAndLiveOnesSurvive() throws Exception {
        final AnchorCache cache = new AnchorCache(8);
        final Object liveWorld = new Object();
        cache.put(100L, new RegionAnchor(liveWorld, 1, 1));
        final java.util.List<Tracker> dead = new java.util.ArrayList<>();
        for (long id = 1; id < 8; id++) {
            dead.add(cacheAnAnchorFor(cache, id));
        }
        assertEquals(8, cache.size());
        Reachability.assertAllCollected(dead);

        cache.put(101L, new RegionAnchor(liveWorld, 2, 2));

        assertEquals(2, cache.size(), "the seven entries of unloaded worlds were purged; nothing live was cleared");
        assertNotNull(cache.get(100L, anchor -> true));
        assertNotNull(cache.get(101L, anchor -> true));
    }

    @Test
    void whenFullOfLiveWorldsTheCacheStartsOverInsteadOfGrowing() {
        final AnchorCache cache = new AnchorCache(8);
        final Object[] worlds = new Object[20];
        for (int i = 0; i < worlds.length; i++) {
            worlds[i] = new Object();
            cache.put(i, new RegionAnchor(worlds[i], i, i));
            assertTrue(cache.size() <= 8, "bounded at " + cache.size());
        }
        assertNotNull(cache.get(19L, anchor -> true), "the newest entry is always present");
        assertSame(worlds[19], cache.get(19L, anchor -> true).world());
    }

    @Test
    void theBackendUsesTheWeakCacheNotAMapOfAnchors() throws Exception {
        assertEquals(AnchorCache.class, FoliaRegionBackend.class.getDeclaredField("anchors").getType(),
            "FoliaRegionBackend must keep its region anchors in the weak AnchorCache");
    }
}
