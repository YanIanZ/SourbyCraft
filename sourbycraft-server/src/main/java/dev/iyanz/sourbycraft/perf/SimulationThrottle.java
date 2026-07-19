package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.core.PerWorldHolder;
import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.perf.sensor.Tier;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * SourbyCraft F-perfup3 — simulation-distance throttle (the invisible sibling of {@link ViewThrottle}).
 *
 * <p><b>Why this is the high-view-distance TPS lever.</b> {@code view-distance} decides how many
 * chunks a client is <em>sent</em> (rendered); {@code simulation-distance} decides how many chunks
 * around each player actually <em>tick</em> — entities, block/redstone/fluid ticks, random ticks and
 * mob-spawn attempts. Ticking scales with the sim radius <em>squared</em> per player: at sim 10 each
 * player ticks 21×21 = 441 chunks; at sim 4, 9×9 = 81 — an ~82% cut in per-player tick work. With many
 * players on a core-limited box that is the dominant, unbounded cost that the chunk-throughput knobs
 * (send/load/gen rate) cannot touch — those bound streaming, not ticking.
 *
 * <p><b>Invisible, so default-ON and engaged early.</b> Unlike {@link ViewThrottle} (changing
 * view-distance forces a client chunk reload = a visible flicker, hence opt-in / crisis-only), lowering
 * simulation-distance is entirely server-side: the client still renders the same far chunks, they just
 * stop ticking beyond the (shrunk) sim radius. Gameplay near a player is untouched — mobs still spawn
 * and tick within the reduced radius (4 chunks = 64 blocks around every player); only distant unattended
 * farms/redstone pause while the server is under load, and resume the moment it recovers. Because there
 * is nothing to flicker, this engages from YELLOW (not crisis-only) and recovers quickly.
 *
 * <p><b>Tier targets</b> (per world, floored at {@code minSimulationDistance}):
 * GREEN → original · YELLOW → min(original,7) · ORANGE → 5 · RED → 4 · EMERGENCY → floor.
 * Targets are absolute (not a per-cycle step) so a sudden EMERGENCY sheds the full tick load in one
 * cycle; recovery steps back up one chunk per healthy cycle to avoid re-loading a burst of tickets.
 *
 * <p><b>Folia.</b> Scheduled on the global-region scheduler (as {@link ViewThrottle} and the perf
 * sensor are); {@code World#setSimulationDistance} is a world-wide API call, Folia-safe on the global
 * region thread. Per-world originals live in a {@link PerWorldHolder} (evicted on world unload by the
 * shared cleanup listener). Reloadable: {@link SourbyCraftConfig#autoThrottleSim} is read each cycle,
 * so toggling it restores every world's original distance without a restart.
 */
public final class SimulationThrottle {

    /** Per-world original simulation distance, captured the first time we throttle it down. */
    private static final PerWorldHolder<Integer> originalSimDistance = new PerWorldHolder<>();

    private static volatile Object schedulerTask; // ScheduledTask handle
    private static Tier lastLoggedTier = null;
    /** Consecutive non-degraded cycles; recovery steps up only after a couple, to avoid ticket churn. */
    private static int healthyCycles = 0;
    private static final int RECOVERY_HOLD_CYCLES = 2;

    private SimulationThrottle() {}

    public static void register(Plugin plugin) {
        // Always schedule (even if currently disabled) so a /sourbycraft reload toggle takes effect
        // without a restart — the tick reads the live flag and restores originals while off.
        schedulerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
            task -> tick(),
            60L,  // initial delay (ticks)
            60L   // period (ticks) — 3 s; invisible, so it may react faster than ViewThrottle's 5 s
        );
        SourbyLogger.info("SimulationThrottle: registered (Folia global-region) — enabled="
            + SourbyCraftConfig.autoThrottleSim + " min-simulation-distance="
            + Math.max(2, Math.min(32, SourbyCraftConfig.minSimulationDistance))
            + " (auto-throttle-sim is reloadable via /sourbycraft reload)");
    }

    private static void tick() {
        // Reloadable gate: when off, restore any world we throttled down, then idle.
        if (!SourbyCraftConfig.autoThrottleSim) {
            boolean restored = false;
            for (World world : Bukkit.getWorlds()) {
                Integer original = originalSimDistance.get(world.getName());
                if (original != null) {
                    if (world.getSimulationDistance() != original) { safeSet(world, original); restored = true; }
                    originalSimDistance.remove(world.getName());
                }
            }
            healthyCycles = 0;
            if (restored) SourbyLogger.debug("SimulationThrottle disabled — restored original simulation distances");
            return;
        }

        final Tier tier = PerfSensor.currentTier();
        final int floor = Math.max(2, Math.min(32, SourbyCraftConfig.minSimulationDistance));
        final boolean degraded = tier.isWorseThan(Tier.GREEN); // engage from YELLOW down — it's invisible

        if (degraded) healthyCycles = 0;
        else if (healthyCycles < RECOVERY_HOLD_CYCLES) healthyCycles++;
        final boolean mayRecover = !degraded && healthyCycles >= RECOVERY_HOLD_CYCLES;

        int changed = 0, minT = Integer.MAX_VALUE, maxT = Integer.MIN_VALUE;
        boolean recovering = false;

        for (World world : Bukkit.getWorlds()) {
            final String name = world.getName();
            final int current = world.getSimulationDistance();
            final int original = originalSimDistance.computeIfAbsent(name, k -> current);

            if (degraded) {
                // Absolute per-tier target (shed the full tick load at once), floored, never above original.
                final int target = Math.max(floor, Math.min(original, targetFor(tier, original, floor)));
                if (target != current) { safeSet(world, target); changed++; minT = Math.min(minT, target); maxT = Math.max(maxT, target); }
            } else if (mayRecover && current < original) {
                // Healthy + stable → step +1 toward original (gentle, avoids a ticket-load burst).
                final int target = Math.min(original, current + 1);
                safeSet(world, target); changed++; recovering = true; minT = Math.min(minT, target); maxT = Math.max(maxT, target);
                if (target >= original) originalSimDistance.remove(name); // fully recovered — respect manual changes
            }
        }

        if (changed > 0 && tier != lastLoggedTier) {
            final String t = (minT == maxT) ? Integer.toString(minT) : (minT + "-" + maxT);
            SourbyLogger.debug("simulation distance -> " + t + " (tier=" + tier
                + (recovering ? ", recovering" : "") + ") [" + changed + " world" + (changed == 1 ? "" : "s") + "]");
            lastLoggedTier = tier;
        }
    }

    /** Absolute sim-distance target for a load tier (before flooring / original clamp). */
    private static int targetFor(final Tier tier, final int original, final int floor) {
        return switch (tier) {
            case GREEN -> original;
            case YELLOW -> Math.min(original, 7);
            case ORANGE -> 5;
            case RED -> 4;
            case EMERGENCY -> floor;
        };
    }

    private static void safeSet(final World world, final int distance) {
        try { world.setSimulationDistance(distance); }
        catch (Throwable t) { SourbyLogger.debug("SimulationThrottle: setSimulationDistance(" + distance + ") failed for " + world.getName() + ": " + t); }
    }
}
