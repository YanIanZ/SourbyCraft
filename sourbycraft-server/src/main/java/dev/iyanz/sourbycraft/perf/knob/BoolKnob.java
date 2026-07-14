package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.SourbyCraftConfig;

public final class BoolKnob extends PerfKnob {

    private final boolean defaultValue;
    private volatile boolean value;

    public BoolKnob(String key, boolean defaultValue) {
        this(key, defaultValue, KnobMeta.legacy());
    }

    public BoolKnob(String key, boolean defaultValue, KnobMeta meta) {
        super(key, meta);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public boolean get() { return value; }

    public void set(boolean v) { this.value = v; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "boolean"; }

    @Override public boolean applyRaw(Object raw) {
        if (raw instanceof Boolean b) { this.value = b; return true; }
        return false;
    }

    @Override void loadFrom() {
        this.value = SourbyCraftConfig.cfgBool(key, defaultValue);
    }
}
