package dev.iyanz.sourbycraft.api.metrics;

import org.jspecify.annotations.NullMarked;

/**
 * Immutable, thread-safe collection health for a snapshot.
 *
 * <p>{@link MetricState#WARMING} indicates incomplete history, while
 * {@link MetricState#STALE} indicates that the last values are older than the collector's expected
 * cadence. Consumers should use {@link #state()} and {@link #ageMillis()} rather than inferring
 * freshness from metric values.</p>
 */
@NullMarked
public interface Freshness {
    MetricState state();

    long ageMillis();

    long collectorLatenessMillis();

    long scanDurationNanos();

    String diagnostic();
}
