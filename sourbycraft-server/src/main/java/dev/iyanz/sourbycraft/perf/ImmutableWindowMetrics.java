package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;

record ImmutableWindowMetrics(long coverageMillis, long sampleCount, boolean approximate,
                               boolean truncated, double worstTps, double medianTps,
                               double aggregateTps, double worstAverageMspt, double aggregateAverageMspt,
                              double medianAverageMspt, double minimumMspt,
                              double maximumMspt, double medianMspt,
                              double estimatedP95Mspt, double estimatedP99Mspt,
                              double busiestUtilisation, double averageUtilisation,
                              double totalMissingCpuMs) implements WindowMetrics {

    static final ImmutableWindowMetrics EMPTY = new ImmutableWindowMetrics(
        0L, 0L, false, false,
        Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
        Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
        Double.NaN, Double.NaN, Double.NaN);
}
