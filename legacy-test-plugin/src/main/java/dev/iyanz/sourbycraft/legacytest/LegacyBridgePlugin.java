package dev.iyanz.sourbycraft.legacytest;

import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Exercises the Bukkit scheduler the way a pre-Folia plugin does, and logs one marker per outcome
 * so CI can assert what the Aurora Bridge did with each call.
 */
public final class LegacyBridgePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        this.getLogger().info("LEGACY_BRIDGE_ENABLED");
        final BukkitScheduler scheduler = this.getServer().getScheduler();

        scheduler.runTask(this, () -> this.getLogger().info(
            "LEGACY_BRIDGE_SYNC_OK thread=" + Thread.currentThread().getName()));

        scheduler.runTaskAsynchronously(this, () -> this.getLogger().info(
            "LEGACY_BRIDGE_ASYNC_OK thread=" + Thread.currentThread().getName()));

        // A repeating task that cancels itself through BukkitTask#cancel, which must reach the
        // bridge rather than Folia's unsupported CraftScheduler path.
        final AtomicInteger runs = new AtomicInteger();
        final BukkitTask[] timer = new BukkitTask[1];
        timer[0] = scheduler.runTaskTimer(this, () -> {
            if (runs.incrementAndGet() == 3) {
                this.getLogger().info("LEGACY_BRIDGE_TIMER_OK runs=3");
                timer[0].cancel();
            } else if (runs.get() > 3) {
                this.getLogger().severe("LEGACY_BRIDGE_TIMER_RAN_AFTER_CANCEL runs=" + runs.get());
            }
        }, 1L, 1L);

        // isQueued must see a bridged task (plugins use it to avoid double-scheduling).
        final BukkitTask later = scheduler.runTaskLater(this, () -> { }, 40L);
        this.getLogger().info(scheduler.isQueued(later.getTaskId())
            ? "LEGACY_BRIDGE_QUEUED_OK" : "LEGACY_BRIDGE_QUEUED_MISSING");
        this.getLogger().info(scheduler.getPendingTasks().stream().anyMatch(t -> t.getTaskId() == later.getTaskId())
            ? "LEGACY_BRIDGE_PENDING_OK" : "LEGACY_BRIDGE_PENDING_MISSING");

        // A legacy runTask called from region context, the way an event handler or a player
        // command calls it. The chunk-load callback runs on the region owning the chunk; with
        // aurora.bridge.sync-route = "caller-region" the task runs on that region and may read
        // the block there.
        final org.bukkit.World world = this.getServer().getWorlds().get(0);
        world.getChunkAtAsync(0, 0).thenAccept(chunk -> {
            world.addPluginChunkTicket(0, 0, this);
            scheduler.runTask(this, () -> {
                try {
                    final String type = world.getBlockAt(8, 0, 8).getType().name();
                    this.getLogger().info("LEGACY_BRIDGE_REGION_SYNC_OK thread=" + Thread.currentThread().getName()
                        + " block=" + type);
                } catch (final RuntimeException refused) {
                    this.getLogger().info("LEGACY_BRIDGE_REGION_SYNC_REFUSED " + refused.getClass().getSimpleName()
                        + " thread=" + Thread.currentThread().getName());
                    throw refused;
                }
            });
        });

        // World access from a legacy "sync" task scheduled outside any region (onEnable). The
        // bridge runs it on the global region, which owns no chunks; whatever the base does is
        // logged, and a thrown ownership violation is what the bridge counts toward quarantine.
        scheduler.runTaskLater(this, () -> {
            try {
                final String type = this.getServer().getWorlds().get(0).getBlockAt(0, 64, 0).getType().name();
                this.getLogger().info("LEGACY_BRIDGE_WORLD_ACCESS_ALLOWED block=" + type);
            } catch (final RuntimeException refused) {
                this.getLogger().info("LEGACY_BRIDGE_WORLD_ACCESS_REFUSED " + refused.getClass().getSimpleName());
                throw refused;
            }
        }, 5L);
    }
}
