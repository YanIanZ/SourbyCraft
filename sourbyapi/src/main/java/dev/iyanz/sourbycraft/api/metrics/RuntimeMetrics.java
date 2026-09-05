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
}
