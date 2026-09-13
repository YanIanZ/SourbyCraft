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

    double gcTimePercent();

    double gcCollectionsPerMinute();

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
