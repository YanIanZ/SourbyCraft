package dev.iyanz.sourbycraft.api.metrics;

import org.jspecify.annotations.NullMarked;

/**
 * An immutable, thread-safe view of performance at one sampling boundary.
 *
 * <p>Every window is aligned to {@link #sampledAtEpochMillis()} and looks backward from that
 * boundary. A floating-point result is {@link Double#NaN} when it cannot be calculated. During
 * warming, shorter windows may be usable before longer windows; stale snapshots retain their last
 * collected values and report their age through {@link #freshness()}.</p>
 */
@NullMarked
public interface PerformanceSnapshot {
    long sequence();

    long sampledAtEpochMillis();

    double targetTps();

    int activeRegionCount();

    int retainedGenerationCount();

    Freshness freshness();

    WindowMetrics window(MetricWindow window);

    /**
     * Returns metrics for the process-wide global scheduler, which is measured separately and is
     * never included in spatial region counts or {@link #window(MetricWindow) spatial aggregates}.
     *
     * @param window requested lookback window
     * @return immutable global-scheduler metrics for that window
     */
    WindowMetrics globalWindow(MetricWindow window);

    RuntimeMetrics runtime();
}
