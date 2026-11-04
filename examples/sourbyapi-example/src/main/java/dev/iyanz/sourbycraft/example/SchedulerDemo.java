package dev.iyanz.sourbycraft.example;

import dev.iyanz.sourbycraft.api.scheduler.AuroraScheduler;
import dev.iyanz.sourbycraft.api.scheduler.TaskHandle;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code SourbyAPI.scheduler()}: run on the region of a location ({@code runAt}), on an entity's scheduler
 * ({@code runFor}), on the global region and off every region ({@code runAsync}).
 */
final class SchedulerDemo {

    private final Plugin plugin;
    private final AuroraScheduler scheduler;

    SchedulerDemo(final Plugin plugin, final AuroraScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    /**
     * A delayed, then repeating, global task that cancels itself. The global region is for world-independent state only;
     * a log line is fine, a block or an entity is not.
     */
    void startHeartbeat() {
        final AtomicInteger beats = new AtomicInteger();
        this.scheduler.runGlobalTimer(this.plugin, handle -> {
            this.plugin.getLogger().info("global heartbeat " + beats.incrementAndGet() + " of 3");
            if (beats.get() >= 3) handle.cancel(); // a repeating task is handed its own handle to stop itself
        }, 20L * 10, 20L * 10);
    }

    /** {@code runAt} on the sender's location, then {@code runForTimer} on the sender (a player) three times. */
    void tickDemo(final CommandSender sender) {
        if (!(sender instanceof Player player)) {
            this.scheduler.runGlobalLater(this.plugin, () -> sender.sendMessage("global task ran one second later"), 20L);
            sender.sendMessage("scheduled a global task (the console owns no location or entity)");
            return;
        }
        // The command already runs on the player's region, so this location is owned by the current thread.
        final Location where = player.getLocation();
        sender.sendMessage("owned by this thread: " + this.scheduler.isOwnedByCurrentRegion(where));

        // runAt: the block is read on the region that owns it, whatever thread asked.
        this.scheduler.runAt(this.plugin, where, () ->
            player.sendMessage("block under you, read by its own region: " + where.clone().subtract(0, 1, 0).getBlock().getType()));

        // runForTimer: follows the player across regions and worlds, stops itself, and stops if the player is removed.
        final AtomicInteger runs = new AtomicInteger();
        final TaskHandle handle = this.scheduler.runForTimer(this.plugin, player, task -> {
            player.sendMessage("entity timer run " + runs.incrementAndGet() + " of 3");
            if (runs.get() >= 3) task.cancel();
        }, 20L, 20L, () -> this.plugin.getLogger().info("player left before the timer finished"));
        sender.sendMessage("entity timer scheduled, cancelled=" + handle.isCancelled());
    }

    /**
     * {@code runAsync} for blocking work, then back to the sender's own thread with the answer ({@link Replies}). An async
     * task belongs to no region and must not touch world, block or entity state.
     */
    void asyncDemo(final CommandSender sender) {
        this.scheduler.runAsync(this.plugin, () -> {
            final long started = System.nanoTime();
            // Stand-in for blocking I/O (a database, a file, a web call).
            final long checksum = java.util.stream.LongStream.range(0, 5_000_000L).sum();
            final long micros = (System.nanoTime() - started) / 1_000L;
            Replies.send(this.plugin, this.scheduler, sender, "async work done (" + checksum + ") in " + micros + " us");
        });
        this.scheduler.runAsyncLater(this.plugin, () -> this.plugin.getLogger().info("async task, wall-clock delay"),
            Duration.ofSeconds(2));
    }
}
