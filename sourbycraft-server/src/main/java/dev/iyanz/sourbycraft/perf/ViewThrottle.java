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
 * <p>Logging is collapsed to one debug summary line per tier change (e.g.
 * {@code view distance -> 4 (tier=RED) [3 worlds]}) rather than one line per world per step,
 * so a multi-world tier transition no longer floods the console. Lines are debug-level (surface
 * with {@code -Dsourbycraft.debug=true}); zero scheduler cost when
 * {@link SourbyCraftConfig#autoThrottleView} is false.
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

    /**
     * Tier for which we last emitted a collapsed summary line. A cycle only logs when the live tier
     * differs from this, so a steady tier (including a steady EMERGENCY) never re-logs per step —
     * one line per tier change, not one per world per step.
     */
    private static Tier lastLoggedTier = null;

    private ViewThrottle() {}

    /**
     * Register the throttle task on the Folia global-region scheduler.
     * No-ops (with a log line) when {@link SourbyCraftConfig#autoThrottleView} is false.
     */
    public static void register(Plugin plugin) {
        if (!SourbyCraftConfig.autoThrottleView) {
            SourbyLogger.info("ViewThrottle: disabled (network.auto-throttle-view=false)");
            return;
        }
        int minDist = Math.max(2, Math.min(32, SourbyCraftConfig.minViewDistance));
        schedulerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
            task -> tick(),
            100L, // initial delay (ticks)
            100L  // period (ticks)
        );
        SourbyLogger.info("ViewThrottle: registered (Folia global-region) — min-view-distance=" + minDist);
    }

    private static void tick() {
        Tier tier = PerfSensor.currentTier();
        int minDist = Math.max(2, Math.min(32, SourbyCraftConfig.minViewDistance));

        // Collapse logging: aggregate this cycle's adjustments and emit at most ONE summary line,
        // and only when the tier changed since we last logged. This turns a per-world-per-step
        // flood into a single "view distance -> N (tier=X) [K worlds]" line per tier change.
        int changedWorlds = 0;
        int minTarget = Integer.MAX_VALUE;
        int maxTarget = Integer.MIN_VALUE;
        boolean recovering = false;

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
                    changedWorlds++;
                    minTarget = Math.min(minTarget, target);
                    maxTarget = Math.max(maxTarget, target);
                }
            } else if (current < original) {
                // Healthy tier → step +1 toward original.
                int target = Math.min(original, current + 1);
                world.setViewDistance(target);
                changedWorlds++;
                recovering = true;
                minTarget = Math.min(minTarget, target);
                maxTarget = Math.max(maxTarget, target);
                if (target >= original) {
                    // Fully recovered — forget the saved original so a manual /view change is respected.
                    originalViewDistance.remove(name);
                }
            }
        }

        // One collapsed summary per tier change (debug-level, so quiet by default).
        if (changedWorlds > 0 && tier != lastLoggedTier) {
            String target = (minTarget == maxTarget) ? Integer.toString(minTarget) : (minTarget + "-" + maxTarget);
            SourbyLogger.debug("view distance -> " + target + " (tier=" + tier
                + (recovering ? ", recovering" : "") + ") [" + changedWorlds + " world"
                + (changedWorlds == 1 ? "" : "s") + "]");
            lastLoggedTier = tier;
        }
    }
}
