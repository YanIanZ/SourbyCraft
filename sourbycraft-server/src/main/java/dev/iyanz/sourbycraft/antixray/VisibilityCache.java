package dev.iyanz.sourbycraft.antixray;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-player set of block positions (packed long via
 * {@code net.minecraft.core.BlockPos.asLong}) that the
 * {@link RayTraceWorker} has confirmed are currently in line-of-sight.
 * The chunk-send obfuscation path consults this cache before stripping
 * an ore from the outgoing packet — present in cache => ore stays
 * visible, absent => ore stays obfuscated.
 *
 * <p>Set is bounded by {@link #MAX_PER_PLAYER}; a {@link LongLinkedOpenHashSet}
 * gives true FIFO eviction ({@code removeFirstLong}) without allocating an
 * iterator per insert once full.
 *
 * <p><b>Epoch guard (anti-leak).</b> An async ray submitted before a
 * teleport / world change / quit may land AFTER the corresponding
 * {@link #clear} — re-confirming visibility from a stale eye position,
 * which is exactly the leak those clears exist to close. Every clear bumps
 * the player's epoch; workers capture the epoch at submit time and
 * {@link #markVisible(UUID, long, long)} discards results whose epoch no
 * longer matches. Epoch counters are monotonic and deliberately survive
 * quit (one {@code AtomicLong} per unique UUID since boot — trivially
 * bounded) so an in-flight worker can never resurrect a cleared state by
 * recreating a zeroed counter.
 */
public final class VisibilityCache {

    public static final int MAX_PER_PLAYER = 4096;

    private static final Map<UUID, LongLinkedOpenHashSet> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicLong> EPOCHS = new ConcurrentHashMap<>();

    private VisibilityCache() {}

    /** Current epoch for the player; capture at submit time, pass to {@link #markVisible}. */
    public static long epoch(final UUID playerId) {
        AtomicLong e = EPOCHS.get(playerId);
        return (e != null ? e : EPOCHS.computeIfAbsent(playerId, id -> new AtomicLong())).get();
    }

    public static boolean isVisible(final UUID playerId, final long blockKey) {
        return isVisible(CACHE.get(playerId), blockKey);
    }

    /**
     * Per-player set handle for loop hoisting: fetch once via {@link #setFor}, then call this
     * overload per key — collapses O(pending) ConcurrentHashMap lookups to one per player-cycle.
     */
    public static LongLinkedOpenHashSet setFor(final UUID playerId) {
        return CACHE.get(playerId);
    }

    public static boolean isVisible(final LongLinkedOpenHashSet set, final long blockKey) {
        if (set == null) return false;
        // Same monitor as markVisible: virtual-thread writes must happen-before region-thread reads,
        // and contains() during a backing-array resize is not safe unsynchronized.
        synchronized (set) {
            return set.contains(blockKey);
        }
    }

    /**
     * Record a confirmed-visible position. {@code epochAtSubmit} must be the value of
     * {@link #epoch} captured when the ray was submitted; a clear in between discards the result.
     */
    public static void markVisible(final UUID playerId, final long blockKey, final long epochAtSubmit) {
        if (epoch(playerId) != epochAtSubmit) return; // eye moved (teleport/world/quit) since submit
        LongLinkedOpenHashSet set = CACHE.computeIfAbsent(playerId, id -> new LongLinkedOpenHashSet());
        synchronized (set) {
            if (set.size() >= MAX_PER_PLAYER) {
                set.removeFirstLong(); // FIFO: evict the oldest confirmation
            }
            set.add(blockKey);
        }
    }

    public static void clear(final UUID playerId) {
        // Bump BEFORE removing the set so an in-flight markVisible either sees the old epoch and
        // writes into the map we are about to drop, or sees the new epoch and discards itself.
        EPOCHS.computeIfAbsent(playerId, id -> new AtomicLong()).incrementAndGet();
        CACHE.remove(playerId);
    }

    public static void clearAll() {
        for (UUID id : CACHE.keySet()) clear(id);
    }
}
