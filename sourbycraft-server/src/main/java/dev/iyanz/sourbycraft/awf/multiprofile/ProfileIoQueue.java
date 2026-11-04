package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongConsumer;

/**
 * Runs MultiProfile's repository and ownership steps on a shared I/O lane, in FIFO order per account and without
 * one account's slow step holding every other account back (PM-091, Build 55).
 *
 * <p>The engine used to submit every step straight to the Resource Governor storage lane and serialise the steps
 * with one lock held inside the lane worker. On the default one-thread lane a join or quit burst therefore filled
 * the lane's queue with profile steps, and AWF world commits submitted behind them were rejected. Build 51 gave
 * profile steps their own FIFO holding one lane slot at a time. Build 55 keeps that and adds partitions.</p>
 *
 * <p><b>Keys and partitions.</b> A step names the resources it touches ({@link Key}: account and profile ids);
 * each id hashes to one of {@code partitions} partitions. Two steps conflict when they share a partition; a step
 * with no key ({@link Key#BARRIER}) conflicts with every step. Dispatch walks the waiting steps in arrival order
 * and hands a step to the lane when none of its partitions is held by a running step <em>and</em> no earlier
 * waiting step shares a partition with it (that earlier step keeps its place: FIFO per partition, so per account,
 * is preserved; a later unrelated step may overtake a blocked one, which is the head-of-line relief). With
 * {@code partitions == 1} every step shares the single partition and the queue behaves exactly like Build 54: one
 * step at a time, FIFO over all accounts.</p>
 *
 * <p><b>No deadlock.</b> A step acquires all of its partitions at dispatch time, under the queue lock, or none;
 * a running step never waits for another step (it runs to completion on the lane and its outcome is published
 * after its partitions are released), so no step holds a partition while waiting for one. The oldest waiting
 * step is shadowed by nothing, its partitions are held only by running steps, and running steps finish (their
 * work is bounded by the repository's own deadlines and every exception is caught), so it is dispatched once
 * they do; by induction every waiting step is. A barrier step waits for the running steps it conflicts with and
 * blocks every later step that shares a partition with it (all of them) until it has run, so it is never
 * starved by a stream of single-partition steps.</p>
 *
 * <p><b>Bounds.</b> At most {@code capacity} steps wait; {@link #offer} returns {@code false} when it is full.
 * At most {@code min(partitions, maxRunning)} steps run at once (one per partition, and never more than the
 * storage lane has threads: a step handed to the lane beyond its threads would only sit in the lane's queue, in front
 * of AWF world commits), so profile traffic holds at most that many lane slots; on the default one-thread storage
 * lane that is still one step at a time, and the relief appears when {@code aurora.scheduler.storage-threads} is
 * above one. A step the lane rejects (saturated or shut down) is completed through {@link Step#rejected} and its
 * partitions are released; the dispatch loop goes on with the next runnable step without recursing.</p>
 *
 * <p><b>Metrics.</b> The time a step waited from {@link #offer} to its hand-off to the lane is reported to the
 * {@code waitObserver} (nanoseconds) for the {@code /perf multiprofile} queue-wait percentiles.</p>
 */
final class ProfileIoQueue {

    /** One step: {@link #run} does the work and returns how to publish its outcome. */
    interface Step {
        Runnable run();

        void rejected(RejectedExecutionException rejected);
    }

    /** Most ids one key names; a step touching more uses the barrier. */
    static final int MAX_KEY_IDS = 6;

    /**
     * The resources a step touches, as account and profile ids. {@link #BARRIER} is a step that conflicts with
     * every other step (unknown or many resources, store-wide operations).
     */
    static final class Key {
        static final Key BARRIER = new Key(new UUID[0], true);

        private final UUID[] ids;
        private final boolean barrier;

        private Key(final UUID[] ids, final boolean barrier) {
            this.ids = ids;
            this.barrier = barrier;
        }

        /** A key over the given ids; {@code null} entries are skipped. More than {@link #MAX_KEY_IDS} ids is the barrier. */
        static Key of(final UUID... ids) {
            final List<UUID> kept = new ArrayList<>(ids.length);
            for (final UUID id : ids) {
                if (id != null && !kept.contains(id)) kept.add(id);
            }
            if (kept.isEmpty() || kept.size() > MAX_KEY_IDS) return BARRIER;
            return new Key(kept.toArray(new UUID[0]), false);
        }

        boolean barrier() {
            return this.barrier;
        }

        /** The partition mask of this key for {@code partitions} partitions (all bits for the barrier). */
        long mask(final int partitions) {
            if (this.barrier) return partitions >= 64 ? -1L : (1L << partitions) - 1L;
            long mask = 0L;
            for (final UUID id : this.ids) mask |= 1L << partition(id, partitions);
            return mask;
        }

        static int partition(final UUID id, final int partitions) {
            final long bits = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
            final int hash = (int) (bits ^ (bits >>> 32));
            return Math.floorMod(hash, partitions);
        }

        @Override
        public String toString() {
            return this.barrier ? "barrier" : List.of(this.ids).toString();
        }
    }

    /** A waiting step with its partition mask and arrival time. */
    private static final class Waiting {
        final Step step;
        final long mask;
        final long queuedNanos;

        Waiting(final Step step, final long mask, final long queuedNanos) {
            this.step = step;
            this.mask = mask;
            this.queuedNanos = queuedNanos;
        }
    }

    /** Most partitions; the mask is a {@code long}. */
    static final int MAX_PARTITIONS = 64;

    private final Executor lane;
    private final int capacity;
    private final int partitions;
    /** Most steps handed to the lane and not finished (the lane's thread budget). */
    private final int maxRunning;
    private final LongConsumer waitObserver;
    /** Waiting steps in arrival order. Guarded by {@code this}. */
    private final ArrayList<Waiting> waiting = new ArrayList<>();
    /** Partitions held by steps handed to the lane and not finished. Guarded by {@code this}. */
    private long busy;
    private int running;
    private int peakWaiting;

    ProfileIoQueue(final Executor lane, final int capacity) {
        this(lane, capacity, 1, nanos -> {});
    }

    ProfileIoQueue(final Executor lane, final int capacity, final int partitions, final LongConsumer waitObserver) {
        this(lane, capacity, partitions, partitions, waitObserver);
    }

    /**
     * @param maxRunning most steps in flight on the lane at once (its thread count); at least 1. With fewer threads
     *     than partitions the cap, not the partition count, bounds the lane slots profile traffic can hold.
     */
    ProfileIoQueue(final Executor lane, final int capacity, final int partitions, final int maxRunning, final LongConsumer waitObserver) {
        this.lane = Objects.requireNonNull(lane, "lane");
        if (capacity < 1) throw new IllegalArgumentException("capacity " + capacity);
        if (partitions < 1 || partitions > MAX_PARTITIONS) throw new IllegalArgumentException("partitions " + partitions);
        if (maxRunning < 1) throw new IllegalArgumentException("maxRunning " + maxRunning);
        this.capacity = capacity;
        this.partitions = partitions;
        this.maxRunning = Math.min(partitions, maxRunning);
        this.waitObserver = Objects.requireNonNull(waitObserver, "waitObserver");
    }

    int partitions() {
        return this.partitions;
    }

    /** Most steps this queue hands to the lane at once. */
    int maxRunning() {
        return this.maxRunning;
    }

    /** Queues a barrier step; {@code false} when the queue is full and the step was not taken. */
    boolean offer(final Step step) {
        return offer(Key.BARRIER, step);
    }

    /** Queues a step under its key; {@code false} when the queue is full and the step was not taken. */
    boolean offer(final Key key, final Step step) {
        Objects.requireNonNull(step, "step");
        synchronized (this) {
            if (this.waiting.size() >= this.capacity) return false;
            this.waiting.add(new Waiting(step, key.mask(this.partitions), System.nanoTime()));
            if (this.waiting.size() > this.peakWaiting) this.peakWaiting = this.waiting.size();
        }
        dispatch();
        return true;
    }

    /** Steps waiting for a partition right now. */
    synchronized int waitingSteps() {
        return this.waiting.size();
    }

    /** Steps handed to the lane and not finished. */
    synchronized int runningSteps() {
        return this.running;
    }

    /** Most steps that waited at once since start. */
    synchronized int peakWaiting() {
        return this.peakWaiting;
    }

    /**
     * Hands every runnable waiting step to the lane. A step is runnable when no running step and no earlier
     * waiting step shares a partition with it and fewer than {@code maxRunning} steps are in flight. Runs until no
     * waiting step is runnable. A rejection by the lane releases the step's partitions under the lock and the loop
     * simply continues (review P3-13: the release used to call {@code dispatch()} again from inside this loop, one
     * nesting level per rejected step, so a closed lane with a long queue recursed as deep as the queue).
     */
    private void dispatch() {
        while (true) {
            final Waiting next;
            synchronized (this) {
                next = pickRunnable();
                if (next == null) return;
                this.busy |= next.mask;
                this.running++;
            }
            this.waitObserver.accept(Math.max(0L, System.nanoTime() - next.queuedNanos));
            try {
                this.lane.execute(() -> runHeld(next));
            } catch (final RejectedExecutionException rejected) {
                synchronized (this) {
                    this.busy &= ~next.mask;
                    this.running--;
                }
                // Callbacks of the rejected step may offer more steps; they join the waiting list and this loop
                // picks them up (or a concurrent dispatch does).
                next.step.rejected(rejected);
            }
        }
    }

    /** Guarded by {@code this}: the oldest waiting step that is not blocked by a running or an earlier waiting step. */
    private Waiting pickRunnable() {
        if (this.running >= this.maxRunning) return null;
        long shadow = this.busy;
        for (int i = 0; i < this.waiting.size(); i++) {
            final Waiting candidate = this.waiting.get(i);
            if ((candidate.mask & shadow) == 0L) {
                this.waiting.remove(i);
                return candidate;
            }
            shadow |= candidate.mask;
            if (shadow == -1L || (this.partitions < 64 && shadow == (1L << this.partitions) - 1L)) return null;
        }
        return null;
    }

    private void release(final long mask) {
        synchronized (this) {
            this.busy &= ~mask;
            this.running--;
        }
        dispatch();
    }

    private void runHeld(final Waiting held) {
        Runnable publish = null;
        try {
            publish = held.step.run();
        } finally {
            // The partitions are released before the outcome is published: callbacks chained on a profile future
            // (they schedule entity work or queue further steps) never run while this step holds a partition.
            release(held.mask);
        }
        if (publish != null) publish.run();
    }
}
