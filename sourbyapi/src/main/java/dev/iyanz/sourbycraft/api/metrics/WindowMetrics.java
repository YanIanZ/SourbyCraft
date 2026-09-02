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

    double medianAverageMspt();

    double minimumMspt();

    double maximumMspt();

    double medianMspt();

    double estimatedP95Mspt();

    double estimatedP99Mspt();

    double busiestUtilisation();

    double averageUtilisation();

    double totalMissingCpuMs();
}
