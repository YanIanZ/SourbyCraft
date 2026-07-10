package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.perf.knob.Knobs;
import dev.iyanz.sourbycraft.util.SourbyLogger;
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
 *   <li>{@code perf.ai.throttle-*} → {@link EntityGoalSelectorInactiveTickConfig#enabled}
 *       (Pufferfish inactive-goal-selector throttle in {@code Mob.serverAiStep}). The base
 *       exposes only an on/off toggle plus a hardcoded 1-in-20 cadence — it has no
 *       distance/interval fields — so we bridge the <em>gate</em>: AI throttling is turned on
 *       whenever the SourbyCraft policy asks for any throttling (a positive tick-interval),
 *       and off when the policy wants vanilla every-tick AI.</li>
 * </ul>
 *
 * <p><b>NOT bridged (no equivalent base mechanism — documented as remaining, see the F2e
 * report):</b> {@code perf.entity-tick-rate} (Kaiiju's limiter is per-entity-type counts in
 * {@code kaiiju_entity_limits.yml}, not a single scalar tick-rate), the excess
 * minecart/boat collision sweepers ({@code perf.lag-machine.excess-*} / {@code remove-excess-*}
 * — the base has no collision-point vehicle cap), and the transient-projectile save
 * suppression ({@code perf.lag-machine.disable-saving-{snowballs,fireworks}} — no base config
 * and no patch in the current 82-patch series).
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

    private KnobEnforcer() {}

    /**
     * Push the current mappable knob values into their base-config fields. Idempotent and cheap;
     * call after every knob mutation (tier transition, combat-profile apply, boot). Logs a line
     * per field that actually changed so the bridge is observable.
     */
    public static void enforceAll() {
        enforceProjectileCaps();
        enforceAiThrottleGate();
    }

    private static void enforceProjectileCaps() {
        final int perTick = Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.get();
        if (perTick != lastProjPerTick) {
            ProjectileChunkReduceConfig.maxProjectileLoadsPerTick = perTick;
            lastProjPerTick = perTick;
            SourbyLogger.info("enforced perf.lag-machine.max-projectile-loads-per-tick=" + perTick
                + " -> ProjectileChunkReduceConfig.maxProjectileLoadsPerTick");
        }
        final int perProjectile = Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.get();
        if (perProjectile != lastProjPerProjectile) {
            ProjectileChunkReduceConfig.maxProjectileLoadsPerProjectile = perProjectile;
            lastProjPerProjectile = perProjectile;
            SourbyLogger.info("enforced perf.lag-machine.max-projectile-loads-per-projectile=" + perProjectile
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
        // Throttle is "active" when the policy asks to slow AI beyond a distance every N>1 ticks.
        final boolean gate = distance > 0 && interval > 1;
        final int gateBit = gate ? 1 : 0;
        if (gateBit != lastGoalSelectorGate) {
            EntityGoalSelectorInactiveTickConfig.enabled = gate;
            lastGoalSelectorGate = gateBit;
            SourbyLogger.info("enforced perf.ai.throttle (interval=" + interval + ", distance=" + distance
                + ") -> EntityGoalSelectorInactiveTickConfig.enabled=" + gate);
        }
    }
}
