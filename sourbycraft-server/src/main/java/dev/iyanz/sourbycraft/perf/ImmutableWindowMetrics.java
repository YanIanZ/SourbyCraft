package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;

record ImmutableWindowMetrics(long coverageMillis, long sampleCount, boolean approximate,
                               boolean truncated, double worstTps, double medianTps,
                               double aggregateTps, double worstAverageMspt, double aggregateAverageMspt,
                              double medianAverageMspt, double minimumMspt,
                              double maximumMspt, double medianMspt,
                              double estimatedP95Mspt, double estimatedP99Mspt,
                              double busiestUtilisation, double averageUtilisation,
                              double totalMissingCpuMs, double longestInProgressTickMspt,
                              int ticksInProgress) implements WindowMetrics {

    /** A window with no tick running; the usual case, and what every pre-existing caller means. */
    ImmutableWindowMetrics(final long coverageMillis, final long sampleCount, final boolean approximate,
                           final boolean truncated, final double worstTps, final double medianTps,
                           final double aggregateTps, final double worstAverageMspt,
                           final double aggregateAverageMspt, final double medianAverageMspt,
                           final double minimumMspt, final double maximumMspt, final double medianMspt,
                           final double estimatedP95Mspt, final double estimatedP99Mspt,
                           final double busiestUtilisation, final double averageUtilisation,
                           final double totalMissingCpuMs) {
        this(coverageMillis, sampleCount, approximate, truncated, worstTps, medianTps, aggregateTps,
            worstAverageMspt, aggregateAverageMspt, medianAverageMspt, minimumMspt, maximumMspt,
            medianMspt, estimatedP95Mspt, estimatedP99Mspt, busiestUtilisation, averageUtilisation,
            totalMissingCpuMs, Double.NaN, 0);
    }

    static final ImmutableWindowMetrics EMPTY = new ImmutableWindowMetrics(
        0L, 0L, false, false,
        Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
        Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
        Double.NaN, Double.NaN, Double.NaN);
}
