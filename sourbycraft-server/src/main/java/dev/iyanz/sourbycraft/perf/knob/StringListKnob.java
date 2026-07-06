package dev.iyanz.sourbycraft.perf.knob;

import java.util.ArrayList;
import java.util.List;

public final class StringListKnob extends PerfKnob {

    private final List<String> defaultValue;
    private volatile List<String> value;

    public StringListKnob(String key, List<String> defaultValue, KnobMeta meta) {
        super(key, meta);
        this.defaultValue = List.copyOf(defaultValue);
        this.value = this.defaultValue;
    }

    public List<String> get() { return value; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "List<String>"; }

    @Override public boolean applyRaw(Object raw) {
        if (!(raw instanceof List<?> list)) return false;
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) out.add(o.toString());
        }
        this.value = List.copyOf(out);
        return true;
    }

    @Override void loadFrom() { /* list keys are operator-file only; no jar-baked read */ }
}
