package dev.iyanz.sourbycraft.api.metrics;

import org.jspecify.annotations.NullMarked;

/**
 * Immutable, thread-safe aggregates for one fixed lookback window.
 *
 * <p>Windows end at their snapshot's sampling boundary. {@link #coverageMillis()} describes the
 * history actually represented, which may be shorter while warming or when retention is
 * truncated. {@link #approximate()} identifies values derived from bounded summaries rather than
 * exact raw samples. Floating-point values are {@link Double#NaN} when unavailable.</p>
 */
@NullMarked
public interface WindowMetrics {
    long coverageMillis();

    long sampleCount();

    boolean approximate();

    boolean truncated();

    double worstTps();

    double medianTps();

    double aggregateTps();

    double worstAverageMspt();

    /** Returns total completed execution time divided by total completed sample count. */
    double aggregateAverageMspt();

    double medianAverageMspt();

    double minimumMspt();

    /**
     * Returns the longest tick duration in this window: the slowest completed tick, or the elapsed
     * time of a tick that is still running when it is longer (see
     * {@link #longestInProgressTickMspt()}). It is not clamped to the window length.
     */
    double maximumMspt();

    /**
     * Returns the elapsed time in milliseconds of the longest tick that was still running when the
     * snapshot was taken, or {@link Double#NaN} when no tick was running. A running tick has not
     * finished, so this is a lower bound of its duration, not a completed measurement. It is
     * already part of {@link #maximumMspt()}; this separates the two so a stalled tick cannot pass
     * for a completed maximum. Defaults to {@code NaN} for implementations that do not track it.
     */
    default double longestInProgressTickMspt() {
        return Double.NaN;
    }

    /**
     * Returns how many ticks were running when the snapshot was taken (regions for a region window,
     * 0 or 1 for the global window). Defaults to 0 for implementations that do not track it.
     */
    default int ticksInProgress() {
        return 0;
    }

    double medianMspt();

    double estimatedP95Mspt();

    double estimatedP99Mspt();

    double busiestUtilisation();

    double averageUtilisation();

    double totalMissingCpuMs();
}
