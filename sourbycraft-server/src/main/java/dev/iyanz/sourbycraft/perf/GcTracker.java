package dev.iyanz.sourbycraft.perf;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Lifecycle-owned sampler of cumulative MXBean collection counters over a bounded 60s window.
 * Collection time can include concurrent collector work; it is not a stop-the-world pause metric.
 * The worker owns the ring under the class monitor and publishes an immutable result to readers.
 */
public final class GcTracker {

    private GcTracker() {}

    private static final int WINDOW_SECONDS = 60;
    private static final int SAMPLE_PERIOD_SECONDS = 3;
    private static final int SLOTS = WINDOW_SECONDS / SAMPLE_PERIOD_SECONDS + 1; // ~21

    // Ring buffers of cumulative counters + the wall-clock nanotime each sample was taken.
    private static final long[] ringCount = new long[SLOTS];
    private static final long[] ringTimeMs = new long[SLOTS];
    private static final long[] ringNanos = new long[SLOTS];
    private static volatile int head = -1;      // index of the newest sample, -1 until first sample
    private static volatile int filled = 0;      // number of valid samples (<= SLOTS)
    private static volatile boolean started = false;
    private static volatile Thread worker;
    private static volatile Gc published = Gc.EMPTY;

    /** Idempotent. Starts the sampler daemon. */
    public static synchronized void start() {
        if (started) return;
        started = true;
        sampleOnce(System.nanoTime()); // seed slot 0 immediately so an early snapshot has a baseline
        final Thread t = new Thread(() -> {
            while (Thread.currentThread() == worker) {
                try {
                    Thread.sleep(SAMPLE_PERIOD_SECONDS * 1000L);
                } catch (final InterruptedException e) {
                    return;
                }
                try {
                    sampleOnce(System.nanoTime());
                } catch (final Throwable ignored) {
                    // never let a GC-bean hiccup kill the sampler
                }
            }
        }, "SourbyCraft-GcTracker");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** Called during server shutdown. No collector thread survives an explicit stop. */
    public static void stop() {
        final Thread closing;
        synchronized (GcTracker.class) {
            closing = worker;
            worker = null;
            started = false;
            head = -1;
            filled = 0;
            published = Gc.EMPTY;
        }
        if (closing != null) {
            closing.interrupt();
            try { closing.join(2_000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private static synchronized void sampleOnce(final long nowNanos) {
        if (!started) return;
        long totalCount = 0L;
        long totalTimeMs = 0L;
        final List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (final GarbageCollectorMXBean b : beans) {
            final long c = b.getCollectionCount();
            final long tm = b.getCollectionTime();
            if (c > 0) totalCount += c;
            if (tm > 0) totalTimeMs += tm;
        }
        final int next = (head + 1) % SLOTS;
        ringCount[next] = totalCount;
        ringTimeMs[next] = totalTimeMs;
        ringNanos[next] = nowNanos;
        head = next;
        if (filled < SLOTS) filled++;
        published = calculateSnapshot();
    }

    /** @return GC health over the rolling window; all-zero (never null) when there is no data yet. */
    public static Gc snapshot() {
        return published;
    }

    private static Gc calculateSnapshot() {
        try {
            final int h = head;
            final int f = filled;
            if (h < 0 || f < 2) {
                return Gc.EMPTY;
            }
            // Oldest valid slot = (head - (filled-1)) mod SLOTS.
            final int oldest = ((h - (f - 1)) % SLOTS + SLOTS) % SLOTS;
            final long dCount = ringCount[h] - ringCount[oldest];
            final long dTimeMs = ringTimeMs[h] - ringTimeMs[oldest];
            final long dNanos = ringNanos[h] - ringNanos[oldest];
            if (dNanos <= 0L) {
                return Gc.EMPTY;
            }
            final double windowSec = dNanos / 1.0E9;
            final double collectionsPerMin = dCount * 60.0 / windowSec;
            final double gcTimePercent = 100.0 * (dTimeMs / 1000.0) / windowSec; // collection time as % of elapsed wall time
            final double avgPauseMs = dCount > 0 ? (double) dTimeMs / dCount : 0.0;
            return new Gc(collectionsPerMin, gcTimePercent, avgPauseMs, windowSec);
        } catch (final Throwable t) {
            return Gc.EMPTY;
        }
    }

    /**
     * @param collectionsPerMin GC cycles per minute over the window
     * @param gcTimePercent      total collection time as a percentage of wall-clock (not a pause metric;
     *                           includes concurrent collector work)
     * @param avgPauseMs         legacy accessor name for mean MXBean collection time
     * @param windowSeconds      the actual window the figures were computed over
     */
    public record Gc(double collectionsPerMin, double gcTimePercent, double avgPauseMs, double windowSeconds) {
        public static final Gc EMPTY = new Gc(0.0, 0.0, 0.0, 0.0);

        public boolean hasData() {
            return this.windowSeconds > 0.0;
        }
    }
}
