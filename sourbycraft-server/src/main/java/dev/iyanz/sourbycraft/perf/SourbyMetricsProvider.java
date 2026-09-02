package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import java.util.Objects;

public final class SourbyMetricsProvider implements SourbyMetrics {

    private volatile PerformanceSnapshot snapshot = ImmutablePerformanceSnapshot.warming();

    @Override
    public PerformanceSnapshot snapshot() {
        return this.snapshot;
    }

    void publish(final PerformanceSnapshot next) {
        this.snapshot = Objects.requireNonNull(next, "next");
    }
}
