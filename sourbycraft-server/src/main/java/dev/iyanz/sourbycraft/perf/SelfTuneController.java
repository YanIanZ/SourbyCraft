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
    // F-perfup: load-gated behavior-affecting knobs. Baseline captured on first tier change so GREEN
    // restores the operator/base default exactly (no regression); ORANGE and below tighten.
    private static volatile boolean baselineEntityLimiterEnabled;
    private static volatile boolean baselineGoalSelectorInactive = true;
    // NMS-scalar deep-enforcement: base magnitudes captured on first tier change so GREEN/YELLOW
    // restore them exactly (scale 1.0, interval 20 = no regression); ORANGE and below tighten.
    private static volatile double baselineEntityLimiterScale = 1.0D;
    private static volatile int baselineGoalSelectorInterval = -1;
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
        // F2e: push the freshly-set knob values into the Luminol/Pufferfish base config so the
        // base's already-Folia-safe tick code actually enforces them in-tick (projectile chunk-load
        // caps, inactive-goal-selector throttle). Runs on the global-region scheduler thread.
        KnobEnforcer.enforceAll();
        // Log the resulting knob snapshot, honestly split into ENFORCED vs set-only. A blanket
        // Knobs.logLoaded() dump would advertise every knob as if applied — but only the projectile
        // caps and the AI-throttle gate are actually enforced by KnobEnforcer; the rest
        // (entity-tick-rate, snowball/firework save-suppression, excess minecart/boat sweepers) are
        // set here but have no in-tick actuator on the current base (see KnobEnforcer's "NOT bridged"
        // note), so we mark them plainly rather than claim enforcement that never happens.
        logTierPolicy(newTier);
    }

    /**
     * Honest per-tier knob log: reports the ENFORCED knobs (real in-tick effect via
     * {@link KnobEnforcer}) separately from the set-only knobs (mutated on tier change but with no
     * actuator on the current base — documented in {@link KnobEnforcer}). Keeps operators from
     * reading the tier line as "all of these are now applied".
     */
    private static void logTierPolicy(final Tier tier) {
        boolean aiThrottled = Knobs.AI_THROTTLE_BEYOND_DISTANCE.get() > 0
            && Knobs.AI_THROTTLE_TICK_INTERVAL.get() > 1;
        boolean goalSelector = aiThrottled || Knobs.GOAL_SELECTOR_INACTIVE_TICK_ENABLED.get();
        SourbyLogger.info("self-tune [tier-" + tier + "] ENFORCED:"
            + " projectile-loads/tick=" + Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.get()
            + " projectile-loads/projectile=" + Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.get()
            + " ai-distance-throttle=" + (aiThrottled ? "on" : "off")
            + " (dist=" + Knobs.AI_THROTTLE_BEYOND_DISTANCE.get()
            + " interval=" + Knobs.AI_THROTTLE_TICK_INTERVAL.get() + ")"
            + " goal-selector-inactive-throttle=" + (goalSelector ? "on" : "off")
            + " goal-selector-interval=" + Knobs.GOAL_SELECTOR_INACTIVE_TICK_INTERVAL.get()
            + " entity-limiter=" + (Knobs.KAIIJU_ENTITY_LIMITER_ENABLED.get() ? "on" : "off")
            + " entity-limiter-scale=" + Knobs.KAIIJU_ENTITY_LIMITER_SCALE.get()
            + " disable-saving-snowballs=" + Knobs.LAG_MACHINE_DISABLE_SAVING_SNOWBALLS.get()
            + " disable-saving-fireworks=" + Knobs.LAG_MACHINE_DISABLE_SAVING_FIREWORKS.get());
        // The knobs below are RESERVED: config surface exists but has no in-tick actuator on the
        // current base, so they intentionally do nothing (kept for compatibility / future wiring, and
        // to avoid a config-breaking removal). Logged honestly so nobody reads them as "applied".
        SourbyLogger.debug("self-tune [tier-" + tier + "] reserved (no in-tick actuator — no effect):"
            + " entity-tick-rate=" + Knobs.ENTITY_TICK_RATE.get()
            + " remove-excess-minecarts=" + Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.get()
            + " (limit=" + Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.get() + ")"
            + " remove-excess-boats=" + Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.get()
            + " (limit=" + Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.get() + ")");
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
        // F-perfup: snapshot the operator/base defaults so GREEN/YELLOW restore them exactly.
        baselineEntityLimiterEnabled = Knobs.KAIIJU_ENTITY_LIMITER_ENABLED.get();
        baselineGoalSelectorInactive = Knobs.GOAL_SELECTOR_INACTIVE_TICK_ENABLED.get();
        // NMS-scalar deep-enforcement: snapshot the base magnitudes (scale 1.0, interval 20).
        baselineEntityLimiterScale = Knobs.KAIIJU_ENTITY_LIMITER_SCALE.get();
        baselineGoalSelectorInterval = Knobs.GOAL_SELECTOR_INACTIVE_TICK_INTERVAL.get();
    }

    private static void applyTier(final Tier tier) {
        switch (tier) {
            case GREEN -> restoreBaseline();
            case YELLOW -> {
                // r26: YELLOW was a no-op (restoreBaseline), so nothing happened until TPS < ORANGE
                // — the operator's "perf tidak bekerja saat tps down". YELLOW now applies the
                // LIGHTEST real relief: distant-mob AI throttling + the inactive goal-selector
                // throttle. Both only affect mobs far from players / already inactive, so gameplay
                // near players is untouched; everything else stays at the operator baseline.
                restoreBaseline();
                Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(64); // harsher than the GREEN baseline (80/3)
                Knobs.AI_THROTTLE_TICK_INTERVAL.set(3);
                Knobs.GOAL_SELECTOR_INACTIVE_TICK_ENABLED.set(true);
                Knobs.GOAL_SELECTOR_INACTIVE_TICK_INTERVAL.set(25);
            }
            case ORANGE -> {
                // Baseline 0 = unlimited: divide-down would turn it into the HARSHEST cap (1).
                // Substitute per-tier absolute defaults instead.
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(baselineProjectilePerTick <= 0 ? 10 : Math.max(1, baselineProjectilePerTick / 2));
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(baselineProjectilePerProjectile);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(baselineRemoveMinecarts);
                Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.set(baselineMinecartsLimit);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(baselineRemoveBoats);
                Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.set(baselineBoatsLimit);
                Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(48);
                Knobs.AI_THROTTLE_TICK_INTERVAL.set(4);
                // F-perfup: engage per-region entity caps + force the inactive goal-selector throttle on.
                Knobs.KAIIJU_ENTITY_LIMITER_ENABLED.set(true);
                Knobs.GOAL_SELECTOR_INACTIVE_TICK_ENABLED.set(true);
                // NMS-scalar deep-enforcement: gently scale down entity caps + widen goal-selector cadence.
                Knobs.KAIIJU_ENTITY_LIMITER_SCALE.set(0.85D);
                Knobs.GOAL_SELECTOR_INACTIVE_TICK_INTERVAL.set(30);
            }
            case RED -> {
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(baselineProjectilePerTick <= 0 ? 5 : Math.max(1, baselineProjectilePerTick / 4)); // 0 = unlimited baseline
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(baselineProjectilePerProjectile <= 0 ? 5 : Math.max(1, baselineProjectilePerProjectile / 2));
                Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(true);
                Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.set(Math.max(1, baselineMinecartsLimit / 2));
                Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(true);
                Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.set(Math.max(1, baselineBoatsLimit / 2));
                Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(40);
                Knobs.AI_THROTTLE_TICK_INTERVAL.set(6);
                Knobs.KAIIJU_ENTITY_LIMITER_ENABLED.set(true);
                Knobs.GOAL_SELECTOR_INACTIVE_TICK_ENABLED.set(true);
                // NMS-scalar deep-enforcement: scale entity caps to 0.75, widen goal-selector cadence to 40.
                Knobs.KAIIJU_ENTITY_LIMITER_SCALE.set(0.75D);
                Knobs.GOAL_SELECTOR_INACTIVE_TICK_INTERVAL.set(40);
                MemoryPressure.trimSoftCaches();
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
                Knobs.KAIIJU_ENTITY_LIMITER_ENABLED.set(true);
                Knobs.GOAL_SELECTOR_INACTIVE_TICK_ENABLED.set(true);
                // NMS-scalar deep-enforcement: halve entity caps, widen goal-selector cadence to 60.
                Knobs.KAIIJU_ENTITY_LIMITER_SCALE.set(0.5D);
                Knobs.GOAL_SELECTOR_INACTIVE_TICK_INTERVAL.set(60);
                MemoryPressure.trimSoftCaches();
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
        // r35 raw-perf: even at GREEN, gently throttle the AI of mobs FAR from every player (default
        // beyond 80 blocks, brain/pathfinding every 3rd tick). Distant mobs barely interact, but their
        // pathfinding (WalkNodeEvaluator/PathNavigation — the #1 hotspot in the live spark profile) is
        // pure waste — so this is an always-on throughput win, not a load reaction. Reloadable; set
        // perf.ai.throttle-beyond-distance = 0 to disable and get vanilla every-tick AI everywhere.
        Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(dev.iyanz.sourbycraft.SourbyCraftConfig.aiBaselineThrottleDistance);
        Knobs.AI_THROTTLE_TICK_INTERVAL.set(dev.iyanz.sourbycraft.SourbyCraftConfig.aiBaselineThrottleInterval);
        // F-perfup: GREEN/YELLOW restore the operator/base defaults exactly — no regression.
        Knobs.KAIIJU_ENTITY_LIMITER_ENABLED.set(baselineEntityLimiterEnabled);
        Knobs.GOAL_SELECTOR_INACTIVE_TICK_ENABLED.set(baselineGoalSelectorInactive);
        // NMS-scalar deep-enforcement: GREEN/YELLOW restore the base magnitudes (scale 1.0, interval 20).
        Knobs.KAIIJU_ENTITY_LIMITER_SCALE.set(baselineEntityLimiterScale);
        if (baselineGoalSelectorInterval != -1) Knobs.GOAL_SELECTOR_INACTIVE_TICK_INTERVAL.set(baselineGoalSelectorInterval);
    }
}
