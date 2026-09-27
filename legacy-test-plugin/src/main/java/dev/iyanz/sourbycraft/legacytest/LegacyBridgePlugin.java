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

        // World access from a legacy "sync" task. The bridge runs it on the global region, which
        // owns no chunks; whatever the base does is logged, and a thrown ownership violation is
        // what the bridge counts toward quarantine.
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
