package dev.iyanz.sourbycraft.perf;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * SourbyCraft runtime GC-health tracker. GC pauses are invisible in TPS and MSPT — the scheduler
 * rate stays ~20 and a region's tick time doesn't include a stop-the-world pause that froze every
 * thread — yet they are one of the most common real causes of "the server feels laggy". This samples
 * the JVM's {@link GarbageCollectorMXBean}s on a fixed cadence and keeps a rolling ~60s window so
 * {@link #snapshot()} can report the honest GC overhead: collections/min, wall-clock %, and the
 * average/worst pause.
 *
 * <p>Self-contained: a single daemon thread, no allocation on the sample path beyond the ring writes,
 * and every read degrades to zeros rather than throwing.
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

    /** Idempotent. Starts the sampler daemon. Pass a nanoTime supplier so scripts/tests stay pure. */
    public static synchronized void start() {
        if (started) return;
        started = true;
        sampleOnce(System.nanoTime()); // seed slot 0 immediately so an early snapshot has a baseline
        final Thread t = new Thread(() -> {
            while (true) {
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
        t.start();
    }

    private static void sampleOnce(final long nowNanos) {
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
    }

    /** @return GC health over the rolling window; all-zero (never null) when there is no data yet. */
    public static Gc snapshot() {
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
            final double gcTimePercent = 100.0 * (dTimeMs / 1000.0) / windowSec; // total STW ms as % of wall
            final double avgPauseMs = dCount > 0 ? (double) dTimeMs / dCount : 0.0;
            return new Gc(collectionsPerMin, gcTimePercent, avgPauseMs, windowSec);
        } catch (final Throwable t) {
            return Gc.EMPTY;
        }
    }

    /**
     * @param collectionsPerMin GC cycles per minute over the window
     * @param gcTimePercent      total stop-the-world time as a percentage of wall-clock (the honest
     *                           "how much of real time is lost to GC")
     * @param avgPauseMs         mean pause length across the window's collections
     * @param windowSeconds      the actual window the figures were computed over
     */
    public record Gc(double collectionsPerMin, double gcTimePercent, double avgPauseMs, double windowSeconds) {
        public static final Gc EMPTY = new Gc(0.0, 0.0, 0.0, 0.0);

        public boolean hasData() {
            return this.windowSeconds > 0.0;
        }
    }
}
