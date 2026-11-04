package dev.iyanz.sourbycraft.api.scheduler;

import java.time.Duration;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 55. One place to schedule work on SourbyCraft's region-threaded server, obtained with
 * {@link dev.iyanz.sourbycraft.api.SourbyAPI#scheduler()}.
 *
 * <p>SourbyCraft has no main thread. Every chunk belongs to a region, each region ticks on its own thread, and
 * world, block and entity state may only be touched by the thread of the region that owns it. This facade picks the
 * right scheduler for the thing the work is about:</p>
 *
 * <ul>
 *   <li>{@code runAt…} — a {@link Location} (or a chunk): the work runs on the region that owns that chunk when it
 *       becomes due. Use it for blocks and for state tied to a place.</li>
 *   <li>{@code runFor…} — an {@link Entity}: the work runs on whichever region owns the entity at that moment, so
 *       it follows the entity across regions and worlds. If the entity is removed first (a player who quits, an
 *       entity that dies or is unloaded with its chunk or world), the work does not run; the optional
 *       {@code retired} callback runs instead (on the entity's region). Use it for players and entities.</li>
 *   <li>{@code runGlobal…} — the global region: world-independent server state (time, weather, the world list,
 *       console commands). It must not touch chunks, blocks or entities.</li>
 *   <li>{@code runAsync…} — a thread of no region at all, for blocking I/O. It must not touch world state.</li>
 * </ul>
 *
 * <pre>{@code
 * AuroraScheduler scheduler = SourbyAPI.scheduler();
 *
 * // A block, on its own region, on the next tick.
 * scheduler.runAt(plugin, block.getLocation(), () -> block.setType(Material.GOLD_BLOCK));
 *
 * // Each second, on whichever region owns the player; it stops itself, and stops when the player leaves.
 * scheduler.runForTimer(plugin, player, task -> {
 *     if (player.getHealth() >= 19) task.cancel();
 *     player.setHealth(Math.min(20, player.getHealth() + 1));
 * }, 20L, 20L);
 *
 * // Blocking I/O off the regions, then back to the player's region with the answer.
 * scheduler.runAsync(plugin, () -> {
 *     String rank = database.loadRank(player.getUniqueId());
 *     scheduler.runFor(plugin, player, () -> player.sendMessage("Rank: " + rank));
 * });
 *
 * // Only touch the location directly when this thread already owns it.
 * if (scheduler.isOwnedByCurrentRegion(location)) location.getBlock().setType(Material.AIR);
 * else scheduler.runAt(plugin, location, () -> location.getBlock().setType(Material.AIR));
 * }</pre>
 *
 * <p><b>Implementation.</b> On SourbyCraft this is a thin layer over the server's public region-threading
 * schedulers ({@link org.bukkit.Bukkit#getRegionScheduler()}, {@link Entity#getScheduler()},
 * {@link org.bukkit.Bukkit#getGlobalRegionScheduler()} and {@link org.bukkit.Bukkit#getAsyncScheduler()}); it adds
 * no queue or thread of its own, so the tasks behave exactly as tasks scheduled there do. In particular: the
 * plugin must be enabled (otherwise the call throws {@link org.bukkit.plugin.IllegalPluginAccessException}), and
 * every task of a plugin is cancelled when that plugin is disabled.</p>
 *
 * <p><b>Ticks.</b> Delays and periods are in ticks of the region (or of the global region) that runs the task, which
 * each tick independently; 20 ticks are one second only while that region keeps up. A delay of {@code 0} means the
 * next tick, as {@code runAt}/{@code runFor}/{@code runGlobal} do; a negative delay, and a period below {@code 1},
 * throw {@link IllegalArgumentException}. A task never runs on the calling thread before the method returns.</p>
 *
 * <p><b>Threads.</b> Every method may be called from any thread. A callback that throws is logged by the underlying
 * scheduler under the plugin's logger; a repeating task is scheduled again.</p>
 */
@NullMarked
public interface AuroraScheduler {

    // ------------------------------------------------------------------ region of a location

    /** Runs {@code task} on the next tick of the region that owns {@code location}'s chunk. */
    TaskHandle runAt(Plugin plugin, Location location, Runnable task);

    /** Runs {@code task} on the region that owns {@code location}'s chunk after {@code delayTicks} ticks. */
    TaskHandle runAtLater(Plugin plugin, Location location, Runnable task, long delayTicks);

    /**
     * Runs {@code task} on the region that owns {@code location}'s chunk after {@code delayTicks} ticks, then every
     * {@code periodTicks} ticks until it is cancelled. The task receives its handle to cancel itself.
     */
    TaskHandle runAtTimer(Plugin plugin, Location location, Consumer<TaskHandle> task, long delayTicks, long periodTicks);

    /** As {@link #runAt(Plugin, Location, Runnable)} for chunk {@code chunkX, chunkZ} of {@code world}. */
    default TaskHandle runAt(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Runnable task) {
        return runAt(plugin, chunkOrigin(world, chunkX, chunkZ), task);
    }

    /** As {@link #runAtLater(Plugin, Location, Runnable, long)} for chunk {@code chunkX, chunkZ} of {@code world}. */
    default TaskHandle runAtLater(final Plugin plugin, final World world, final int chunkX, final int chunkZ,
                                  final Runnable task, final long delayTicks) {
        return runAtLater(plugin, chunkOrigin(world, chunkX, chunkZ), task, delayTicks);
    }

    /** As {@link #runAtTimer(Plugin, Location, Consumer, long, long)} for chunk {@code chunkX, chunkZ} of {@code world}. */
    default TaskHandle runAtTimer(final Plugin plugin, final World world, final int chunkX, final int chunkZ,
                                  final Consumer<TaskHandle> task, final long delayTicks, final long periodTicks) {
        return runAtTimer(plugin, chunkOrigin(world, chunkX, chunkZ), task, delayTicks, periodTicks);
    }

    // ------------------------------------------------------------------ an entity

    /**
     * Runs {@code task} on the next tick of the region that owns {@code entity} at that moment. If the entity is
     * removed first, nothing runs; if it has already been removed, nothing is scheduled and the returned handle
     * {@linkplain TaskHandle#isCancelled() is cancelled}.
     */
    default TaskHandle runFor(final Plugin plugin, final Entity entity, final Runnable task) {
        return runFor(plugin, entity, task, null);
    }

    /**
     * As {@link #runFor(Plugin, Entity, Runnable)}; when the entity is removed before {@code task} runs,
     * {@code retired} runs instead (on the entity's region, never both). Neither runs, and the returned handle is
     * cancelled, when the entity had already been removed when this was called. {@code retired} is called while the
     * entity is being removed: it must not remove or teleport the entity or schedule more work for it.
     */
    TaskHandle runFor(Plugin plugin, Entity entity, Runnable task, @Nullable Runnable retired);

    /** As {@link #runFor(Plugin, Entity, Runnable)} after {@code delayTicks} ticks. */
    default TaskHandle runForLater(final Plugin plugin, final Entity entity, final Runnable task, final long delayTicks) {
        return runForLater(plugin, entity, task, delayTicks, null);
    }

    /** As {@link #runFor(Plugin, Entity, Runnable, Runnable)} after {@code delayTicks} ticks. */
    TaskHandle runForLater(Plugin plugin, Entity entity, Runnable task, long delayTicks, @Nullable Runnable retired);

    /**
     * Runs {@code task} on the region that owns {@code entity} after {@code delayTicks} ticks, then every
     * {@code periodTicks} ticks, until it is cancelled or the entity is removed (the task then stops and its handle
     * reports cancelled).
     */
    default TaskHandle runForTimer(final Plugin plugin, final Entity entity, final Consumer<TaskHandle> task,
                                   final long delayTicks, final long periodTicks) {
        return runForTimer(plugin, entity, task, delayTicks, periodTicks, null);
    }

    /**
     * As {@link #runForTimer(Plugin, Entity, Consumer, long, long)}; {@code retired} runs once, on the entity's
     * region, when the entity is removed while the task is still scheduled.
     */
    TaskHandle runForTimer(Plugin plugin, Entity entity, Consumer<TaskHandle> task, long delayTicks, long periodTicks,
                           @Nullable Runnable retired);

    // ------------------------------------------------------------------ the global region

    /** Runs {@code task} on the next tick of the global region. It must not touch chunks, blocks or entities. */
    TaskHandle runGlobal(Plugin plugin, Runnable task);

    /** Runs {@code task} on the global region after {@code delayTicks} ticks. */
    TaskHandle runGlobalLater(Plugin plugin, Runnable task, long delayTicks);

    /** Runs {@code task} on the global region after {@code delayTicks} ticks, then every {@code periodTicks} ticks. */
    TaskHandle runGlobalTimer(Plugin plugin, Consumer<TaskHandle> task, long delayTicks, long periodTicks);

    // ------------------------------------------------------------------ off the regions

    /**
     * Runs {@code task} as soon as possible on a thread of the server's async scheduler, which belongs to no region:
     * suitable for blocking I/O, never for world, block or entity state.
     */
    TaskHandle runAsync(Plugin plugin, Runnable task);

    /** As {@link #runAsync} after {@code delay} of wall-clock time (not ticks). A negative delay throws. */
    TaskHandle runAsyncLater(Plugin plugin, Runnable task, Duration delay);

    /**
     * As {@link #runAsync} after {@code delay}, then every {@code period} (wall-clock time), until cancelled.
     * {@code period} must be positive.
     */
    TaskHandle runAsyncTimer(Plugin plugin, Consumer<TaskHandle> task, Duration delay, Duration period);

    // ------------------------------------------------------------------ ownership

    /**
     * Whether the calling thread is the region thread that owns {@code location}'s chunk right now, so the chunk's
     * blocks may be touched directly. False on async threads and on the global region.
     */
    boolean isOwnedByCurrentRegion(Location location);

    /** Whether the calling thread is the region thread that owns {@code entity} right now. */
    boolean isOwnedByCurrentRegion(Entity entity);

    /** Whether the calling thread is the global region's tick thread. */
    boolean isGlobalTickThread();

    /** The block-coordinate origin of a chunk, for the chunk overloads. */
    private static Location chunkOrigin(final World world, final int chunkX, final int chunkZ) {
        java.util.Objects.requireNonNull(world, "world");
        return new Location(world, (double) (chunkX << 4), 0.0, (double) (chunkZ << 4));
    }
}
