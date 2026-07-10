package dev.iyanz.sourbycraft.perf.knob;

import java.util.Locale;

public final class EnumKnob<E extends Enum<E>> extends PerfKnob {

    private final Class<E> type;
    private final E defaultValue;
    private volatile E value;

    public EnumKnob(String key, Class<E> type, E defaultValue, KnobMeta meta) {
        super(key, meta);
        this.type = type;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public E get() { return value; }

    public void set(E v) { this.value = v; }

    /** Lowercase name — matches how operators write it in yml. */
    @Override public Object snapshot() { return value.name().toLowerCase(Locale.ROOT); }

    @Override public Object defaultValue() { return defaultValue.name().toLowerCase(Locale.ROOT); }

    @Override public String typeName() { return type.getSimpleName(); }

    @Override public boolean applyRaw(Object raw) {
        if (!(raw instanceof String s)) return false;
        try {
            this.value = Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override void loadFrom() { /* enum keys are operator-file only; no jar-baked read */ }
}
