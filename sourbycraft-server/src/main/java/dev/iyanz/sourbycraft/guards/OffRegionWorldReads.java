package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.bridge.BridgeRuntime;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegionScheduler;
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
 * <p>The region-threading base keeps the players of a world per region ({@code RegionizedWorldData.getLocalPlayers},
 * reached through {@code Level.getLocalPlayers()}); a thread that ticks no region of that world -- the global tick
 * thread a bridged untargeted task runs on, an async thread, a region thread of another world -- has no such data,
 * and the base answered with a {@code NullPointerException} ("the return value of Level.getCurrentWorldData() is
 * null") or, for another world's region, an {@code IllegalStateException} ("World mismatch"). Shopkeepers 2.28.1 on
 * the WuretaMC Hub (Build 56, 2026-11-04) hit it 646 times in one log: its AI task ticks each shop mob's
 * {@code GoalSelector} through NMS, and {@code LookAtPlayerGoal} asks the level for the nearest player.</p>
 *
 * <p>Here, for a <b>bridged plugin's task body</b> (any {@code aurora.bridge.thread-checks} value) or <b>any caller
 * while the checks are {@code relaxed}</b> (owner decision 2026-10-22, LightingLuminol parity), such a read gets the
 * world's whole player list instead: an immutable copy of {@code ServerLevel.players()}, which is a
 * {@code CopyOnWriteArrayList} the login and logout paths maintain for every region, so the copy is consistent and
 * costs one array copy. Nothing is mutated and nothing blocks. Every other caller keeps the base's behaviour exactly
 * (Folia-native plugins and the server itself see the same exception as before), so the strict semantics are not
 * widened for code that is supposed to be region-aware.</p>
 *
 * <p>Each snapshot is counted ({@link #playerSnapshots()}, {@code /perf plugins}) and announced by one rate-limited
 * WARN per (plugin or thread, world, read) per 60 s naming the plugin, task and world, because a plugin reading
 * players off its region is still a plugin running on the wrong thread. Entities need no snapshot of their own:
 * {@code World#getEntities()} and its siblings go through Moonrise's {@code EntityLookup.getAll()}, which already
 * copies the world-wide list under its own lock.</p>
 *
 * <p>Honesty: this removes one exception; it does not make the caller region-safe. A relaxed server stays unsafe and
 * unqualified (see {@link ThreadChecks}).</p>
 */
public final class OffRegionWorldReads {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aurora");

    /** How long one (plugin or thread, world, read) line suppresses the next. */
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
     * @param bridgedBody whether a bridged plugin's task body is running on the calling thread
     */
    static boolean serves(final boolean relaxed, final boolean bridgedBody) {
        return relaxed || bridgedBody;
    }

    /** An immutable copy of a world-wide list; the caller may iterate it after the source changed. */
    static <T> List<T> snapshot(final List<T> worldWide) {
        return List.copyOf(worldWide);
    }

    /**
     * The players of {@code level} as the calling thread may read them: the region's local players when the
     * thread ticks a region of that world (the base's answer, unchanged); for a bridged body or any caller while
     * checks are relaxed, a world-wide immutable snapshot; otherwise the base's exception.
     *
     * <p>Called by the Minecraft feature patch "world-wide player snapshot for off-region readers" from
     * {@code Level.getLocalPlayers()} and {@code ServerLevel.getLocalPlayers(selector, max)}.</p>
     */
    public static List<ServerPlayer> localPlayers(final ServerLevel level) {
        final RegionizedWorldData current = TickRegionScheduler.getCurrentRegionizedWorldData();
        if (current != null && current.world == level) {
            return current.getLocalPlayers();
        }
        final String plugin = BridgeRuntime.bridgedPluginOnThread();
        if (!serves(ThreadChecks.RELAXED, plugin != null)) {
            // The base's path: null off every region (NullPointerException at the caller), "World mismatch" on
            // another world's region. Folia-native callers keep the strict semantics.
            return level.getCurrentWorldData().getLocalPlayers();
        }
        final List<ServerPlayer> players = snapshot(level.players());
        PLAYER_SNAPSHOTS.increment();
        final String where = current == null ? "no region" : "a region of " + current.world.getWorld().getName();
        final String world = level.getWorld().getName();
        final String caller = plugin == null ? "thread " + Thread.currentThread().getName()
            : "plugin " + plugin + " (task " + BridgeRuntime.bridgedTaskOnThread() + ")";
        final long suppressed = admitLog((plugin == null ? Thread.currentThread().getName() : plugin) + '|' + world + "|players",
            System.nanoTime());
        if (suppressed >= 0) {
            LOGGER.warn(describe(caller, world, "players", players.size(), where) + LOG_WINDOWS.suppressedNote(suppressed));
        }
        return players;
    }

    /** The WARN text for one served snapshot (without the suppressed note). */
    static String describe(final String caller, final String world, final String read, final int size, final String where) {
        return "Off-region world read: " + caller + " read the " + read + " of world " + world
            + " from a thread that owns no region of it (" + where + "); served a world-wide read-only snapshot of "
            + size + " " + read + " instead of the base's exception (aurora.bridge.thread-checks "
            + ThreadChecks.effective().configValue() + "; not qualified).";
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
