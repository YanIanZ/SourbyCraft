package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.bridge.BridgeRuntime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * World-wide read-only snapshots for a thread that owns no region of the world it reads.
 *
 * <p>The region-threading base keeps the players of a world per region (the region data's local player list,
 * reached through {@code Level.getLocalPlayers()}); a thread that ticks no region of that world -- the global tick
 * thread a bridged untargeted task runs on, an async thread, a region thread of another world -- has no such data,
 * and the base answered with a {@code NullPointerException} ("the return value of Level.getCurrentWorldData() is
 * null") or, for another world's region, an {@code IllegalStateException} ("World mismatch"). Shopkeepers 2.28.1 on
 * the WuretaMC Hub (Build 56, 2026-11-04) hit it 646 times in one log: its AI task ticks each shop mob's
 * {@code GoalSelector} through NMS, and {@code LookAtPlayerGoal} asks the level for the nearest player.</p>
 *
 * <p>The Minecraft feature patch "world-wide player snapshot for off-region readers" decides on-region itself (the
 * region's own players, unchanged) and calls {@link #localPlayers} only off every region of the world. Here, for a
 * <b>bridged plugin's task body while {@code aurora.bridge.thread-checks} is {@code relaxed} or {@code bridged}</b>
 * or for <b>any caller while the checks are {@code relaxed}</b> (owner decision 2026-10-22, LightingLuminol parity),
 * such a read gets the world's whole player list instead: an immutable copy of {@code ServerLevel.players()}, which
 * is a {@code CopyOnWriteArrayList} the login and logout paths maintain for every region, so the copy is consistent
 * and costs one array copy; taking it blocks nothing and touches no region structure. Every other caller --
 * {@code enforced} (also for bridged bodies: the operator asked for fail-fast), Folia-native plugins and the server
 * under {@code bridged} -- gets {@code null} and the patch takes the base's path, so the strict semantics are not
 * widened for code that is supposed to be region-aware. "Bridged body" is the thread marker the bridge sets for the
 * duration of a bridged task body, so it also covers code that body calls on the same thread (a Folia-native
 * plugin's listener fired inside it, say).</p>
 *
 * <p>What the snapshot does <b>not</b> make safe: the caller is still on the wrong thread, and not every caller of
 * {@code getLocalPlayers} only reads. The entity tracker's add/clear, raids, beacons and the dragon fight iterate it
 * to touch player-side state; off their region they used to fail fast on the NPE and now, while relaxed, proceed
 * on the wrong thread (the accepted LightingLuminol races: see {@link ThreadChecks}). Shopkeepers' path
 * ({@code getNearestPlayer}) is read-only distance arithmetic over the copy, racy but harmless. A relaxed server
 * stays unsafe and unqualified.</p>
 *
 * <p>Each snapshot is counted ({@link #playerSnapshots()}, {@code /perf plugins}) and announced by one rate-limited
 * WARN per (plugin or thread, world) per 60 s naming the plugin, task and world. Entities need no snapshot of their
 * own: {@code World#getEntities()} and its siblings go through Moonrise's {@code EntityLookup.getAll()}, which
 * already copies the world-wide list under its own lock.</p>
 */
public final class OffRegionWorldReads {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aurora");

    /** How long one (plugin or thread, world) line suppresses the next. */
    static final long LOG_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60L);
    /** Distinct keys remembered for the rate limit; past this, new keys share one window. */
    static final int MAX_LOG_KEYS = 256;

    private static final LongAdder PLAYER_SNAPSHOTS = new LongAdder();
    private static final LogWindows LOG_WINDOWS = new LogWindows(LOG_WINDOW_NANOS, MAX_LOG_KEYS);

    private OffRegionWorldReads() {}

    /**
     * Whether an off-region read is served a snapshot instead of the base's exception.
     *
     * @param relaxed {@link ThreadChecks#RELAXED}: every caller is served
     * @param relaxedScope {@link ThreadChecks#RELAXED_SCOPE}: {@code relaxed} or {@code bridged}; under
     *     {@code enforced} nobody is served, bridged bodies included (the operator chose fail-fast)
     * @param bridgedBody whether a bridged plugin's task body is running on the calling thread
     */
    static boolean serves(final boolean relaxed, final boolean relaxedScope, final boolean bridgedBody) {
        return relaxed || (bridgedBody && relaxedScope);
    }

    /** An immutable copy of a world-wide list; the caller may iterate it after the source changed. */
    static <T> List<T> snapshot(final List<T> worldWide) {
        return List.copyOf(worldWide);
    }

    /**
     * Called by the Minecraft feature patch from {@code Level.getLocalPlayers()} only when the calling thread ticks
     * no region of {@code level}.
     *
     * @param regionWorld the world of the region the thread ticks, or {@code null} when it ticks none (the global
     *     tick thread, an async thread)
     * @return an immutable world-wide snapshot of the level's players when the caller is served (see
     *     {@link #serves}), else {@code null}: the patch then takes the base's path (a {@code NullPointerException}
     *     off every region, "World mismatch" on another world's region)
     */
    public static List<ServerPlayer> localPlayers(final ServerLevel level, final String regionWorld) {
        final String plugin = BridgeRuntime.bridgedPluginOnThread();
        if (!serves(ThreadChecks.RELAXED, ThreadChecks.RELAXED_SCOPE, plugin != null)) {
            return null;
        }
        final List<ServerPlayer> players = snapshot(level.players());
        PLAYER_SNAPSHOTS.increment();
        final String world = level.getWorld().getName();
        // The key is the cheapest string that separates the windows; the line itself is built only when admitted.
        final String keyOwner = plugin == null ? Thread.currentThread().getName() : plugin;
        final long suppressed = admitLog(keyOwner + '|' + world, System.nanoTime());
        if (suppressed >= 0) {
            final String caller = plugin == null ? "thread " + keyOwner
                : "plugin " + plugin + " (task " + BridgeRuntime.bridgedTaskOnThread() + ")";
            final String where = regionWorld == null ? "no region" : "a region of " + regionWorld;
            LOGGER.warn(describe(caller, world, "players", players.size(), where) + LOG_WINDOWS.suppressedNote(suppressed));
        }
        return players;
    }

    /** The WARN text for one served snapshot (without the suppressed note). */
    static String describe(final String caller, final String world, final String read, final int size, final String where) {
        return "Off-region world read: " + caller + " read the " + read + " of world " + world
            + " from a thread that owns no region of it (" + where + "); served a world-wide read-only snapshot of "
            + size + " " + read + " instead of the base's exception. The caller is still on the wrong thread"
            + " (aurora.bridge.thread-checks " + ThreadChecks.effective().configValue() + "; not qualified).";
    }

    /** Rate limit seam: {@code -1} to suppress, else how many lines the key suppressed before. */
    static long admitLog(final String key, final long nowNanos) {
        return LOG_WINDOWS.admit(key, nowNanos);
    }

    /** Player snapshots served to off-region readers since boot. */
    public static long playerSnapshots() {
        return PLAYER_SNAPSHOTS.sum();
    }

    /** For {@code OffRegionWorldReadsTest} only: clears the rate-limit memory. */
    static void resetLogMemoryForTesting() {
        LOG_WINDOWS.clear();
    }
}
