package dev.iyanz.sourbycraft.perf.knob;

import dev.iyanz.sourbycraft.SourbyCraftConfig;

public final class BoolKnob extends PerfKnob {

    private final boolean defaultValue;
    private volatile boolean value;

    public BoolKnob(String key, boolean defaultValue) {
        super(key);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public boolean get() { return value; }

    public void set(boolean v) { this.value = v; }

    @Override public Object snapshot() { return value; }

    @Override void loadFrom() {
        this.value = SourbyCraftConfig.ymlBool(key, defaultValue);
    }
}
