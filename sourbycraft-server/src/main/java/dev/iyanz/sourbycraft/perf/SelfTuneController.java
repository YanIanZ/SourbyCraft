package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.perf.knob.Knobs;
import dev.iyanz.sourbycraft.perf.sensor.Tier;
import dev.iyanz.sourbycraft.util.SourbyLogger;

/**
 * P7 Self-Tune Controller.
 *
 * <p>Listens to {@link dev.iyanz.sourbycraft.perf.sensor.PerfSensor} tier
 * transitions and mutates {@link Knobs} entries so the lag-machine
 * defences tighten as the server slips down the tier ladder. On
 * recovery the controller restores the operator-yml baseline so a
 * brief load spike doesn't permanently leave the server in
 * emergency-throttle mode.
 *
 * <p>Tier policy (cumulative; each tier inherits the prior tier's
 * tightening on top of operator defaults):
 *
 * <pre>
 *  GREEN      — restore operator yml baseline
 *  YELLOW     — keep operator defaults (no escalation yet)
 *  ORANGE     — projectile fan-out cap halved
 *  RED        — vehicle sweepers ON, projectile cap quartered
 *  EMERGENCY  — vehicle sweepers ON with tight limit (2), projectile cap = 1
 * </pre>
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
