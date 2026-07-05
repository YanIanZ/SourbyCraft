package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.perf.sensor.Tier;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

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
 */
public final class ViewThrottle {

    /**
     * Per-world original view distance captured the first time we encounter a world.
     * Used as the recovery ceiling when the server returns to a healthy tier.
     */
    private static final Map<String, Integer> originalViewDistance = new ConcurrentHashMap<>();

    private ViewThrottle() {}

    /**
     * Register the throttle task. Called from {@code SWPlugin.onEnable}.
     * No-ops (with a log line) when {@link SourbyCraftConfig#autoThrottleView} is false.
     */
    public static void register(Plugin plugin) {
        if (!SourbyCraftConfig.autoThrottleView) {
            SourbyLogger.info("[SourbyCraft] ViewThrottle: disabled (network.auto-throttle-view=false)");
            return;
        }
        int minDist = Math.max(2, Math.min(32, SourbyCraftConfig.minViewDistance));
        Bukkit.getScheduler().runTaskTimer(plugin, ViewThrottle::tick, 100L, 100L);
        // SWM island servers reuse world names after resets — a stale entry would cap the
        // recreated world's recovery ceiling at the old degraded distance.
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onWorldUnload(org.bukkit.event.world.WorldUnloadEvent e) {
                originalViewDistance.remove(e.getWorld().getName());
            }
        }, plugin);
        SourbyLogger.info("[SourbyCraft] ViewThrottle: registered — min-view-distance=" + minDist);
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
                        + " " + current + "→" + target + " (tier=" + tier + ")");
                }
            } else if (current < original) {
                // Healthy tier → step +1 toward original.
                int target = Math.min(original, current + 1);
                world.setViewDistance(target);
                SourbyLogger.info("[SourbyCraft] view distance world=" + name
                    + " " + current + "→" + target + " (tier=" + tier + ", recovering)");
                if (target >= original) {
                    // Fully recovered — forget the saved original so a manual /view change is respected.
                    originalViewDistance.remove(name);
                }
            }
        }
    }
}
