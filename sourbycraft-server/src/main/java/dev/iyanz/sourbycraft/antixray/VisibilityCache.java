package dev.iyanz.sourbycraft.antixray;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player set of block positions (packed long via
 * {@code net.minecraft.core.BlockPos.asLong}) that the
 * {@link RayTraceWorker} has confirmed are currently in line-of-sight.
 * The chunk-send obfuscation path consults this cache before stripping
 * an ore from the outgoing packet — present in cache => ore stays
 * visible, absent => ore stays obfuscated.
 *
 * <p>Set is bounded by {@link #MAX_PER_PLAYER}; oldest entries get
 * dropped when the player accumulates too many visible-ore positions.
 * Cheap to look up (fastutil {@code LongOpenHashSet#contains}).
 *
 * <p>This is the SourbyCraft port of RayTraceAntiXray's per-player
 * visibility map, but using fastutil primitive sets rather than
 * boxed {@code HashMap<Long, Boolean>}.
 */
public final class VisibilityCache {

    public static final int MAX_PER_PLAYER = 4096;

    private static final Map<UUID, LongOpenHashSet> CACHE = new ConcurrentHashMap<>();

    private VisibilityCache() {}

    public static boolean isVisible(final UUID playerId, final long blockKey) {
        LongOpenHashSet set = CACHE.get(playerId);
        if (set == null) return false;
        // Same monitor as markVisible: virtual-thread writes must happen-before main-thread reads,
        // and contains() during a backing-array resize is not safe unsynchronized.
        synchronized (set) {
            return set.contains(blockKey);
        }
    }

    public static void markVisible(final UUID playerId, final long blockKey) {
        LongOpenHashSet set = CACHE.computeIfAbsent(playerId, id -> new LongOpenHashSet());
        synchronized (set) {
            if (set.size() >= MAX_PER_PLAYER) {
                // Random eviction: drop one entry to keep the set bounded.
                // Hot-path inserts dominate; precise LRU is not worth the cost.
                long victim = set.iterator().nextLong();
                set.remove(victim);
            }
            set.add(blockKey);
        }
    }

    public static void clear(final UUID playerId) {
        CACHE.remove(playerId);
    }

    public static void clearAll() {
        CACHE.clear();
    }
}
