package dev.iyanz.sourbycraft.perf.knob;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raw nested-map knob for structured sections (emoji.shortcodes.codes,
 * dab.entity-overrides). Values are deep-copied on apply; consumers parse
 * the raw structure in the SourbyCraftConfig bridge block.
 */
public final class MapKnob extends PerfKnob {

    private final Map<String, Object> defaultValue;
    private volatile Map<String, Object> value;

    public MapKnob(String key, Map<String, Object> defaultValue, KnobMeta meta) {
        super(key, meta);
        this.defaultValue = deepCopy(defaultValue);
        this.value = this.defaultValue;
    }

    public Map<String, Object> get() { return value; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "Map"; }

    @Override public boolean applyRaw(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return false;
        this.value = deepCopy(castKeysToString(m));
        return true;
    }

    @Override void loadFrom() { /* map keys are operator-file only; no jar-baked read */ }

    private static Map<String, Object> castKeysToString(Map<?, ?> in) {
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        in.forEach((k, v) -> { if (k != null) out.put(k.toString(), v); });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        in.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> m
            ? deepCopy(castKeysToString(m))
            : v));
        return Collections.unmodifiableMap(out);
    }
}
