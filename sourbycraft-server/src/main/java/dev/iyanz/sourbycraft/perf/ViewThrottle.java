package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.perf.sensor.Tier;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import dev.iyanz.sourbycraft.core.PerWorldHolder;

/**
 * SourbyCraft S5 — lightweight view-distance throttle engine.
 *
 * <p>Every 100 ticks reads the live {@link PerfSensor} tier and adjusts each world's
 * view distance:
 * <ul>
 *   <li>Degraded / worst tier (ORANGE, RED, EMERGENCY): step down one chunk per cycle,
 *       floored at {@code max(2, min(32, SourbyCraftConfig.minViewDistance))}.</li>
 *   <li>Healthy tier (GREEN, YELLOW): step back up one chunk toward the world's original
 *       view distance (captured on first touch).</li>
 * </ul>
 *
 * <p>One INFO line is emitted per actual change to avoid log spam.
 * Zero scheduler cost when {@link SourbyCraftConfig#autoThrottleView} is false.
 *
 * <p><b>Folia adaptation (F2c).</b> The Paper tag scheduled {@link #tick()} via
 * {@code Bukkit.getScheduler().runTaskTimer(...)} — the global main-thread scheduler,
 * absent on Folia. On Folia the tick is scheduled on the global-region scheduler
 * ({@code Bukkit.getGlobalRegionScheduler().runAtFixedRate(...)}, the same handle the
 * perf sensor uses). {@code World#getViewDistance()}/{@code setViewDistance()} are
 * world-wide API calls; running them on the global region thread is the Folia-safe
 * placement. The per-world original-distance map is a {@link PerWorldHolder}
 * ({@link java.util.concurrent.ConcurrentHashMap} backed). The {@link SourbyCraftConfig#autoThrottleView}
 * guard is respected — when off, no task is scheduled (zero cost).
 */
public final class ViewThrottle {

    /**
     * Per-world original view distance captured the first time we encounter a world.
     * Used as the recovery ceiling when the server returns to a healthy tier.
     * PerWorldHolder evicts on WorldUnloadEvent (centralized listener).
     */
    private static final PerWorldHolder<Integer> originalViewDistance = new PerWorldHolder<>();

    private static volatile Object schedulerTask; // io.papermc.paper.threadedregions.scheduler.ScheduledTask

    private ViewThrottle() {}

    /**
     * Register the throttle task on the Folia global-region scheduler.
     * No-ops (with a log line) when {@link SourbyCraftConfig#autoThrottleView} is false.
     */
    public static void register(Plugin plugin) {
        if (!SourbyCraftConfig.autoThrottleView) {
            SourbyLogger.info("[SourbyCraft] ViewThrottle: disabled (network.auto-throttle-view=false)");
            return;
        }
        int minDist = Math.max(2, Math.min(32, SourbyCraftConfig.minViewDistance));
        schedulerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
            task -> tick(),
            100L, // initial delay (ticks)
            100L  // period (ticks)
        );
        SourbyLogger.info("[SourbyCraft] ViewThrottle: registered (Folia global-region) — min-view-distance=" + minDist);
    }

    private static void tick() {
        Tier tier = PerfSensor.currentTier();
        int minDist = Math.max(2, Math.min(32, SourbyCraftConfig.minViewDistance));

        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            int current = world.getViewDistance();

            // Capture original on first encounter (before any of our changes).
            int original = originalViewDistance.computeIfAbsent(name, k -> current);

            if (tier.isWorseThan(Tier.YELLOW)) {
                // Degraded/worst tier → step down one chunk, floor at minDist.
                int target = Math.max(minDist, current - 1);
                if (target != current) {
                    world.setViewDistance(target);
                    SourbyLogger.info("[SourbyCraft] view distance world=" + name
                        + " " + current + "->" + target + " (tier=" + tier + ")");
                }
            } else if (current < original) {
                // Healthy tier → step +1 toward original.
                int target = Math.min(original, current + 1);
                world.setViewDistance(target);
                SourbyLogger.info("[SourbyCraft] view distance world=" + name
                    + " " + current + "->" + target + " (tier=" + tier + ", recovering)");
                if (target >= original) {
                    // Fully recovered — forget the saved original so a manual /view change is respected.
                    originalViewDistance.remove(name);
                }
            }
        }
    }
}
