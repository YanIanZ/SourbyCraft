package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.perf.knob.Knobs;
import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.util.Locale;

/**
 * P4 Combat Profiles.
 *
 * <p>Operator-selectable bundle of knob defaults tuned for a target
 * playstyle. Applied once at boot from {@code combat.profile} in
 * {@code sourbycraft.yml}. {@link SelfTuneController} still owns runtime
 * escalation per {@code PerfSensor} tier transitions — a profile only
 * sets the operator-baseline, not the tier-escalated values.
 *
 * <pre>
 *  VANILLA  — every-tick AI for all mobs, no sweepers (Mojang parity)
 *  BALANCED — moderate AI throttle (96 blocks, every 2 ticks), sweepers off
 *  PVP      — aggressive AI throttle (48 blocks, every 4 ticks), vehicle
 *             sweepers + projectile cap quartered (PvP arenas with thick
 *             cross-fire benefit; SMP servers want BALANCED instead)
 * </pre>
 */
public enum CombatProfile {

    VANILLA, BALANCED, PVP;

    public static CombatProfile parse(final String raw, final CombatProfile fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            SourbyLogger.warn("Unknown combat.profile '" + raw + "', falling back to " + fallback);
            return fallback;
        }
    }

    /** Apply this profile's knob defaults. Idempotent. */
    public void apply() {
        switch (this) {
            case VANILLA -> {
                Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(0);
                Knobs.AI_THROTTLE_TICK_INTERVAL.set(4);
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(0);
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(0);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(false);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(false);
            }
            case BALANCED -> {
                Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(96);
                Knobs.AI_THROTTLE_TICK_INTERVAL.set(2);
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(10);
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(10);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(false);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(false);
            }
            case PVP -> {
                Knobs.AI_THROTTLE_BEYOND_DISTANCE.set(48);
                Knobs.AI_THROTTLE_TICK_INTERVAL.set(4);
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(5);
                Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(5);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(true);
                Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.set(8);
                Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(true);
                Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.set(8);
            }
        }
        SourbyLogger.info("combat profile applied: " + this);
        // F2e: enforce the profile's knob baseline onto the base config immediately, so the
        // operator-selected combat profile has real in-tick effect from boot (before any tier
        // escalation). SelfTuneController re-enforces on every subsequent tier transition.
        KnobEnforcer.enforceAll();
    }
}
