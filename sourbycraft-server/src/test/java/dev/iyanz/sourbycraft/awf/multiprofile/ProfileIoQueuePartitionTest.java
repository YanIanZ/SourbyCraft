package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Build 55 (PM-091): the partitioned profile I/O queue. A hand-driven lane makes every interleaving explicit: steps
 * are handed to the lane, the test runs them one at a time, and the order they were handed over in is the order the
 * queue chose. No threads, no sleeps.
 */
class ProfileIoQueuePartitionTest {

    /** A lane that collects what it was handed; {@link #runNext} runs the oldest one. */
    static final class HandLane implements Executor {
        final ArrayDeque<Runnable> handed = new ArrayDeque<>();
        boolean reject;

        @Override
        public void execute(final Runnable task) {
            if (this.reject) throw new RejectedExecutionException("lane closed");
            this.handed.add(task);
        }

        void runNext() {
            final Runnable next = this.handed.poll();
            assertNotNull(next, "nothing handed to the lane");
            next.run();
        }
    }

    /** A step that records its run in a shared log and completes an outcome list when published. */
    static final class Named implements ProfileIoQueue.Step {
        final String name;
        final List<String> ran;
        final List<String> published;
        RejectedExecutionException rejected;

        Named(final String name, final List<String> ran, final List<String> published) {
            this.name = name;
            this.ran = ran;
            this.published = published;
        }

        @Override
        public Runnable run() {
            this.ran.add(this.name);
            return () -> this.published.add(this.name);
        }

        @Override
        public void rejected(final RejectedExecutionException rejected) {
            this.rejected = rejected;
        }
    }

    private static UUID inPartition(final int partition, final int partitions) {
        for (int i = 0; i < 100_000; i++) {
            final UUID candidate = new UUID(0x5A5A_0000_0000_0000L + i, 0x1234L);
            if (ProfileIoQueue.Key.partition(candidate, partitions) == partition) return candidate;
        }
        throw new AssertionError("no id for partition " + partition);
    }

    @Test
    void onePartitionIsTheBuild54Queue() {
        final HandLane lane = new HandLane();
        final List<String> ran = new ArrayList<>();
        final List<String> published = new ArrayList<>();
        final ProfileIoQueue queue = new ProfileIoQueue(lane, 8);
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a), new Named("a1", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(b), new Named("b1", ran, published)));
        assertTrue(queue.offer(new Named("barrier", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a), new Named("a2", ran, published)));
        // Exactly one step holds the single slot; the others wait whatever their keys.
        assertEquals(1, lane.handed.size());
        assertEquals(3, queue.waitingSteps());
        lane.runNext();
        lane.runNext();
        lane.runNext();
        lane.runNext();
        assertEquals(List.of("a1", "b1", "barrier", "a2"), ran);
        assertEquals(List.of("a1", "b1", "barrier", "a2"), published, "outcomes publish after the slot moved on, in order");
        assertEquals(0, queue.waitingSteps());
        assertEquals(0, queue.runningSteps());
    }

    @Test
    void differentPartitionsRunAtOnceAndEachKeyStaysFifo() {
        final HandLane lane = new HandLane();
        final List<String> ran = new ArrayList<>();
        final List<String> published = new ArrayList<>();
        final ProfileIoQueue queue = new ProfileIoQueue(lane, 16, 4, nanos -> {});
        final UUID a = inPartition(0, 4);
        final UUID b = inPartition(1, 4);
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a), new Named("a1", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a), new Named("a2", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(b), new Named("b1", ran, published)));
        // a1 holds partition 0; a2 waits behind it (same key); b1 is handed over at once: no head-of-line blocking.
        assertEquals(2, lane.handed.size());
        assertEquals(2, queue.runningSteps());
        assertEquals(1, queue.waitingSteps());
        lane.runNext(); // a1
        assertEquals(List.of("a1"), ran);
        // a2 is dispatched once a1 released partition 0.
        assertEquals(2, lane.handed.size());
        lane.runNext(); // b1
        lane.runNext(); // a2
        assertEquals(List.of("a1", "b1", "a2"), ran);
        assertEquals(0, queue.waitingSteps());
    }

    @Test
    void aLaterStepNeverOvertakesAnEarlierWaitingStepOfItsPartition() {
        final HandLane lane = new HandLane();
        final List<String> ran = new ArrayList<>();
        final List<String> published = new ArrayList<>();
        final ProfileIoQueue queue = new ProfileIoQueue(lane, 16, 4, nanos -> {});
        final UUID a = inPartition(0, 4);
        final UUID b = inPartition(1, 4);
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a), new Named("a1", ran, published)));
        // A step over both partitions waits for a1; a plain b step offered after it must not jump ahead of it on
        // partition 1 (FIFO per partition), even though partition 1 is free.
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a, b), new Named("ab", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(b), new Named("b1", ran, published)));
        assertEquals(1, lane.handed.size(), "only a1 runs: ab waits for a, b1 waits behind ab");
        lane.runNext(); // a1 -> ab dispatched
        assertEquals(1, lane.handed.size());
        lane.runNext(); // ab -> b1 dispatched
        lane.runNext(); // b1
        assertEquals(List.of("a1", "ab", "b1"), ran);
    }

    @Test
    void barrierWaitsForEveryRunningStepAndBlocksEveryLaterOne() {
        final HandLane lane = new HandLane();
        final List<String> ran = new ArrayList<>();
        final List<String> published = new ArrayList<>();
        final ProfileIoQueue queue = new ProfileIoQueue(lane, 16, 4, nanos -> {});
        final UUID a = inPartition(0, 4);
        final UUID b = inPartition(1, 4);
        final UUID c = inPartition(2, 4);
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a), new Named("a1", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(b), new Named("b1", ran, published)));
        assertTrue(queue.offer(new Named("barrier", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(c), new Named("c1", ran, published)));
        assertEquals(2, lane.handed.size(), "a1 and b1 run; the barrier waits for both; c1 waits behind the barrier");
        lane.runNext(); // a1
        assertEquals(1, lane.handed.size(), "b1 still runs: the barrier is not dispatched yet");
        lane.runNext(); // b1 -> barrier dispatched alone
        assertEquals(1, lane.handed.size());
        assertEquals(1, queue.runningSteps());
        lane.runNext(); // barrier -> c1
        lane.runNext();
        assertEquals(List.of("a1", "b1", "barrier", "c1"), ran);
    }

    @Test
    void capacityBoundsTheWaitingStepsAndRejectionReleasesPartitions() {
        final HandLane lane = new HandLane();
        final List<String> ran = new ArrayList<>();
        final List<String> published = new ArrayList<>();
        final ProfileIoQueue queue = new ProfileIoQueue(lane, 2, 2, nanos -> {});
        final UUID a = inPartition(0, 2);
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a), new Named("a1", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a), new Named("a2", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a), new Named("a3", ran, published)));
        assertFalse(queue.offer(ProfileIoQueue.Key.of(a), new Named("a4", ran, published)), "two wait: the third is refused");
        assertEquals(2, queue.peakWaiting());
        // The lane closes: the next step handed over is rejected, its partition freed, and the queue drains by rejection.
        lane.reject = true;
        final Named rejected = new Named("a2", ran, published);
        lane.runNext(); // a1 finishes; a2 (already queued) is handed over and rejected, then a3, both released
        assertEquals(List.of("a1"), ran);
        assertEquals(0, queue.runningSteps());
        assertEquals(0, queue.waitingSteps());
        assertSame(null, rejected.rejected, "the test's own Named was never offered");
    }

    /**
     * Review P2-6: at most {@code maxRunning} steps (the storage lane's threads) are in flight even when more partitions
     * are free; the next step is handed over as soon as one finishes, in arrival order.
     */
    @Test
    void inFlightIsCappedByTheLaneThreads() {
        final HandLane lane = new HandLane();
        final List<String> ran = new ArrayList<>();
        final List<String> published = new ArrayList<>();
        final ProfileIoQueue queue = new ProfileIoQueue(lane, 16, 4, 1, nanos -> {});
        assertEquals(1, queue.maxRunning());
        assertEquals(4, queue.partitions());
        final UUID a = inPartition(0, 4);
        final UUID b = inPartition(1, 4);
        final UUID c = inPartition(2, 4);
        assertTrue(queue.offer(ProfileIoQueue.Key.of(a), new Named("a1", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(b), new Named("b1", ran, published)));
        assertTrue(queue.offer(ProfileIoQueue.Key.of(c), new Named("c1", ran, published)));
        assertEquals(1, lane.handed.size(), "more steps in flight than the lane has threads");
        assertEquals(1, queue.runningSteps());
        assertEquals(2, queue.waitingSteps());
        lane.runNext();
        assertEquals(List.of("a1"), ran);
        assertEquals(1, lane.handed.size(), "the next step was not handed over when a slot freed");
        lane.runNext();
        lane.runNext();
        assertEquals(List.of("a1", "b1", "c1"), ran, "arrival order under the cap");
        assertEquals(0, queue.waitingSteps());
        // A cap above the partition count is the partition count; below 1 is refused.
        assertEquals(2, new ProfileIoQueue(lane, 1, 2, 8, nanos -> {}).maxRunning());
        assertThrows(IllegalArgumentException.class, () -> new ProfileIoQueue(lane, 1, 2, 0, nanos -> {}));
    }

    /**
     * Review P3-13: a lane that rejects drains a long queue by rejection without recursing once per step (the release
     * used to call dispatch from inside dispatch; a closed lane with thousands of waiting steps overflowed the stack).
     */
    @Test
    void aClosedLaneDrainsALongQueueWithoutRecursion() {
        final HandLane lane = new HandLane();
        final List<String> ran = new ArrayList<>();
        final List<String> published = new ArrayList<>();
        final int steps = 20_000;
        final ProfileIoQueue queue = new ProfileIoQueue(lane, steps, 1, nanos -> {});
        final List<Named> offered = new ArrayList<>(steps);
        final UUID a = UUID.randomUUID();
        for (int i = 0; i < steps; i++) {
            final Named step = new Named("s" + i, ran, published);
            offered.add(step);
            assertTrue(queue.offer(ProfileIoQueue.Key.of(a), step));
        }
        assertEquals(1, lane.handed.size(), "one partition: one step in flight");
        assertEquals(steps - 1, queue.waitingSteps());
        // The lane closes while the first step runs: finishing it dispatches the rest, each rejected in turn.
        lane.reject = true;
        lane.runNext();
        assertEquals(List.of("s0"), ran);
        assertEquals(0, queue.waitingSteps());
        assertEquals(0, queue.runningSteps());
        for (int i = 1; i < steps; i++) {
            assertNotNull(offered.get(i).rejected, "step " + i + " was neither run nor rejected");
        }
        assertSame(null, offered.get(0).rejected);
    }

    @Test
    void keysAndMasks() {
        assertTrue(ProfileIoQueue.Key.of().barrier());
        assertTrue(ProfileIoQueue.Key.of((UUID) null).barrier());
        final UUID id = UUID.randomUUID();
        assertFalse(ProfileIoQueue.Key.of(id, null, id).barrier(), "nulls skipped, duplicates folded");
        assertEquals(1L, Long.bitCount(ProfileIoQueue.Key.of(id).mask(8)));
        assertEquals((1L << 8) - 1L, ProfileIoQueue.Key.BARRIER.mask(8));
        assertEquals(-1L, ProfileIoQueue.Key.BARRIER.mask(64));
        final UUID[] many = new UUID[ProfileIoQueue.MAX_KEY_IDS + 1];
        for (int i = 0; i < many.length; i++) many[i] = UUID.randomUUID();
        assertTrue(ProfileIoQueue.Key.of(many).barrier(), "too many ids is the barrier");
        assertThrows(IllegalArgumentException.class, () -> new ProfileIoQueue(Runnable::run, 1, 0, nanos -> {}));
        assertThrows(IllegalArgumentException.class, () -> new ProfileIoQueue(Runnable::run, 1, 65, nanos -> {}));
        final AtomicReference<Long> waited = new AtomicReference<>();
        final ProfileIoQueue queue = new ProfileIoQueue(Runnable::run, 4, 2, waited::set);
        queue.offer(new Named("x", new ArrayList<>(), new ArrayList<>()));
        assertNotNull(waited.get(), "every dispatch reports its queue wait");
        assertTrue(waited.get() >= 0L);
    }
}
