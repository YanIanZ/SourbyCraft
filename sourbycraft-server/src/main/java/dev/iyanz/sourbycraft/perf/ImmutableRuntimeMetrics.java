package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;

record ImmutableRuntimeMetrics(long heapUsedBytes, long heapMaxBytes, double rssPercent,
                               double gcTimePercent, double gcCollectionsPerMinute,
                               double averageGcPauseMs, long heapCommittedBytes,
                               long nonHeapUsedBytes, long processRssBytes,
                               double processCpuPercent, double systemCpuPercent,
                               int availableProcessors, int liveThreadCount, long uptimeMillis,
                               long gcCollectionTimeMillis, long gcCollectionCount) implements RuntimeMetrics {

    ImmutableRuntimeMetrics(long used, long max, double rss, double gcTime, double gcRate, double gcAverage) {
        this(used, max, rss, gcTime, gcRate, gcAverage, -1L, -1L, -1L, Double.NaN,
            Double.NaN, -1, -1, -1L, -1L, -1L);
    }

    static final ImmutableRuntimeMetrics UNAVAILABLE = new ImmutableRuntimeMetrics(
        -1L, -1L, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
}
