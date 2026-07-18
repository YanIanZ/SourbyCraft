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
 * <p><b>Default OFF (opt-in).</b> Changing the view distance at runtime forces clients to reload
 * their chunk set — a visible screen flicker — and it is the only client-visible perf actuator (the
 * AI throttle, Kaiiju entity limiter, projectile caps and save-suppress all act invisibly). It is
 * gated on {@link SourbyCraftConfig#autoThrottleView} (default false); when off, no task is scheduled.
 *
 * <p>When enabled, every 100 ticks it reads the live {@link PerfSensor} tier and adjusts each world's
 * view distance:
 * <ul>
 *   <li>Crisis tier (RED, EMERGENCY only — NOT ORANGE): step down one chunk per cycle, floored at
 *       {@code max(2, min(32, SourbyCraftConfig.minViewDistance))}.</li>
 *   <li>Otherwise, after a sustained non-crisis run (anti-flicker hysteresis): step back up one chunk
 *       toward the world's original view distance (captured on first touch).</li>
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

    /**
     * Consecutive non-crisis cycles (tier at/above ORANGE — i.e. NOT RED/EMERGENCY) seen so far.
     * RECOVERY (stepping view distance back up) only begins after {@link #RECOVERY_HOLD_CYCLES} of
     * these in a row, and any single crisis cycle resets it to 0. This is the anti-flicker guard:
     * without it, a server whose TPS flaps near a threshold dropped the view distance then stepped it
     * back up every 5 s, so the outer ring of chunks the clients hold unloaded and reloaded on each
     * swing — a visible screen flicker. Asymmetric on purpose: drop FAST to protect TPS, restore only
     * once the server has been stable for a while, so a brief recovery never reverses the drop.
     */
    private static int consecutiveHealthyCycles = 0;

    /** ~30 s of sustained non-crisis tier (at the 100-tick / 5 s cadence) before view distance recovers. */
    private static final int RECOVERY_HOLD_CYCLES = 6;

    private ViewThrottle() {}

    /**
     * Register the throttle task on the Folia global-region scheduler.
     * No-ops (with a log line) when {@link SourbyCraftConfig#autoThrottleView} is false.
     */
    public static void register(Plugin plugin) {
        // Always schedule the tick, even when auto-throttle-view is currently off, so that toggling
        // network.auto-throttle-view via /sourbycraft reload takes effect WITHOUT a restart: tick()
        // reads the live flag each cycle and no-ops (restoring any throttled worlds) while it is off.
        // A 5 s no-op tick is free; this is the price of reload-live behaviour.
        int minDist = Math.max(2, Math.min(32, SourbyCraftConfig.minViewDistance));
        schedulerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
            task -> tick(),
            100L, // initial delay (ticks)
            100L  // period (ticks)
        );
        SourbyLogger.info("ViewThrottle: registered (Folia global-region) — enabled="
            + SourbyCraftConfig.autoThrottleView + " min-view-distance=" + minDist
            + " (auto-throttle-view is reloadable via /sourbycraft reload)");
    }

    private static void tick() {
        // Reloadable gate: when disabled (default, or turned off via /sourbycraft reload), restore any
        // worlds we previously throttled DOWN back to their captured original view distance, then idle.
        // Reading the live config field here is what makes the toggle take effect without a restart.
        if (!SourbyCraftConfig.autoThrottleView) {
            boolean restoredAny = false;
            for (World world : Bukkit.getWorlds()) {
                Integer original = originalViewDistance.get(world.getName());
                if (original != null) {
                    if (world.getViewDistance() != original) {
                        world.setViewDistance(original);
                        restoredAny = true;
                    }
                    originalViewDistance.remove(world.getName());
                }
            }
            consecutiveHealthyCycles = 0;
            if (restoredAny) SourbyLogger.debug("ViewThrottle disabled — restored original view distances");
            return;
        }
        Tier tier = PerfSensor.currentTier();
        int minDist = Math.max(2, Math.min(32, SourbyCraftConfig.minViewDistance));
        // Engage only at RED/EMERGENCY (genuine crisis), not ORANGE. Runtime view-distance changes
        // flicker the client (chunk reload), so an opted-in operator only pays that cost when the
        // server is actually in trouble — mild ORANGE load is handled by the invisible actuators.
        final boolean degraded = tier.isWorseThan(Tier.ORANGE); // RED/EMERGENCY only

        // Anti-blink hysteresis. Track a run of healthy cycles; a degraded cycle resets it. Recovery
        // (stepping the view distance back up) is gated on a sustained run, so tier flapping near a
        // threshold can only DROP the view distance (protective, monotonic) and never yo-yo it — the
        // chunk ring at the edge stops unloading/reloading.
        if (degraded) {
            consecutiveHealthyCycles = 0;
        } else if (consecutiveHealthyCycles < RECOVERY_HOLD_CYCLES) {
            consecutiveHealthyCycles++;
        }
        final boolean mayRecover = !degraded && consecutiveHealthyCycles >= RECOVERY_HOLD_CYCLES;

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

            if (degraded) {
                // Degraded/worst tier → step down one chunk, floor at minDist.
                int target = Math.max(minDist, current - 1);
                if (target != current) {
                    world.setViewDistance(target);
                    changedWorlds++;
                    minTarget = Math.min(minTarget, target);
                    maxTarget = Math.max(maxTarget, target);
                }
            } else if (mayRecover && current < original) {
                // Healthy AND stable for RECOVERY_HOLD_CYCLES → step +1 toward original.
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
