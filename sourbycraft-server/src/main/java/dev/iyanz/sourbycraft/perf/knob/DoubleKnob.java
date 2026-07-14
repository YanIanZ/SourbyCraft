package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.SourbyCraftConfig;

public final class DoubleKnob extends PerfKnob {

    private final double defaultValue;
    private volatile double value;

    public DoubleKnob(String key, double defaultValue, KnobMeta meta) {
        super(key, meta);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public double get() { return value; }

    public void set(double v) { this.value = v; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "double"; }

    @Override public boolean applyRaw(Object raw) {
        if (raw instanceof Number n) { this.value = n.doubleValue(); return true; }
        return false;
    }

    @Override void loadFrom() {
        this.value = SourbyCraftConfig.cfgDouble(key, defaultValue);
    }
}
