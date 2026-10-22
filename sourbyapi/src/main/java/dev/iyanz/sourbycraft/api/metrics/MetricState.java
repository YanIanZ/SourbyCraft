package dev.iyanz.sourbycraft.api.metrics;

/** Describes whether the values in a snapshot are ready and current. */
public enum MetricState {
    /** The collector is running but does not yet have enough history for every requested window. */
    WARMING,
    /** The snapshot is current and available. */
    AVAILABLE,
    /** The snapshot contains usable values but collection has fallen behind. */
    STALE,
    /** Metrics cannot currently be collected. */
    UNAVAILABLE
}
