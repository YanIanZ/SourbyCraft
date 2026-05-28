package dev.iyanz.sourbycraft.async;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-pool counters. Latency uses a cumulative average for low overhead.
 */
public final class PoolMetrics {

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong queueDepthHigh = new AtomicLong();
    private volatile double avgLatencyMs = 0.0;

    public void recordSubmit() { submitted.incrementAndGet(); }

    public synchronized void recordCompletion(long latencyMs) {
        long c = completed.incrementAndGet();
        avgLatencyMs = avgLatencyMs + (latencyMs - avgLatencyMs) / c;
    }

    public void recordTimeout() { timedOut.incrementAndGet(); }

    public void observeQueueDepth(long depth) {
        long h;
        do {
            h = queueDepthHigh.get();
            if (depth <= h) return;
        } while (!queueDepthHigh.compareAndSet(h, depth));
    }

    public Snapshot snapshot() {
        return new Snapshot(
            submitted.get(),
            completed.get(),
            timedOut.get(),
            queueDepthHigh.get(),
            avgLatencyMs
        );
    }

    public static final class Snapshot {
        public final long submitted;
        public final long completed;
        public final long timedOut;
        public final long queueDepthHigh;
        public final double avgLatencyMs;

        Snapshot(long submitted, long completed, long timedOut, long queueDepthHigh, double avgLatencyMs) {
            this.submitted = submitted;
            this.completed = completed;
            this.timedOut = timedOut;
            this.queueDepthHigh = queueDepthHigh;
            this.avgLatencyMs = avgLatencyMs;
        }
    }
}
