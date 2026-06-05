package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class KnobRegistry {

    private static final Map<String, PerfKnob> KNOBS = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private KnobRegistry() {}

    static void register(PerfKnob k) {
        if (KNOBS.putIfAbsent(k.key(), k) != null) {
            throw new IllegalStateException("duplicate knob key: " + k.key());
        }
    }

    static void warnOnce(String key, int requested, int clamped) {
        String dedupeKey = key + ":" + (requested < clamped ? "lo" : "hi");
        if (WARNED.add(dedupeKey)) {
            SourbyLogger.warn(
                "[SourbyCraft] knob '" + key + "' value " + requested
                    + " clamped to " + clamped
            );
        }
    }

    static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        KNOBS.forEach((k, knob) -> out.put(k, knob.snapshot()));
        return Collections.unmodifiableMap(out);
    }

    static void loadAllFromYml() {
        for (PerfKnob k : KNOBS.values()) k.loadFrom();
    }

    static void logLoaded(String context) {
        StringBuilder sb = new StringBuilder("[SourbyCraft] perf knobs loaded [")
            .append(context).append("]:");
        KNOBS.forEach((key, knob) -> sb.append(" ").append(key).append("=").append(knob.snapshot()));
        SourbyLogger.info(sb.toString());
    }
}
