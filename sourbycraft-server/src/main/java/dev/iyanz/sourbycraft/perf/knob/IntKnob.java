package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.SourbyCraftConfig;

public final class IntKnob extends PerfKnob {

    private final int defaultValue;
    private final int min;
    private final int max;
    private volatile int value;

    public IntKnob(String key, int defaultValue, int min, int max) {
        this(key, defaultValue, min, max, KnobMeta.legacy());
    }

    public IntKnob(String key, int defaultValue, int min, int max, KnobMeta meta) {
        super(key, meta);
        if (min > max) throw new IllegalArgumentException("min > max for " + key);
        this.min = min;
        this.max = max;
        this.defaultValue = clamp(defaultValue, min, max);
        this.value = this.defaultValue;
    }

    public int get() { return value; }

    public void set(int v) {
        int clamped = clamp(v, min, max);
        if (clamped != v) KnobRegistry.warnOnce(key, v, clamped);
        this.value = clamped;
    }

    public int min() { return min; }
    public int max() { return max; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "int"; }

    @Override public boolean applyRaw(Object raw) {
        if (raw instanceof Number n) { set(n.intValue()); return true; }
        return false;
    }

    @Override void loadFrom() {
        set(SourbyCraftConfig.ymlInt(key, defaultValue));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
