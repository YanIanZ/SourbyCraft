package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The recorder behind region-file save latency: percentiles, window, ring bound, concurrency. */
class RegionFileSaveLatencyTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    /** A clock the test moves by hand; starts well away from zero, as System.nanoTime may. */
    private final AtomicLong now = new AtomicLong(123_456_789_000L);
    private final RegionFileSaveLatency latency = new RegionFileSaveLatency(this.now::get);

    private void save(final RegionFileSaveLatency.Recorder recorder, final long nanos, final boolean failed) {
        final long end = this.now.get();
        recorder.record(end - nanos, end, failed);
    }

    private RegionFileSaveLatency.Snapshot only() {
        final List<RegionFileSaveLatency.Snapshot> all = this.latency.snapshot();
        assertEquals(1, all.size(), all.toString());
        return all.get(0);
    }

    @Test
    void nearestRankPercentilesAndMaxOverTheWindow() {
        final var recorder = this.latency.recorder("world", "minecraft:overworld", "chunk");
        for (int ms = 100; ms >= 1; ms--) {
            save(recorder, ms * MS, false);
        }
        final var s = only();
        assertEquals(100, s.saves());
        assertEquals(0, s.failures());
        assertEquals(100, s.windowSamples());
        assertEquals(50.0, s.p50Millis(), 1e-9);
        assertEquals(99.0, s.p99Millis(), 1e-9);
        assertEquals(100.0, s.maxMillis(), 1e-9);
        assertEquals(100.0, s.maxEverMillis(), 1e-9);
        assertEquals("world", s.world());
        assertEquals("minecraft:overworld", s.dimension());
        assertEquals("chunk", s.type());
    }

    @Test
    void subMillisecondSavesKeepMicrosecondResolution() {
        final var recorder = this.latency.recorder("w", "d", "chunk");
        save(recorder, 250_000L, false); // 250 µs
        save(recorder, 1_500L, false);   // 1.5 µs, rounds up to 2 µs, never to 0
        final var s = only();
        assertEquals(0.002, s.p50Millis(), 1e-9);
        assertEquals(0.25, s.maxMillis(), 1e-9);
    }

    @Test
    void samplesOlderThanTheWindowLeaveThePercentilesButNotTheTotals() {
        final var recorder = this.latency.recorder("w", "d", "chunk");
        save(recorder, 900 * MS, true);
        this.now.addAndGet((RegionFileSaveLatency.WINDOW_SECONDS + 2) * SECOND);
        save(recorder, 3 * MS, false);
        save(recorder, 5 * MS, false);

        final var s = only();
        assertEquals(3, s.saves(), "counts are since start");
        assertEquals(1, s.failures(), "and so are failures");
        assertEquals(2, s.windowSamples(), "the 900 ms save ended more than a window ago");
        assertEquals(5.0, s.maxMillis(), 1e-9, "window max ignores the old save");
        assertEquals(900.0, s.maxEverMillis(), 1e-9, "the all-time max keeps it");

        this.now.addAndGet((RegionFileSaveLatency.WINDOW_SECONDS + 2) * SECOND);
        final var idle = only();
        assertEquals(0, idle.windowSamples());
        assertEquals(0.0, idle.p50Millis());
        assertEquals(0.0, idle.p99Millis());
        assertEquals(0.0, idle.maxMillis());
        assertEquals(3, idle.saves());
    }

    @Test
    void theRingKeepsOnlyTheLastCapacitySamples() {
        final var recorder = this.latency.recorder("w", "d", "chunk");
        final int total = RegionFileSaveLatency.CAPACITY + 500;
        // The first 500 are slow, the last CAPACITY fast: only the fast ones are retained.
        for (int i = 0; i < total; i++) {
            save(recorder, (i < 500 ? 40 : 2) * MS, false);
        }
        final var s = only();
        assertEquals(total, s.saves());
        assertEquals(RegionFileSaveLatency.CAPACITY, s.windowSamples());
        assertEquals(2.0, s.maxMillis(), 1e-9);
        assertEquals(40.0, s.maxEverMillis(), 1e-9);
    }

    @Test
    void recordersAreSplitPerWorldDimensionAndTypeAndReusedPerKey() {
        final var overworld = this.latency.recorder("world", "minecraft:overworld", "chunk");
        assertSame(overworld, this.latency.recorder("world", "minecraft:overworld", "chunk"),
            "a reloaded world's new storage keeps the same recorder");
        final var poi = this.latency.recorder("world", "minecraft:overworld", "poi");
        final var nether = this.latency.recorder("world_nether", "minecraft:the_nether", "chunk");
        this.latency.recorder("unused", "minecraft:overworld", "chunk");
        assertNotSame(overworld, poi);

        save(overworld, 1 * MS, false);
        save(poi, 7 * MS, true);
        save(nether, 3 * MS, false);
        save(nether, 4 * MS, false);

        final List<RegionFileSaveLatency.Snapshot> all = this.latency.snapshot();
        assertEquals(3, all.size(), "a recorder that never timed a save is not listed: " + all);
        assertEquals(List.of("world/chunk", "world/poi", "world_nether/chunk"),
            all.stream().map(s -> s.world() + "/" + s.type()).toList(), "sorted by world, dimension, type");
        assertEquals(1, all.get(1).failures());
        assertEquals(2, all.get(2).saves());
        assertThrows(UnsupportedOperationException.class, () -> all.add(all.get(0)), "the snapshot is read-only");
    }

    @Test
    void durationsSaturateAndNegativeElapsedCountsAsZero() {
        final var recorder = this.latency.recorder("w", "d", "chunk");
        final long twoHours = TimeUnit.HOURS.toNanos(2);
        save(recorder, twoHours, false);
        recorder.record(this.now.get(), this.now.get() - 5 * MS, false); // a clock that went backwards
        final var s = only();
        assertEquals(2, s.saves());
        assertEquals(0xFFFF_FFFFL / 1000.0, s.maxMillis(), 1e-9, "window samples saturate at 2^32-1 µs");
        assertEquals(twoHours / 1_000_000.0, s.maxEverMillis(), 1e-9, "the all-time max is exact");
    }

    /** Runs {@code perThread} records on each of {@code threads} threads released together. */
    private void hammer(final RegionFileSaveLatency.Recorder recorder, final int threads, final int perThread)
        throws InterruptedException {
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> workers = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        final long end = this.now.get();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            final Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // thread t, save i: (t + 1) ms, and every other save fails
                        recorder.record(end - (id + 1) * MS, end, (i & 1) == 0);
                    }
                } catch (final Throwable failed) {
                    synchronized (errors) {
                        errors.add(failed);
                    }
                }
            }, "RegionFileSaveLatencyTest-" + t);
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (final Thread thread : workers) {
            thread.join(TimeUnit.SECONDS.toMillis(30));
            assertTrue(!thread.isAlive(), thread.getName() + " did not finish");
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void concurrentWritersWithinCapacityLoseNothing() throws InterruptedException {
        final var recorder = this.latency.recorder("w", "d", "chunk");
        final int threads = 8;
        final int perThread = RegionFileSaveLatency.CAPACITY / threads;
        hammer(recorder, threads, perThread);

        final var s = only();
        assertEquals(RegionFileSaveLatency.CAPACITY, s.saves());
        assertEquals(RegionFileSaveLatency.CAPACITY / 2, s.failures());
        assertEquals(RegionFileSaveLatency.CAPACITY, s.windowSamples(), "every slot holds exactly one sample");
        assertEquals(threads, s.maxMillis(), 1e-9);
        assertEquals(threads, s.maxEverMillis(), 1e-9);
        // perThread samples of each of 1..8 ms: the median is 4 ms, p99 is 8 ms.
        assertEquals(4.0, s.p50Millis(), 1e-9);
        assertEquals(8.0, s.p99Millis(), 1e-9);
    }

    @Test
    void concurrentWritersBeyondCapacityKeepExactTotalsAndAFullRing() throws InterruptedException {
        final var recorder = this.latency.recorder("w", "d", "chunk");
        final int threads = 8;
        final int perThread = 20_000;
        hammer(recorder, threads, perThread);

        final var s = only();
        assertEquals((long) threads * perThread, s.saves(), "the count is exact under contention");
        assertEquals((long) threads * perThread / 2, s.failures(), "so is the failure count");
        assertEquals(threads, s.maxEverMillis(), 1e-9, "and the all-time max");
        // After every writer stored, every slot holds some writer's sample: the documented loss is
        // which samples survive, never an empty or torn slot.
        assertEquals(RegionFileSaveLatency.CAPACITY, s.windowSamples());
        assertTrue(s.maxMillis() >= 1.0 && s.maxMillis() <= threads, "only real durations: " + s);
        assertTrue(s.p50Millis() >= 1.0 && s.p50Millis() <= threads, "only real durations: " + s);
    }
}
