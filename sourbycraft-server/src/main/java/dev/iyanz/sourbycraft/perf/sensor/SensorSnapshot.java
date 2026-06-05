package dev.iyanz.sourbycraft.perf.sensor;

/**
 * Immutable point-in-time reading of all 4 load signals + computed tier + dwell state.
 * Produced once per sensor tick (1s cadence at default cadence-ticks=20). Published via
 * a volatile field on PerfSensor; consumers see the latest visible snapshot via volatile
 * semantics. Record is the canonical pull-API payload.
 */
public record SensorSnapshot(
    long timestampNanos,
    double tps1s,
    double tps30s,
    double tps5m,
    double msptAvg,
    double memPct,
    double gcMsPerMin,
    Tier tier,
    Tier candidateTier,
    int dwellSamples
) {
    /** Snapshot returned before the first sensor tick has run. All signals zero; tier GREEN. */
    public static final SensorSnapshot INITIAL = new SensorSnapshot(
        0L, 20.0, 20.0, 20.0, 0.0, 0.0, 0.0, Tier.GREEN, Tier.GREEN, 0
    );
}
