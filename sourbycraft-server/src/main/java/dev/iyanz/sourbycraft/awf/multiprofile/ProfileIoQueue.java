package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs MultiProfile's repository and ownership steps one at a time on a shared I/O lane.
 *
 * <p>The engine used to submit every step straight to the Resource Governor storage lane and
 * serialise the steps with one lock held inside the lane worker. On the default one-thread lane a
 * join or quit burst therefore filled the lane's queue with profile steps, and AWF world commits
 * submitted behind them were rejected. This queue keeps profile steps in its own FIFO and hands
 * exactly one of them to the lane at a time: profile traffic holds at most one lane slot, and no lane
 * worker waits for a profile lock.</p>
 *
 * <p>A step's outcome is published after the next step was handed to the lane, so callbacks chained
 * on a profile future (they schedule entity work or queue further steps) never run while this
 * queue's single slot is held. Ordering is FIFO across all accounts; there is no per-account
 * parallelism, which keeps the serialisation the engine's admission checks rely on.</p>
 *
 * <p>The queue is bounded: {@link #offer} returns {@code false} when it is full instead of growing
 * without limit. A step the lane rejects (saturated or shut down) is completed through
 * {@link Step#rejected} and the next step is tried.</p>
 */
final class ProfileIoQueue {

    /** One step: {@link #run} does the work and returns how to publish its outcome. */
    interface Step {
        Runnable run();

        void rejected(RejectedExecutionException rejected);
    }

    private final Executor lane;
    private final int capacity;
    private final ArrayDeque<Step> waiting = new ArrayDeque<>();
    /** A step holds the slot: it was handed to the lane and has not finished. Guarded by {@code this}. */
    private boolean busy;

    ProfileIoQueue(final Executor lane, final int capacity) {
        this.lane = Objects.requireNonNull(lane, "lane");
        if (capacity < 1) throw new IllegalArgumentException("capacity " + capacity);
        this.capacity = capacity;
    }

    /** Queues a step; {@code false} when the queue is full and the step was not taken. */
    boolean offer(final Step step) {
        synchronized (this) {
            if (this.waiting.size() >= this.capacity) return false;
            this.waiting.addLast(step);
            if (this.busy) return true;
            this.busy = true;
        }
        dispatch();
        return true;
    }

    /** Hands the next waiting step to the lane, or frees the slot when none waits. */
    private void dispatch() {
        while (true) {
            final Step next;
            synchronized (this) {
                next = this.waiting.pollFirst();
                if (next == null) {
                    this.busy = false;
                    return;
                }
            }
            try {
                this.lane.execute(() -> runHeld(next));
                return;
            } catch (final RejectedExecutionException rejected) {
                // Callbacks of the rejected step may offer more steps; they queue behind the slot
                // this loop still holds and are tried next.
                next.rejected(rejected);
            }
        }
    }

    private void runHeld(final Step step) {
        Runnable publish = null;
        try {
            publish = step.run();
        } finally {
            dispatch();
        }
        if (publish != null) publish.run();
    }
}
