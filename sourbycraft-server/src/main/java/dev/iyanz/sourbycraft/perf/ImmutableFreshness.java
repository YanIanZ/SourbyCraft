package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.Freshness;
import dev.iyanz.sourbycraft.api.metrics.MetricState;

record ImmutableFreshness(MetricState state, long ageMillis, long collectorLatenessMillis,
                          long scanDurationNanos, String diagnostic) implements Freshness {}
