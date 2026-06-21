package dev.iyanz.sourbycraft.perf.knob;

/**
 * Sealed base for a runtime-tunable performance knob. Subclasses own a typed value
 * and a clamp policy. Each instance auto-registers in KnobRegistry on construction.
 * Knob declarations live in {@link Knobs}.
 */
public sealed abstract class PerfKnob permits BoolKnob, IntKnob {

    protected final String key;

    protected PerfKnob(String key) {
        this.key = key;
        KnobRegistry.register(this);
    }

    public final String key() { return key; }

    /** Boxed snapshot value for {@link Knobs#snapshot()}. */
    public abstract Object snapshot();

    /** Read from sourbycraft.yml (jar-baked) and apply to this knob. Called at boot. */
    abstract void loadFrom();
}
