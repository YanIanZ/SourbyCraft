package dev.iyanz.sourbycraft.perf.sensor;

/**
 * Server load tier — worse = higher ordinal. Used by PerfSensor's classifier and by
 * downstream consumers (P7 controller, P8 telemetry). Cardinality is fixed; do not
 * insert new values mid-list without auditing every Tier.ordinal()-indexed array.
 */
public enum Tier {
    GREEN,
    YELLOW,
    ORANGE,
    RED,
    EMERGENCY;

    /** {@code true} if this tier represents worse load than {@code other}. */
    public boolean isWorseThan(Tier other) {
        return this.ordinal() > other.ordinal();
    }

    /** Returns whichever of {@code this} or {@code other} represents worse load. */
    public Tier worse(Tier other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }
}
