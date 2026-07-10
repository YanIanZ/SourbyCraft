package dev.iyanz.sourbycraft.perf.knob;

import java.util.Collections;
import java.util.Map;

/**
 * Minimal Folia-side stub of the SourbyCraft perf-knob registry.
 *
 * <p>No knobs are registered on the Folia base because the perf engine that
 * populates them has not been ported. {@code /perf} reads this snapshot and
 * simply omits the "Knobs" section when it is empty, so the command runs and
 * accurately reflects that no tunable knobs are active on this build.
 *
 * <p>Replace with the full registry (keyed {@code PerfKnob} map) when the perf
 * engine lands on Folia.
 */
public final class KnobRegistry {

    /** No knobs registered on Folia yet — always empty. */
    public static Map<String, Object> snapshot() {
        return Collections.emptyMap();
    }

    private KnobRegistry() {}
}
