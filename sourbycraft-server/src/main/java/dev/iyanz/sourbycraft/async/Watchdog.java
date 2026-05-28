package dev.iyanz.sourbycraft.async;

/**
 * Decides task deadlines from rolling average latency.
 * Deadline = max({@code FLOOR_MS}, avgLatencyMs * multiplier).
 *
 * Floor (100 ms) guards against avg-of-microsecond tasks creating
 * impossible deadlines.
 */
public final class Watchdog {

    private static final long FLOOR_MS = 100L;

    private final double multiplier;
    private double avgLatencyMs = 0.0;
    private long samples = 0;

    public Watchdog(double multiplier) {
        if (multiplier <= 0) throw new IllegalArgumentException("multiplier > 0");
        this.multiplier = multiplier;
    }

    public synchronized void recordCompletion(long latencyMs) {
        samples++;
        avgLatencyMs = avgLatencyMs + (latencyMs - avgLatencyMs) / samples;
    }

    public synchronized long deadlineMs() {
        if (samples == 0) return FLOOR_MS;
        long raw = (long) (avgLatencyMs * multiplier);
        return Math.max(FLOOR_MS, raw);
    }
}
