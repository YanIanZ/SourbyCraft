package dev.iyanz.sourbycraft.perf.knob;

/**
 * Sealed base for a typed config knob. Subclasses own a typed value and a
 * clamp policy. Each instance auto-registers in KnobRegistry on construction.
 * Perf knob declarations live in {@link Knobs}; general config keys live in
 * {@code dev.iyanz.sourbycraft.config.ConfigKeys}.
 */
public sealed abstract class PerfKnob
    permits BoolKnob, IntKnob, DoubleKnob, StringKnob, EnumKnob, StringListKnob, MapKnob {

    protected final String key;
    protected final KnobMeta meta;

    protected PerfKnob(String key, KnobMeta meta) {
        this.key = key;
        this.meta = meta;
        KnobRegistry.register(this);
    }

    public final String key() { return key; }

    public final KnobMeta meta() { return meta; }

    /** Boxed snapshot value for {@link Knobs#snapshot()} and yml delegation. */
    public abstract Object snapshot();

    /** Boxed declared default (for the superseded report's non-default check). */
    public abstract Object defaultValue();

    /** Human-readable expected type for type-mismatch warnings. */
    public abstract String typeName();

    /**
     * Apply a raw value from the operator yml. Returns false when the raw
     * value's type does not fit this knob (value keeps its current state and
     * the caller emits the warn-once).
     */
    public abstract boolean applyRaw(Object raw);

    /** Read from the unified config and apply. Legacy boot path; removed in Task 4. */
    abstract void loadFrom();
}
