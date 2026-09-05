package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import java.util.Objects;

record ImmutableGlobalMetrics(WindowMetrics fiveSeconds, WindowMetrics tenSeconds,
                              WindowMetrics oneMinute, WindowMetrics fiveMinutes,
                              WindowMetrics fifteenMinutes) {

    static final ImmutableGlobalMetrics EMPTY = new ImmutableGlobalMetrics(
        ImmutableWindowMetrics.EMPTY, ImmutableWindowMetrics.EMPTY, ImmutableWindowMetrics.EMPTY,
        ImmutableWindowMetrics.EMPTY, ImmutableWindowMetrics.EMPTY);

    ImmutableGlobalMetrics {
        Objects.requireNonNull(fiveSeconds, "fiveSeconds");
        Objects.requireNonNull(tenSeconds, "tenSeconds");
        Objects.requireNonNull(oneMinute, "oneMinute");
        Objects.requireNonNull(fiveMinutes, "fiveMinutes");
        Objects.requireNonNull(fifteenMinutes, "fifteenMinutes");
    }

    WindowMetrics window(final MetricWindow window) {
        return switch (Objects.requireNonNull(window, "window")) {
            case FIVE_SECONDS -> this.fiveSeconds;
            case TEN_SECONDS -> this.tenSeconds;
            case ONE_MINUTE -> this.oneMinute;
            case FIVE_MINUTES -> this.fiveMinutes;
            case FIFTEEN_MINUTES -> this.fifteenMinutes;
        };
    }
}
