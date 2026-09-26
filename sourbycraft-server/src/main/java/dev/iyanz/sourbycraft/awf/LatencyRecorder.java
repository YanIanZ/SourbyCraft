package dev.iyanz.sourbycraft.awf;

import java.util.Arrays;

/**
 * The last {@link #CAPACITY} latency samples, for p50/p95/p99.
 *
 * <p>A fixed ring, so memory is bounded however long the server runs, and percentiles describe
 * recent behaviour rather than the whole uptime. Thread-safe; recording is a synchronized store
 * into an array, cheap beside the I/O being measured.</p>
 */
public final class LatencyRecorder {

    static final int CAPACITY = 1024;

    /** Percentiles in milliseconds over the retained samples; all zero when there are none. */
    public record Percentiles(int samples, double p50, double p95, double p99) {}

    private final long[] nanos = new long[CAPACITY];
    private long count;

    public synchronized void record(final long elapsedNanos) {
        this.nanos[(int)(this.count % CAPACITY)] = Math.max(0L, elapsedNanos);
        this.count++;
    }

    public Percentiles percentiles() {
        final long[] copy;
        synchronized (this) {
            copy = Arrays.copyOf(this.nanos, (int)Math.min(this.count, CAPACITY));
        }
        if (copy.length == 0) return new Percentiles(0, 0, 0, 0);
        Arrays.sort(copy);
        return new Percentiles(copy.length, at(copy, 0.50), at(copy, 0.95), at(copy, 0.99));
    }

    /** Nearest-rank percentile. */
    private static double at(final long[] sorted, final double q) {
        final int rank = (int)Math.ceil(q * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))] / 1_000_000.0;
    }
}
