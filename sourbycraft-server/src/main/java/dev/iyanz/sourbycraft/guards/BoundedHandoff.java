package dev.iyanz.sourbycraft.guards;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one piece of work on the thread that must run it (a region, or the global tick) and makes
 * the calling thread wait for it, with a bound. Paper fired its legacy synchronous events through
 * a {@code Waitable} on the main thread and waited without limit; under region threading there is
 * no main thread, and an unbounded wait on a region that is stalled, or on an entity that is never
 * ticked again, would hold the waiting thread forever.
 *
 * <p>The wait has two phases. Up to {@code startNanos} for the work to start: when it has not
 * started by then it is abandoned and will never run ({@link Outcome#NOT_STARTED}). Once it has
 * started (plugin code is running on its owner), up to {@code runNanos} more for it to finish;
 * after that the caller stops waiting ({@link Outcome#OVERRAN}) and must not read any object the
 * work may still be changing. A target that reports the work can never run (the entity was
 * removed) ends the wait at once ({@link Outcome#RETIRED}).</p>
 *
 * <p>Never call this from a tick thread: waiting there for another region can deadlock two
 * regions. Callers decide that before choosing to hand off.</p>
 */
public final class BoundedHandoff {

    /** How a handoff ended. */
    public enum Outcome {
        /** The work ran to completion; its effects are visible to the caller. */
        COMPLETED,
        /** The work threw; the throwable is in {@link Result#failure()}. Its effects so far are visible. */
        FAILED,
        /** The work had not started within the start bound; it was abandoned and will never run. */
        NOT_STARTED,
        /** The work started but did not finish within the run bound; it may still be running. */
        OVERRAN,
        /** The target reported that the work can never run (for example, the entity was removed). */
        RETIRED,
        /** The target refused the work when it was posted. */
        REFUSED,
        /** The caller was interrupted while waiting; the interrupt flag is restored. */
        INTERRUPTED
    }

    /**
     * The result of a handoff.
     *
     * @param outcome how the handoff ended
     * @param failure what the work threw, for {@link Outcome#FAILED}; otherwise {@code null}
     */
    public record Result(Outcome outcome, Throwable failure) {

        /** Whether the work ran to completion (with or without throwing). */
        public boolean finished() {
            return this.outcome == Outcome.COMPLETED || this.outcome == Outcome.FAILED;
        }
    }

    /** Where work is posted: the owner runs {@code body}, or {@code retired} if it never can. */
    @FunctionalInterface
    public interface Target {

        /**
         * Posts the work.
         *
         * @param body the work, to be run on the owning thread
         * @param retired to be run instead of {@code body} when the owner can never run it
         * @return {@code false} when the owner refused the work outright (neither callback will run)
         */
        boolean post(Runnable body, Runnable retired);
    }

    private static final int PENDING = 0;
    private static final int RUNNING = 1;
    private static final int DONE = 2;
    private static final int ABANDONED = 3;

    private BoundedHandoff() {
    }

    /**
     * Posts {@code body} to {@code target} and waits as described in the class comment.
     *
     * @param target where the work runs
     * @param body the work
     * @param startNanos how long to wait for the work to start
     * @param runNanos how much longer to wait, once it has started, for it to finish
     * @return how the handoff ended
     */
    public static Result run(final Target target, final Runnable body, final long startNanos, final long runNanos) {
        final AtomicInteger state = new AtomicInteger(PENDING);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        final boolean[] retired = new boolean[1];
        final Runnable onOwner = () -> {
            if (!state.compareAndSet(PENDING, RUNNING)) {
                return; // abandoned by the caller, or already retired
            }
            started.countDown();
            try {
                body.run();
            } catch (final Throwable t) {
                failure[0] = t;
            } finally {
                state.set(DONE);
                ended.countDown();
            }
        };
        final Runnable onRetired = () -> {
            if (state.compareAndSet(PENDING, ABANDONED)) {
                retired[0] = true;
                started.countDown();
                ended.countDown();
            }
        };
        final boolean posted;
        try {
            posted = target.post(onOwner, onRetired);
        } catch (final RuntimeException e) {
            state.compareAndSet(PENDING, ABANDONED);
            return new Result(Outcome.REFUSED, e);
        }
        if (!posted) {
            state.compareAndSet(PENDING, ABANDONED);
            return new Result(Outcome.REFUSED, null);
        }
        try {
            if (!started.await(startNanos, TimeUnit.NANOSECONDS) && state.compareAndSet(PENDING, ABANDONED)) {
                return new Result(Outcome.NOT_STARTED, null);
            }
            if (!ended.await(runNanos, TimeUnit.NANOSECONDS)) {
                return new Result(Outcome.OVERRAN, null);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (state.compareAndSet(PENDING, ABANDONED) || state.get() != DONE) {
                return new Result(Outcome.INTERRUPTED, null);
            }
        }
        if (retired[0]) {
            return new Result(Outcome.RETIRED, null);
        }
        return failure[0] == null ? new Result(Outcome.COMPLETED, null) : new Result(Outcome.FAILED, failure[0]);
    }
}
