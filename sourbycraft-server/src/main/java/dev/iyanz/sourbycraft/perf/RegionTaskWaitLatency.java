package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * How long region tasks wait in their region's queue before the region runs them.
 *
 * <p><b>What is timed.</b> Feature patch 0049 stamps a {@code RegionizedTaskQueue} task the first time
 * it is pushed into a region queue ({@code PrioritisedQueue.tryPush}) and records, when the region's
 * {@code PrioritisedQueue.executeTask} polls it and is about to run it, the time since that stamp. Two
 * kinds, by queue: {@link Kind#TICK} (the region's tick-task queue, {@code queueTickTaskQueue}: among
 * others {@code FoliaRegionScheduler}'s plugin tasks and cross-region hand-offs from entity, player,
 * portal and command code) and {@link Kind#CHUNK} (the chunk-task queue, {@code queueChunkTask}:
 * chunk-system load/full-status completions and {@code ServerChunkCache} work). The wait covers time spent in a queue that is later merged or split into another region,
 * and a priority change that re-pushes the task keeps the first stamp. Not timed: tasks that are
 * cancelled, tasks completed outside the queue through {@code PrioritisedTask.execute()}, the
 * world-wide global chunk-task queue, the global region's task queue
 * ({@code RegionizedServer}'s own queue), and entity-scheduler tasks (a separate scheduler).</p>
 *
 * <p><b>Recording cost.</b> Per pushed task one {@code System.nanoTime()} and one volatile store; per
 * executed task one {@code System.nanoTime()}, one {@code LongAdder} increment into a fixed bucket,
 * one {@code LongAdder} add for the total, and one {@code LongAccumulator} max. No lock, no
 * allocation after the adders' cells exist (bounded by the CPU count). The overhead has not been
 * benchmarked on a server.</p>
 *
 * <p><b>Shape.</b> Cumulative since boot, per kind: an exact count, an exact total (for the mean), an
 * exact maximum, and a power-of-two histogram in microseconds. Bucket 0 holds waits under 1 µs,
 * bucket {@code b >= 1} holds waits in {@code [2^(b-1), 2^b)} µs, and the last bucket everything
 * longer. Percentiles are therefore upper bounds of a bucket ("at most"), not exact values. A reader
 * that wants a window diffs two {@link Snapshot#buckets()} arrays taken some time apart.</p>
 *
 * <p><b>Concurrency.</b> Each adder is exact once writers are quiet; a snapshot taken while tasks run
 * may see a count and a histogram that differ by the in-flight records. Nothing returned is live.</p>
 */
public final class RegionTaskWaitLatency {

    /** Histogram buckets; the last one is open-ended (waits of 2^38 µs, ~3 days, and longer). */
    public static final int BUCKETS = 40;

    /** A task that was never stamped (queued before this build, or pushed without the patch's hook). */
    public static final long UNSTAMPED = 0L;

    public static final RegionTaskWaitLatency GLOBAL = new RegionTaskWaitLatency(System::nanoTime);

    public enum Kind { TICK, CHUNK }

    /**
     * One kind's totals since boot.
     *
     * @param executed tasks that waited and were run by the region queue
     * @param meanMillis exact mean wait; 0 when nothing ran
     * @param p50AtMostMillis upper bound of the bucket holding the median; 0 when nothing ran
     * @param p99AtMostMillis upper bound of the bucket holding the 99th percentile; 0 when nothing ran
     * @param maxMillis the longest wait, exact
     * @param buckets a copy of the histogram, {@link #BUCKETS} counts
     */
    public record Snapshot(Kind kind, long executed, double meanMillis, double p50AtMostMillis,
                           double p99AtMostMillis, double maxMillis, long[] buckets) {
        public Snapshot {
            buckets = buckets.clone();
        }

        @Override
        public long[] buckets() {
            return this.buckets.clone();
        }
    }

    private final LongSupplier nanoClock;
    private final LongAdder[][] histogram = new LongAdder[Kind.values().length][BUCKETS];
    private final LongAdder[] totalNanos = new LongAdder[Kind.values().length];
    private final LongAccumulator[] maxNanos = new LongAccumulator[Kind.values().length];

    RegionTaskWaitLatency(final LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        for (int k = 0; k < this.histogram.length; k++) {
            for (int b = 0; b < BUCKETS; b++) this.histogram[k][b] = new LongAdder();
            this.totalNanos[k] = new LongAdder();
            this.maxNanos[k] = new LongAccumulator(Math::max, 0L);
        }
    }

    /**
     * The patch's push hook: the stamp a task keeps, given the one it has. A stamped task keeps its
     * first stamp (a re-push after a priority change), an unstamped one gets the current time.
     */
    public static long stampIfUnset(final long current) {
        return current != UNSTAMPED ? current : GLOBAL.stamp();
    }

    /** The patch's run hook: the region queue is about to run a task stamped at {@code stampedNanos}. */
    public static void taskStarting(final boolean chunkTask, final long stampedNanos) {
        GLOBAL.record(chunkTask ? Kind.CHUNK : Kind.TICK, stampedNanos);
    }

    long stamp() {
        final long now = this.nanoClock.getAsLong();
        return now == UNSTAMPED ? 1L : now;
    }

    void record(final Kind kind, final long stampedNanos) {
        if (stampedNanos == UNSTAMPED) {
            return;
        }
        final long waited = Math.max(0L, this.nanoClock.getAsLong() - stampedNanos);
        final int k = kind.ordinal();
        this.histogram[k][bucket(waited / 1_000L)].increment();
        this.totalNanos[k].add(waited);
        this.maxNanos[k].accumulate(waited);
    }

    static int bucket(final long micros) {
        return Math.min(BUCKETS - 1, 64 - Long.numberOfLeadingZeros(micros));
    }

    /** Upper bound of a bucket in milliseconds (the last bucket reports its lower bound). */
    static double upperMillis(final int bucket) {
        if (bucket == 0) return 0.001;
        final int exponent = bucket == BUCKETS - 1 ? bucket - 1 : bucket;
        return (1L << exponent) / 1000.0;
    }

    public Snapshot snapshot(final Kind kind) {
        final int k = kind.ordinal();
        final long[] counts = new long[BUCKETS];
        long executed = 0L;
        for (int b = 0; b < BUCKETS; b++) {
            counts[b] = this.histogram[k][b].sum();
            executed += counts[b];
        }
        final double mean = executed == 0L ? 0.0 : this.totalNanos[k].sum() / (double) executed / 1_000_000.0;
        return new Snapshot(kind, executed, mean, at(counts, executed, 0.50), at(counts, executed, 0.99),
            this.maxNanos[k].get() / 1_000_000.0, counts);
    }

    /** Nearest-rank percentile as the upper bound of the bucket it falls in; 0 for no samples. */
    private static double at(final long[] counts, final long total, final double q) {
        if (total == 0L) return 0.0;
        final long rank = Math.max(1L, (long) Math.ceil(q * total));
        long seen = 0L;
        for (int b = 0; b < counts.length; b++) {
            seen += counts[b];
            if (seen >= rank) return upperMillis(b);
        }
        return upperMillis(counts.length - 1);
    }
}
