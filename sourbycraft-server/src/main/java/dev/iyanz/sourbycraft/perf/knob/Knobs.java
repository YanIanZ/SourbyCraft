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
