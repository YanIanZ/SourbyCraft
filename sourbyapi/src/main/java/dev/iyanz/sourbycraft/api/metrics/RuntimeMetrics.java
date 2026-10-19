package dev.iyanz.sourbycraft.api.metrics;

import org.jspecify.annotations.NullMarked;

/**
 * Immutable, thread-safe process and JVM metrics captured with a performance snapshot.
 *
 * <p>A floating-point value is {@link Double#NaN} when the runtime cannot supply that metric.</p>
 */
@NullMarked
public interface RuntimeMetrics {
    long heapUsedBytes();

    long heapMaxBytes();

    double rssPercent();

    /**
     * MXBean collection time as a percentage of wall-clock time over the sampling window.
     * Not a pause metric: for concurrent collectors it includes work done beside the mutator.
     */
    double gcTimePercent();

    double gcCollectionsPerMinute();

    /**
     * Mean MXBean collection time per GC cycle over the sampling window, in milliseconds.
     *
     * <p>Despite the name this is <em>not</em> a stop-the-world pause measurement. It is
     * {@code GarbageCollectorMXBean#getCollectionTime()} divided by
     * {@code getCollectionCount()} (deltas over the window), and for concurrent collectors
     * (G1 concurrent phases, ZGC, Shenandoah) that elapsed time includes work done while the
     * application keeps running. Real pause times need GC logging or JFR {@code GCPhasePause}
     * events. The name is kept for binary compatibility with build 44 consumers.</p>
     */
    double averageGcPauseMs();

    /** Additional metrics return unavailable defaults for providers compiled before build 44. */
    default long heapCommittedBytes() { return -1L; }

    default long nonHeapUsedBytes() { return -1L; }

    /** Process resident memory, not container memory or committed virtual memory. */
    default long processRssBytes() { return -1L; }

    /** Recent process CPU load, percent of available CPU capacity; NaN when unsupported. */
    default double processCpuPercent() { return Double.NaN; }

    default double systemCpuPercent() { return Double.NaN; }

    default int availableProcessors() { return -1; }

    default int liveThreadCount() { return -1; }

    default long uptimeMillis() { return -1L; }

    /** Cumulative MXBean collection time; not a stop-the-world pause measurement. */
    default long gcCollectionTimeMillis() { return -1L; }

    default long gcCollectionCount() { return -1L; }
}
