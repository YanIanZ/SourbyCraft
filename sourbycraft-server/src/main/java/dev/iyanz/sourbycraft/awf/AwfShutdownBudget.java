package dev.iyanz.sourbycraft.awf;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.function.LongSupplier;

/**
 * The one time budget every AWF wait at shutdown shares ({@code aurora.awf.shutdown-deadline-ms}).
 *
 * <p>Created once, by {@link AwfEngine#beginShutdown()}, before the first AWF wait of the shutdown
 * (the storage lane draining, the per-world flush and close during the region saves, the chunk I/O
 * drain, the final close of every storage and the release of the backend's leases), so a slow
 * backend cannot get a fresh budget per storage, per attempt or per socket: everything is measured
 * against one monotonic deadline. A lock or a socket that blocks cannot extend it either: locks are
 * taken with the remaining time ({@link #tryLock}), every Redis call's timeouts are cut to it, and
 * when it passes the {@linkplain #onExpiry expiry actions} run once — the engine fences every
 * world (a queued commit commits nothing), stops lease renewal and aborts every connection that is
 * checked out, so a call blocked in a socket read or write fails at once.</p>
 *
 * <p>After expiry no new commit attempt starts. Nothing that missed the deadline is ever reported
 * as saved; see {@link AwfShutdownReport}.</p>
 */
public final class AwfShutdownBudget {

    private static final ScheduledExecutorService TIMER =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "SourbyCraft-Storage-awf-shutdown-deadline");
            thread.setDaemon(true);
            return thread;
        });

    private final LongSupplier clock;
    private final long startNanos;
    private final long totalNanos;
    private final long deadlineNanos;
    private final AtomicBoolean fired = new AtomicBoolean();
    private final List<Runnable> expiryActions = new CopyOnWriteArrayList<>();
    private volatile ScheduledFuture<?> timer;

    /**
     * @param totalMillis the budget, at least 1
     * @param clock a monotonic nanosecond clock; {@link System#nanoTime} in production, which is what the
     *     Redis client's deadline is measured with
     */
    public AwfShutdownBudget(final long totalMillis, final LongSupplier clock) {
        if (totalMillis < 1) throw new IllegalArgumentException("shutdown deadline must be at least 1 ms");
        this.clock = clock;
        this.startNanos = clock.getAsLong();
        this.totalNanos = TimeUnit.MILLISECONDS.toNanos(totalMillis);
        this.deadlineNanos = this.startNanos + this.totalNanos;
    }

    /** Fires the expiry actions at the deadline from a daemon timer, even if nothing is waiting then. */
    AwfShutdownBudget startTimer() {
        if (this.timer == null) {
            this.timer = TIMER.schedule(this::fireExpiry, Math.max(0L, remainingNanos()), TimeUnit.NANOSECONDS);
        }
        return this;
    }

    /** The deadline on the clock's scale ({@link System#nanoTime} in production). */
    public long deadlineNanos() {
        return this.deadlineNanos;
    }

    public long totalMillis() {
        return TimeUnit.NANOSECONDS.toMillis(this.totalNanos);
    }

    public long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(this.clock.getAsLong() - this.startNanos);
    }

    /** Time left, never negative. */
    public long remainingNanos() {
        return Math.max(0L, this.deadlineNanos - this.clock.getAsLong());
    }

    /** Whether the deadline has passed; the first caller that sees it runs the expiry actions. */
    public boolean expired() {
        if (this.fired.get()) return true;
        if (remainingNanos() > 0L) return false;
        fireExpiry();
        return true;
    }

    /**
     * Registers an action for the moment the deadline passes; runs it now if it already has. Actions
     * must not block: they run on the timer thread or on whichever thread first sees the expiry.
     */
    public void onExpiry(final Runnable action) {
        this.expiryActions.add(action);
        if (this.fired.get() && this.expiryActions.remove(action)) runQuietly(action);
    }

    /**
     * AWF's shutdown is over (every storage closed and reported): the deadline no longer applies, so
     * its expiry actions never run and the timer is cancelled. Keeps a slow vanilla step after AWF
     * from producing an AWF deadline warning.
     */
    public void finish() {
        if (!this.fired.compareAndSet(false, true)) return;
        this.expiryActions.clear();
        final ScheduledFuture<?> pending = this.timer;
        if (pending != null) pending.cancel(false);
    }

    /** Runs the expiry actions once. */
    public void fireExpiry() {
        if (!this.fired.compareAndSet(false, true)) return;
        for (final Runnable action : this.expiryActions) {
            if (this.expiryActions.remove(action)) runQuietly(action);
        }
    }

    private static void runQuietly(final Runnable action) {
        try {
            action.run();
        } catch (final RuntimeException failed) {
            dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF shutdown deadline action failed: " + failed);
        }
    }

    /**
     * Takes {@code lock} if it becomes free within the remaining time. Never waits past the deadline;
     * an interrupt counts as not acquired (the interrupt flag is kept).
     */
    public boolean tryLock(final Lock lock) {
        try {
            if (lock.tryLock()) return true;
            final long remaining = remainingNanos();
            return remaining > 0L && lock.tryLock(remaining, TimeUnit.NANOSECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
