package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import java.util.Objects;
import java.util.function.Supplier;

public final class SourbyMetricsProvider implements SourbyMetrics {

    private volatile PerformanceSnapshot snapshot = ImmutablePerformanceSnapshot.warming();
    private volatile Supplier<PerformanceSnapshot> snapshotsForTesting;

    @Override
    public PerformanceSnapshot snapshot() {
        final Supplier<PerformanceSnapshot> override = this.snapshotsForTesting;
        if (override != null) return Objects.requireNonNull(override.get(), "test snapshot");
        return this.snapshot;
    }

    void publish(final PerformanceSnapshot next) {
        this.snapshot = Objects.requireNonNull(next, "next");
    }

    void overrideSnapshotsForTesting(final Supplier<PerformanceSnapshot> override) {
        this.snapshotsForTesting = override;
    }
}
