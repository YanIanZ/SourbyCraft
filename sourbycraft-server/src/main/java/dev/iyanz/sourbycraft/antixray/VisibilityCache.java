package dev.iyanz.sourbycraft.antixray;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SourbyEngine per-player REVEALED-ore set (packed BlockPos.asLong -> consecutive-miss streak).
 *
 * <p>Unlike the old persistent cache, a reveal here is DYNAMIC: the SourbyEngine reveal cycle
 * re-validates line-of-sight to revealed ores and, when a player loses sight of one for
 * {@link #HIDE_STREAK} consecutive checks, drops it so the ore is re-hidden. This closes the
 * "peek once, x-ray forever" hole — an ore is shown only while the player can actually see it.
 *
 * <p><b>Epoch guard.</b> An async ray submitted before a teleport / world change / quit may land
 * after the matching {@link #clear}; every clear bumps the player's epoch and stale results are
 * discarded. Epoch counters survive quit (one {@code AtomicLong} per UUID since boot, trivially
 * bounded) so an in-flight worker can never resurrect a cleared state.
 */
public final class VisibilityCache {

    public static final int MAX_PER_PLAYER = 4096;
    /** Consecutive out-of-sight re-validations before a revealed ore is re-hidden (anti-flicker). */
    public static final int HIDE_STREAK = 3;

    private static final Map<UUID, Long2IntOpenHashMap> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicLong> EPOCHS = new ConcurrentHashMap<>();

    private VisibilityCache() {}

    public static long epoch(final UUID playerId) {
        AtomicLong e = EPOCHS.get(playerId);
        return (e != null ? e : EPOCHS.computeIfAbsent(playerId, id -> new AtomicLong())).get();
    }

    /** The per-player revealed-ore map (miss streaks); may be null. Guard with synchronized(map). */
    public static Long2IntOpenHashMap mapFor(final UUID playerId) {
        return CACHE.get(playerId);
    }

    /** True when NO player has any revealed ore (cheap gate for the global reveal cycle). */
    public static boolean isEmpty() {
        return CACHE.isEmpty();
    }

    /** True when this player has at least one revealed ore needing re-validation. */
    public static boolean hasRevealed(final UUID playerId) {
        final Long2IntOpenHashMap map = CACHE.get(playerId);
        if (map == null) return false;
        synchronized (map) {
            return !map.isEmpty();
        }
    }

    public static boolean isVisible(final UUID playerId, final long blockKey) {
        return isVisible(CACHE.get(playerId), blockKey);
    }

    public static boolean isVisible(final Long2IntOpenHashMap map, final long blockKey) {
        if (map == null) return false;
        synchronized (map) {
            return map.containsKey(blockKey);
        }
    }

    /**
     * Mark an ore revealed (miss streak reset to 0). {@code epochAtSubmit} must be the value of
     * {@link #epoch} captured when the ray was submitted; a clear in between discards the result.
     */
    public static void markVisible(final UUID playerId, final long blockKey, final long epochAtSubmit) {
        if (epoch(playerId) != epochAtSubmit) return; // eye moved (teleport/world/quit) since submit
        Long2IntOpenHashMap map = CACHE.computeIfAbsent(playerId, id -> {
            Long2IntOpenHashMap m = new Long2IntOpenHashMap();
            m.defaultReturnValue(0);
            return m;
        });
        synchronized (map) {
            if (map.size() >= MAX_PER_PLAYER && !map.containsKey(blockKey)) return; // bounded; drop new
            map.put(blockKey, 0);
        }
    }

    /**
     * Record a re-validation MISS for a revealed ore. Returns {@code true} when the ore has now
     * been out of sight for {@link #HIDE_STREAK} consecutive checks and should be RE-HIDDEN (it is
     * removed from the revealed set in that case).
     */
    public static boolean recordMissAndMaybeHide(final Long2IntOpenHashMap map, final long blockKey) {
        if (map == null) return false;
        synchronized (map) {
            if (!map.containsKey(blockKey)) return false;
            int miss = map.get(blockKey) + 1;
            if (miss >= HIDE_STREAK) {
                map.remove(blockKey);
                return true;
            }
            map.put(blockKey, miss);
            return false;
        }
    }

    /** Re-validation HIT: the player still sees the ore — reset its miss streak. */
    public static void recordHit(final Long2IntOpenHashMap map, final long blockKey) {
        if (map == null) return;
        synchronized (map) {
            if (map.containsKey(blockKey)) map.put(blockKey, 0);
        }
    }

    public static void clear(final UUID playerId) {
        EPOCHS.computeIfAbsent(playerId, id -> new AtomicLong()).incrementAndGet();
        CACHE.remove(playerId);
    }

    public static void clearAll() {
        for (UUID id : CACHE.keySet()) clear(id);
    }
}
