package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RegionTaskWaitLatencyTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final RegionTaskWaitLatency latency = new RegionTaskWaitLatency(this.now::get);

    private void waited(final RegionTaskWaitLatency.Kind kind, final long micros) {
        final long stamp = this.latency.stamp();
        this.now.addAndGet(micros * 1_000L);
        this.latency.record(kind, stamp);
    }

    @Test
    void nothingRecordedReadsAsZero() {
        final var snapshot = this.latency.snapshot(RegionTaskWaitLatency.Kind.TICK);
        assertEquals(0L, snapshot.executed());
        assertEquals(0.0, snapshot.meanMillis());
        assertEquals(0.0, snapshot.p50AtMostMillis());
        assertEquals(0.0, snapshot.p99AtMostMillis());
        assertEquals(0.0, snapshot.maxMillis());
        assertEquals(RegionTaskWaitLatency.BUCKETS, snapshot.buckets().length);
    }

    @Test
    void bucketsArePowersOfTwoMicroseconds() {
        assertEquals(0, RegionTaskWaitLatency.bucket(0));
        assertEquals(1, RegionTaskWaitLatency.bucket(1));
        assertEquals(2, RegionTaskWaitLatency.bucket(2));
        assertEquals(2, RegionTaskWaitLatency.bucket(3));
        assertEquals(3, RegionTaskWaitLatency.bucket(4));
        assertEquals(11, RegionTaskWaitLatency.bucket(1_500)); // [1024, 2048) us
        assertEquals(RegionTaskWaitLatency.BUCKETS - 1, RegionTaskWaitLatency.bucket(Long.MAX_VALUE));
        assertEquals(2.048, RegionTaskWaitLatency.upperMillis(11));
    }

    @Test
    void meanAndMaxAreExactPercentilesAreBucketUpperBounds() {
        for (int i = 0; i < 98; i++) waited(RegionTaskWaitLatency.Kind.TICK, 100); // bucket 7: [64,128) us
        waited(RegionTaskWaitLatency.Kind.TICK, 1_500); // bucket 11
        waited(RegionTaskWaitLatency.Kind.TICK, 40_000); // bucket 16: [32768, 65536) us

        final var snapshot = this.latency.snapshot(RegionTaskWaitLatency.Kind.TICK);
        assertEquals(100L, snapshot.executed());
        assertEquals((98 * 100 + 1_500 + 40_000) / 100.0 / 1_000.0, snapshot.meanMillis(), 1e-9);
        assertEquals(0.128, snapshot.p50AtMostMillis());
        assertEquals(2.048, snapshot.p99AtMostMillis(), "rank 99 of 100 is the 1.5 ms wait: its bucket ends at 2048 us");
        assertEquals(40.0, snapshot.maxMillis());
        assertEquals(98L, snapshot.buckets()[7]);
        assertEquals(1L, snapshot.buckets()[11]);
        assertEquals(1L, snapshot.buckets()[16]);
    }

    @Test
    void theKindsAreCountedApart() {
        waited(RegionTaskWaitLatency.Kind.CHUNK, 10);
        assertEquals(1L, this.latency.snapshot(RegionTaskWaitLatency.Kind.CHUNK).executed());
        assertEquals(0L, this.latency.snapshot(RegionTaskWaitLatency.Kind.TICK).executed());
    }

    @Test
    void anUnstampedTaskIsNotRecordedAndAStampIsNeverTheUnstampedMarker() {
        this.latency.record(RegionTaskWaitLatency.Kind.TICK, RegionTaskWaitLatency.UNSTAMPED);
        assertEquals(0L, this.latency.snapshot(RegionTaskWaitLatency.Kind.TICK).executed());

        this.now.set(RegionTaskWaitLatency.UNSTAMPED);
        assertNotEquals(RegionTaskWaitLatency.UNSTAMPED, this.latency.stamp());
    }

    @Test
    void aClockThatSeemsToRunBackwardsCountsAsNoWait() {
        final long stamp = this.latency.stamp();
        this.now.addAndGet(-5_000L);
        this.latency.record(RegionTaskWaitLatency.Kind.TICK, stamp);
        final var snapshot = this.latency.snapshot(RegionTaskWaitLatency.Kind.TICK);
        assertEquals(1L, snapshot.buckets()[0]);
        assertEquals(0.0, snapshot.maxMillis());
    }

    @Test
    void stampIfUnsetKeepsTheFirstStamp() {
        assertEquals(42L, RegionTaskWaitLatency.stampIfUnset(42L));
        assertNotEquals(RegionTaskWaitLatency.UNSTAMPED, RegionTaskWaitLatency.stampIfUnset(RegionTaskWaitLatency.UNSTAMPED));
    }

    @Test
    void aSnapshotIsACopy() {
        waited(RegionTaskWaitLatency.Kind.TICK, 1);
        final var snapshot = this.latency.snapshot(RegionTaskWaitLatency.Kind.TICK);
        final long[] before = snapshot.buckets();
        snapshot.buckets()[1] = 99L;
        waited(RegionTaskWaitLatency.Kind.TICK, 1);
        assertArrayEquals(before, snapshot.buckets());
    }
}
