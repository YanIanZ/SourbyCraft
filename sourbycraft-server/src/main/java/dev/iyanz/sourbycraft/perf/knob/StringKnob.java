package dev.iyanz.sourbycraft.perf.knob;

public final class StringKnob extends PerfKnob {

    private final String defaultValue;
    private volatile String value;

    public StringKnob(String key, String defaultValue, KnobMeta meta) {
        super(key, meta);
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public String get() { return value; }

    public void set(String v) { this.value = v; }

    @Override public Object snapshot() { return value; }

    @Override public Object defaultValue() { return defaultValue; }

    @Override public String typeName() { return "String"; }

    @Override public boolean applyRaw(Object raw) {
        if (raw instanceof String s) { this.value = s; return true; }
        // Operators write unquoted scalars; a bare number/bool where a string
        // is expected is still usable as text (YamlConfiguration semantics).
        if (raw instanceof Number || raw instanceof Boolean) {
            this.value = String.valueOf(raw);
            return true;
        }
        return false;
    }

    @Override void loadFrom() {
        Object v = dev.iyanz.sourbycraft.SourbyCraftConfig.cfgGet(key, (Object) defaultValue);
        this.value = v instanceof String s ? s : defaultValue;
    }
}
