package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.perf.knob.Knobs;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.kaiijumc.kaiiju.KaiijuEntityLimits;
import me.earthme.luminol.config.modules.optimizations.EntityGoalSelectorInactiveTickConfig;
import me.earthme.luminol.config.modules.optimizations.ProjectileChunkReduceConfig;

/**
 * Knob → base-config bridge (F2e). Closes the perf-engine loop: several SourbyCraft
 * perf knobs were previously only <em>set</em> (by {@link SelfTuneController} tier logic
 * and {@link CombatProfile}) but never <em>read</em> by any in-tick actuator, so they had
 * zero runtime effect.
 *
 * <p>Rather than add churn-prone NMS minecraft-patches to enforce them, this enforcer
 * pushes each mappable knob value into the corresponding Luminol/Pufferfish/Kaiiju base
 * config field. The base's own already-Folia-safe, already-optimized tick code then
 * enforces the value with real perf impact. We <b>drive</b> the base config; we do not
 * patch the base's tick code.
 *
 * <p><b>Enforced (real in-tick effect via base code):</b>
 * <ul>
 *   <li>{@code perf.lag-machine.max-projectile-loads-per-tick} →
 *       {@link ProjectileChunkReduceConfig#maxProjectileLoadsPerTick}
 *       (Pufferfish enforcement in {@code Projectile.setPosRaw}, per-region, Folia-safe).</li>
 *   <li>{@code perf.lag-machine.max-projectile-loads-per-projectile} →
 *       {@link ProjectileChunkReduceConfig#maxProjectileLoadsPerProjectile}
 *       (same enforcement point; discards a projectile that exceeds its lifetime cap).</li>
 *   <li>{@code perf.ai.throttle-*} + {@code perf.ai.goal-selector-inactive-throttle} →
 *       {@link EntityGoalSelectorInactiveTickConfig#enabled}
 *       (Pufferfish inactive-goal-selector throttle in {@code Mob.inactiveTick}). The base
 *       exposes only an on/off toggle plus a hardcoded 1-in-20 cadence — it has no
 *       distance/interval fields — so we bridge the <em>gate</em> as the OR of the two policies:
 *       the AI-throttle policy asking for any throttling (a positive tick-interval beyond a
 *       distance) OR the dedicated goal-selector gate knob. The goal-selector knob's SourbyCraft
 *       base default is {@code true} (F3-5, behaviour-neutral), and the perf-engine also forces it
 *       on under load; OR keeps GREEN at the base default while a load tier can only turn it ON.</li>
 *   <li>{@code perf.entity-limiter.enabled} → {@link KaiijuEntityLimits#enabled}
 *       (Kaiiju per-region per-entity-type tick/removal limiter, read in {@code ServerLevel}
 *       entity ticking). SourbyCraft base default {@code false} = no throttling (GREEN, no
 *       regression); the perf-engine flips it on at ORANGE/RED/EMERGENCY so the per-type caps in
 *       {@code kaiiju_entity_limits.yml} engage only under load. The per-type limits are operator
 *       YAML (not a scalar), so we bridge the master gate.</li>
 * </ul>
 *
 * <p><b>NOT bridged (no equivalent base mechanism — documented as remaining, see the F2e
 * report):</b> {@code perf.entity-tick-rate} (the scalar skip-rate has no single base field —
 * Kaiiju's limiter is per-entity-type counts in {@code kaiiju_entity_limits.yml}, now gated via
 * {@code perf.entity-limiter.enabled} above), the excess minecart/boat collision sweepers
 * ({@code perf.lag-machine.excess-*} / {@code remove-excess-*} — the base has no collision-point
 * vehicle cap), and the transient-projectile save suppression
 * ({@code perf.lag-machine.disable-saving-{snowballs,fireworks}} — no base config and no patch in
 * the current 82-patch series).
 *
 * <p><b>Rest defaults.</b> This enforcer only writes the base fields when SourbyCraft's tier
 * logic / combat profile has decided a value. It never mutates the base's compiled defaults at
 * class-load; if the perf-engine is disabled or never transitions, the base keeps its own
 * resting configuration.
 *
 * <p><b>Thread-safety.</b> The base config fields are plain {@code static} (the base already
 * mutates them from its own reload path); the enforcer writes them from the Folia
 * global-region scheduler thread (the sole caller, via {@link SelfTuneController#onTierChange}
 * and the boot path). The in-tick readers tolerate a benign racy read — the same best-effort
 * semantics the base's own config-reload already relies on.
 */
public final class KnobEnforcer {

    /** Last-enforced snapshot so we only log a base-config change when it actually changes. */
    private static volatile int lastProjPerTick = Integer.MIN_VALUE;
    private static volatile int lastProjPerProjectile = Integer.MIN_VALUE;
    private static volatile int lastGoalSelectorGate = Integer.MIN_VALUE; // 0/1, MIN_VALUE = never enforced
    private static volatile int lastEntityLimiterGate = Integer.MIN_VALUE; // 0/1, MIN_VALUE = never enforced
    private static volatile double lastEntityLimiterScale = Double.NaN;    // NaN = never enforced
    private static volatile int lastGoalSelectorInterval = Integer.MIN_VALUE; // MIN_VALUE = never enforced

    private KnobEnforcer() {}

    /**
     * Push the current mappable knob values into their base-config fields. Idempotent and cheap;
     * call after every knob mutation (tier transition, combat-profile apply, boot). Logs a line
     * per field that actually changed so the bridge is observable.
     */
    public static void enforceAll() {
        enforceProjectileCaps();
        enforceAiThrottleGate();
        enforceEntityLimiterGate();
        enforceEntityLimiterScale();
        enforceGoalSelectorInterval();
    }

    private static void enforceProjectileCaps() {
        final int perTick = Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.get();
        if (perTick != lastProjPerTick) {
            ProjectileChunkReduceConfig.maxProjectileLoadsPerTick = perTick;
            lastProjPerTick = perTick;
            SourbyLogger.debug("enforced perf.lag-machine.max-projectile-loads-per-tick=" + perTick
                + " -> ProjectileChunkReduceConfig.maxProjectileLoadsPerTick");
        }
        final int perProjectile = Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.get();
        if (perProjectile != lastProjPerProjectile) {
            ProjectileChunkReduceConfig.maxProjectileLoadsPerProjectile = perProjectile;
            lastProjPerProjectile = perProjectile;
            SourbyLogger.debug("enforced perf.lag-machine.max-projectile-loads-per-projectile=" + perProjectile
                + " -> ProjectileChunkReduceConfig.maxProjectileLoadsPerProjectile");
        }
    }

    /**
     * Map the SourbyCraft AI-throttle policy onto the base's on/off inactive-goal-selector gate.
     * The policy "throttle AI" is expressed by a positive throttle tick-interval (every N&gt;1
     * ticks) at/beyond a distance; the base has no distance/interval field, only an enable flag
     * and a fixed 1-in-20 cadence, so we enable the base throttle whenever SourbyCraft wants any
     * AI throttling and disable it for the vanilla (every-tick) profile.
     */
    private static void enforceAiThrottleGate() {
        final int interval = Knobs.AI_THROTTLE_TICK_INTERVAL.get();
        final int distance = Knobs.AI_THROTTLE_BEYOND_DISTANCE.get();
        // The base's inactive-goal-selector throttle (EntityGoalSelectorInactiveTickConfig.enabled)
        // is driven by TWO SourbyCraft policies, OR'd together:
        //   (a) the AI-throttle policy asks to slow AI beyond a distance every N>1 ticks, OR
        //   (b) the dedicated goal-selector gate knob is on (its SourbyCraft base default is true;
        //       the perf-engine also forces it on under load — see SelfTuneController).
        // OR keeps GREEN honouring the base default (true) while a load tier can only ever turn it ON.
        final boolean aiThrottle = distance > 0 && interval > 1;
        final boolean goalSelectorKnob = Knobs.GOAL_SELECTOR_INACTIVE_TICK_ENABLED.get();
        final boolean gate = aiThrottle || goalSelectorKnob;
        final int gateBit = gate ? 1 : 0;
        if (gateBit != lastGoalSelectorGate) {
            EntityGoalSelectorInactiveTickConfig.enabled = gate;
            lastGoalSelectorGate = gateBit;
            SourbyLogger.debug("enforced goal-selector inactive-throttle gate (ai-throttle interval="
                + interval + " distance=" + distance + ", goal-selector-knob=" + goalSelectorKnob
                + ") -> EntityGoalSelectorInactiveTickConfig.enabled=" + gate);
        }
    }

    /**
     * Map the SourbyCraft entity-limiter gate knob onto Kaiiju's master switch
     * ({@code KaiijuEntityLimits.enabled}), read in-tick by the Kaiiju per-region entity throttler.
     * When off (SourbyCraft base default), Kaiiju does no throttling — vanilla entity ticking, no
     * regression. The perf-engine flips the knob ON at ORANGE/RED/EMERGENCY so the per-type caps in
     * {@code kaiiju_entity_limits.yml} engage only while the server is under load. The per-type
     * limits themselves are operator YAML (not a scalar), so we bridge the master gate; an operator
     * who wants caps active tunes the per-type limits and the gate turns them on under load.
     */
    private static void enforceEntityLimiterGate() {
        final boolean gate = Knobs.KAIIJU_ENTITY_LIMITER_ENABLED.get();
        final int gateBit = gate ? 1 : 0;
        if (gateBit != lastEntityLimiterGate) {
            KaiijuEntityLimits.enabled = gate;
            lastEntityLimiterGate = gateBit;
            SourbyLogger.debug("enforced perf.entity-limiter.enabled=" + gate
                + " -> KaiijuEntityLimits.enabled");
        }
    }

    /**
     * Push the SourbyCraft entity-limiter scale knob onto Kaiiju's global per-type cap multiplier
     * ({@code KaiijuEntityLimits.limitScale}), consumed in-tick by {@code KaiijuEntityThrottler}.
     * Default 1.0 = base behaviour (per-type caps used as-is; GREEN, no regression). Under load the
     * perf-engine scales it DOWN so every per-type tick cap shrinks together — fewer entities tick
     * per tick while the server struggles. Only has effect while the limiter gate itself is on.
     */
    private static void enforceEntityLimiterScale() {
        final double scale = Knobs.KAIIJU_ENTITY_LIMITER_SCALE.get();
        if (scale != lastEntityLimiterScale) {
            KaiijuEntityLimits.limitScale = scale;
            lastEntityLimiterScale = scale;
            SourbyLogger.debug("enforced perf.entity-limiter.scale=" + scale
                + " -> KaiijuEntityLimits.limitScale");
        }
    }

    /**
     * Push the SourbyCraft goal-selector interval knob onto the Pufferfish inactive-tick cadence
     * ({@code EntityGoalSelectorInactiveTickConfig.inactiveTickInterval}), consumed in-tick by
     * {@code Mob.inactiveTick}. Default 20 = the exact base behaviour (1-in-20 cadence; GREEN, no
     * regression). Under load the perf-engine WIDENS it so the goal selector ticks even less often.
     * Only has effect while the throttle gate itself is on.
     */
    private static void enforceGoalSelectorInterval() {
        final int interval = Knobs.GOAL_SELECTOR_INACTIVE_TICK_INTERVAL.get();
        if (interval != lastGoalSelectorInterval) {
            EntityGoalSelectorInactiveTickConfig.inactiveTickInterval = interval;
            lastGoalSelectorInterval = interval;
            SourbyLogger.debug("enforced perf.ai.goal-selector-interval=" + interval
                + " -> EntityGoalSelectorInactiveTickConfig.inactiveTickInterval");
        }
    }
}
