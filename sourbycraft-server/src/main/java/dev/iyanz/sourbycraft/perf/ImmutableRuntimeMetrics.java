package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;

record ImmutableRuntimeMetrics(long heapUsedBytes, long heapMaxBytes, double rssPercent,
                               double gcTimePercent, double gcCollectionsPerMinute,
                               double averageGcPauseMs) implements RuntimeMetrics {

    static final ImmutableRuntimeMetrics UNAVAILABLE = new ImmutableRuntimeMetrics(
        -1L, -1L, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
}
