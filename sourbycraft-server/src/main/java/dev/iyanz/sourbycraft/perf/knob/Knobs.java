package dev.iyanz.sourbycraft.perf.knob;

import java.util.Map;

/**
 * Static declaration site for all SourbyCraft performance knobs. Each public-static-final
 * field declares one knob; class init triggers KnobRegistry registration. Hot-path readers
 * call {@code Knobs.<KNOB>.get()}; controllers and commands call {@code Knobs.<KNOB>.set(...)}.
 */
public final class Knobs {

    private Knobs() {}

    /** Skip-rate for entity ticking. 1 = tick every server tick (vanilla); 20 = once per second. */
    public static final IntKnob ENTITY_TICK_RATE =
        new IntKnob("perf.entity-tick-rate", 20, 1, 20);

    // === P2 Lag-Machine Protection knobs ===

    /** Disables NBT saving for Snowball entities. Saved snowballs are a known lag-machine vector
     *  (despawn-on-load thousands per chunk → slow chunk load). Default true per UniverseSpigot rec. */
    public static final BoolKnob LAG_MACHINE_DISABLE_SAVING_SNOWBALLS =
        new BoolKnob("perf.lag-machine.disable-saving-snowballs", true);

    /** Disables NBT saving for FireworkRocket entities. Same vector as snowballs. */
    public static final BoolKnob LAG_MACHINE_DISABLE_SAVING_FIREWORKS =
        new BoolKnob("perf.lag-machine.disable-saving-fireworks", true);

    /** Max total projectile-triggered chunk loads per server tick. 0 = unlimited. */
    public static final IntKnob LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK =
        new IntKnob("perf.lag-machine.max-projectile-loads-per-tick", 10, 0, 1000);

    /** Max chunk loads a single projectile can trigger before being discarded. 0 = unlimited. */
    public static final IntKnob LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE =
        new IntKnob("perf.lag-machine.max-projectile-loads-per-projectile", 10, 0, 100);

    /** Enables removing excess minecarts on collision (vehicle cap). */
    public static final BoolKnob LAG_MACHINE_REMOVE_EXCESS_MINECARTS =
        new BoolKnob("perf.lag-machine.remove-excess-minecarts", false);

    /** Threshold for "excess" minecarts at a collision point. */
    public static final IntKnob LAG_MACHINE_EXCESS_MINECARTS_LIMIT =
        new IntKnob("perf.lag-machine.excess-minecarts-limit", 10, 1, 1000);

    /** Enables removing excess boats on collision (vehicle cap). */
    public static final BoolKnob LAG_MACHINE_REMOVE_EXCESS_BOATS =
        new BoolKnob("perf.lag-machine.remove-excess-boats", false);

    /** Threshold for "excess" boats at a collision point. */
    public static final IntKnob LAG_MACHINE_EXCESS_BOATS_LIMIT =
        new IntKnob("perf.lag-machine.excess-boats-limit", 10, 1, 1000);

    public static Map<String, Object> snapshot() {
        return KnobRegistry.snapshot();
    }

    public static void loadFromYml() {
        KnobRegistry.loadAllFromYml();
    }

    /** Logs the current knob snapshot under context "boot". Convenience for boot-time call site. */
    public static void logLoaded() { KnobRegistry.logLoaded("boot"); }

    /** Logs the current knob snapshot under a caller-chosen context label (e.g. "tier-transition"). */
    public static void logLoaded(String context) { KnobRegistry.logLoaded(context); }
}
