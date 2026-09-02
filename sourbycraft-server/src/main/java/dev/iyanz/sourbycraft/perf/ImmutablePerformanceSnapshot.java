package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.Freshness;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import java.util.Objects;

record ImmutablePerformanceSnapshot(long sequence, long sampledAtEpochMillis, double targetTps,
                                    int activeRegionCount, int retainedGenerationCount,
                                    Freshness freshness, WindowMetrics fiveSeconds,
                                    WindowMetrics tenSeconds, WindowMetrics oneMinute,
                                    WindowMetrics fiveMinutes, WindowMetrics fifteenMinutes,
                                    RuntimeMetrics runtime,
                                    ImmutableGlobalMetrics global) implements PerformanceSnapshot {

    ImmutablePerformanceSnapshot {
        Objects.requireNonNull(freshness, "freshness");
        Objects.requireNonNull(fiveSeconds, "fiveSeconds");
        Objects.requireNonNull(tenSeconds, "tenSeconds");
        Objects.requireNonNull(oneMinute, "oneMinute");
        Objects.requireNonNull(fiveMinutes, "fiveMinutes");
        Objects.requireNonNull(fifteenMinutes, "fifteenMinutes");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(global, "global");
    }

    static ImmutablePerformanceSnapshot warming() {
        return empty(MetricState.WARMING, "Awaiting first collection");
    }

    static ImmutablePerformanceSnapshot unavailable(final String diagnostic) {
        return empty(MetricState.UNAVAILABLE, diagnostic);
    }

    private static ImmutablePerformanceSnapshot empty(final MetricState state, final String diagnostic) {
        final ImmutableFreshness freshness = new ImmutableFreshness(state, 0L, 0L, 0L, diagnostic);
        return new ImmutablePerformanceSnapshot(0L, 0L, Double.NaN, 0, 0, freshness,
            ImmutableWindowMetrics.EMPTY, ImmutableWindowMetrics.EMPTY, ImmutableWindowMetrics.EMPTY,
            ImmutableWindowMetrics.EMPTY, ImmutableWindowMetrics.EMPTY, ImmutableRuntimeMetrics.UNAVAILABLE,
            ImmutableGlobalMetrics.EMPTY);
    }

    ImmutablePerformanceSnapshot stale(final long nextSequence, final long nowEpochMillis,
                                       final long latenessMillis, final long scanDurationNanos,
                                       final String diagnostic) {
        final long age = Math.max(0L, nowEpochMillis - this.sampledAtEpochMillis);
        return new ImmutablePerformanceSnapshot(nextSequence, this.sampledAtEpochMillis, this.targetTps,
            this.activeRegionCount, this.retainedGenerationCount,
            new ImmutableFreshness(MetricState.STALE, age, latenessMillis, scanDurationNanos, diagnostic),
            this.fiveSeconds, this.tenSeconds, this.oneMinute, this.fiveMinutes, this.fifteenMinutes,
            this.runtime, this.global);
    }

    @Override
    public WindowMetrics window(final MetricWindow window) {
        return switch (Objects.requireNonNull(window, "window")) {
            case FIVE_SECONDS -> this.fiveSeconds;
            case TEN_SECONDS -> this.tenSeconds;
            case ONE_MINUTE -> this.oneMinute;
            case FIVE_MINUTES -> this.fiveMinutes;
            case FIFTEEN_MINUTES -> this.fifteenMinutes;
        };
    }
}
