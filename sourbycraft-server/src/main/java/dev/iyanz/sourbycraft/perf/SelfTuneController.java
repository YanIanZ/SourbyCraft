package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.perf.knob.Knobs;
import dev.iyanz.sourbycraft.perf.sensor.Tier;
import dev.iyanz.sourbycraft.util.SourbyLogger;

/**
 * Self-Tune Controller (Folia F2b port).
 *
 * <p>Listens to {@link dev.iyanz.sourbycraft.perf.sensor.PerfSensor} tier
 * transitions and mutates {@link Knobs} entries so the lag-machine
 * defences tighten as the server slips down the tier ladder. On
 * recovery the controller restores the operator-yml baseline so a
 * brief load spike doesn't permanently leave the server in
 * emergency-throttle mode.
 *
 * <p>This is the aggregate-model controller: {@link PerfSensor} computes one
 * server-wide {@link Tier} (not per-region), and this controller sets the
 * server-wide knob values in response. It only <b>sets knob values</b>; the
 * behaviour that <b>reads</b> those knobs (the actuators) is the F2c layer.
 *
 * <p>Tier policy (cumulative; each tier inherits the prior tier's
 * tightening on top of operator defaults):
 *
 * <pre>
 *  GREEN      — restore operator yml baseline
 *  YELLOW     — keep operator defaults (no escalation yet)
 *  ORANGE     — projectile fan-out cap halved, AI throttle at 64 blocks / every 2t
 *  RED        — vehicle sweepers ON, projectile cap quartered, AI throttle 48 / 4t
 *  EMERGENCY  — vehicle sweepers ON tight (limit 2), projectile cap = 1, AI throttle 32 / 8t
 * </pre>
 *
 * <p><b>Thread-safety.</b> {@link #onTierChange} is invoked from {@code PerfSensor.transition()}
 * on the Folia global-region scheduler thread (the sole caller). Knob {@code set(...)} calls are
 * individually thread-safe (volatile-backed). {@link #captureBaselineIfNeeded} is
 * {@code synchronized} so the one-time baseline snapshot is captured exactly once even if the
 * method were ever called concurrently.
 */
public final class SelfTuneController {

    /** Operator-yml baseline; captured the first time onTierChange runs. */
    private static volatile int baselineProjectilePerTick = -1;
    private static volatile int baselineProjectilePerProjectile = -1;
    private static volatile boolean baselineRemoveMinecarts;
    private static volatile int baselineMinecartsLimit = -1;
    private static volatile boolean baselineRemoveBoats;
    private static volatile int baselineBoatsLimit = -1;
    private static volatile int baselineAiThrottleDistance = -1;
    private static volatile int baselineAiThrottleInterval = -1;
    private static volatile boolean enabled = true;

    private SelfTuneController() {}

    public static void setEnabled(final boolean v) {
        enabled = v;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Invoked by {@code PerfSensor.transition()} on every tier change. */
    public static void onTierChange(final Tier oldTier, final Tier newTier) {
        if (!enabled) return;
        captureBaselineIfNeeded();
        applyTier(newTier);
        SourbyLogger.info("self-tune: applied policy for tier " + newTier);
        // F2e: push the freshly-set knob values into the Luminol/Pufferfish base config so the
        // base's already-Folia-safe tick code actually enforces them in-tick (projectile chunk-load
        // caps, inactive-goal-selector throttle). Runs on the global-region scheduler thread.
        KnobEnforcer.enforceAll();
        // Log the resulting knob snapshot so operators can see exactly what changed.
        Knobs.logLoaded("tier-" + newTier);
    }

    private static synchronized void captureBaselineIfNeeded() {
        if (baselineProjectilePerTick != -1) return;
        baselineProjectilePerTick = Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.get();
        baselineProjectilePerProjectile = Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.get();
        baselineRemoveMinecarts = Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.get();
        baselineMinecartsLimit = Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.get();
        baselineRemoveBoats = Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.get();
        baselineBoatsLimit = Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.get();
        baselineAiThrottleDistance = Knobs.AI_THROTTLE_BEYOND_DISTANCE.get();
        baselineAiThrottleInterval = Knobs.AI_THROTTLE_TICK_INTERVAL.get();
    }

    private static void applyTier(final Tier tier) {
        switch (tier) {
            case GREEN -> restoreBaseline();
            case YELLOW -> restoreBaseline();
            case ORANGE -> {
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(Math.max(1, baselineProjectilePerTick / 2));
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(baselineProjectilePerProjectile);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(baselineRemoveMinecarts);
                Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.set(baselineMinecartsLimit);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(baselineRemoveBoats);
                Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.set(baselineBoatsLimit);
                Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(64);
                Knobs.AI_THROTTLE_TICK_INTERVAL.set(2);
            }
            case RED -> {
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(Math.max(1, baselineProjectilePerTick / 4));
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(Math.max(1, baselineProjectilePerProjectile / 2));
                Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(true);
                Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.set(Math.max(1, baselineMinecartsLimit / 2));
                Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(true);
                Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.set(Math.max(1, baselineBoatsLimit / 2));
                Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(48);
                Knobs.AI_THROTTLE_TICK_INTERVAL.set(4);
            }
            case EMERGENCY -> {
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(1);
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(1);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(true);
                Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.set(2);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(true);
                Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.set(2);
                Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(32);
                Knobs.AI_THROTTLE_TICK_INTERVAL.set(8);
            }
        }
    }

    private static void restoreBaseline() {
        if (baselineProjectilePerTick == -1) return;
        Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(baselineProjectilePerTick);
        Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(baselineProjectilePerProjectile);
        Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(baselineRemoveMinecarts);
        Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.set(baselineMinecartsLimit);
        Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(baselineRemoveBoats);
        Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.set(baselineBoatsLimit);
        Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(baselineAiThrottleDistance);
        Knobs.AI_THROTTLE_TICK_INTERVAL.set(baselineAiThrottleInterval);
    }
}
