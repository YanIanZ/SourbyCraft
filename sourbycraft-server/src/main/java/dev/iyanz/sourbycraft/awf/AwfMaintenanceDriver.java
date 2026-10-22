package dev.iyanz.sourbycraft.awf;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Aurora World Fabric's periodic upkeep on its own daemon thread: once a second it starts the
 * commits that are due ({@link AwfEngine#maintainAll()}) and reports world operations that have
 * been stuck for minutes ({@code AuroraWorldsRuntime.watchOperations()}).
 *
 * <p>This used to ride on the metrics collector's thread, so a collector running late or stalled
 * also delayed commits, and a world could lose more than one commit interval of writes when the
 * server was under load (audit AWF-3). Here nothing else shares the thread.</p>
 *
 * <ul>
 *   <li>Fixed delay: the next pass starts one period after the previous one ended, and passes never
 *       overlap. A pass that is somehow already running when another is asked for is skipped, not
 *       run twice concurrently ({@link #overlaps()}).</li>
 *   <li>Each step runs in its own try/catch: one failing never skips the other or a later pass.
 *       Failures are logged at most once a minute per step, with the number not logged.</li>
 *   <li>{@link #close()} is idempotent; see there for what it waits for.</li>
 * </ul>
 *
 * <p>The steps do no I/O and do not wait for a commit: a commit is only handed to the storage
 * lane, whose bounded queue rejects rather than waits when full. They are not lock-free, though:
 * handing work to the lane takes the queue's lock, the operation watch takes the gate's monitor,
 * and a commit that fails at once (rejected, or the lane already shut down) completes its future
 * on this thread, so its warning is logged synchronously here. The final commit at shutdown does
 * not depend on this driver; {@link AwfEngine#closeAll()} commits every storage on the stopping
 * thread after the last save.</p>
 */
public final class AwfMaintenanceDriver implements AutoCloseable {

    /** The name of the driver's thread. */
    public static final String THREAD_NAME = "SourbyCraft-AWF-Maintenance";
    static final long PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1L);
    static final long ERROR_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1L);
    static final long STOP_WAIT_NANOS = TimeUnit.SECONDS.toNanos(2L);

    /** One named piece of upkeep. */
    record Step(String name, Runnable action) {
        Step {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(action, "action");
        }
    }

    /** Runs a pass repeatedly, each one period after the previous one ended. */
    @FunctionalInterface
    interface Scheduler {
        /**
         * Starts the schedule. Must leave nothing running when it throws.
         *
         * @return the handle that stops it
         */
        Cancellation start(Runnable pass, long periodNanos);
    }

    /** Stops a schedule. */
    interface Cancellation {
        /** Stops future passes without waiting; a pass in flight carries on. Called at most once. */
        void cancel();

        /** Waits up to {@code nanos} for the schedule's thread to finish; whether it did. */
        boolean await(long nanos) throws InterruptedException;
    }

    private final List<Step> steps;
    private final Scheduler scheduler;
    private final long periodNanos;
    private final long stopWaitNanos;
    private final LongSupplier nanoClock;
    private final BiConsumer<String, Throwable> errorLogger;
    /** Held for the whole of a pass; a second pass that cannot take it is skipped. */
    private final ReentrantLock passLock = new ReentrantLock();
    /** Opened once closed and no pass is running; every close() waits on the same one. */
    private final CountDownLatch terminated = new CountDownLatch(1);
    // Rate-limit state per step, guarded by passLock.
    private final boolean[] logged;
    private final long[] lastLoggedNanos;
    private final long[] unlogged;
    private final Object lifecycleLock = new Object();
    private boolean started;
    private volatile boolean stopped;
    private Cancellation cancellation;
    private final AtomicLong passes = new AtomicLong();
    private final AtomicLong overlaps = new AtomicLong();

    AwfMaintenanceDriver(final List<Step> steps, final Scheduler scheduler, final long periodNanos,
                         final LongSupplier nanoClock, final BiConsumer<String, Throwable> errorLogger) {
        this(steps, scheduler, periodNanos, STOP_WAIT_NANOS, nanoClock, errorLogger);
    }

    AwfMaintenanceDriver(final List<Step> steps, final Scheduler scheduler, final long periodNanos,
                         final long stopWaitNanos, final LongSupplier nanoClock,
                         final BiConsumer<String, Throwable> errorLogger) {
        this.steps = List.copyOf(steps);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (periodNanos <= 0L) throw new IllegalArgumentException("periodNanos must be positive");
        if (stopWaitNanos < 0L) throw new IllegalArgumentException("stopWaitNanos must not be negative");
        this.periodNanos = periodNanos;
        this.stopWaitNanos = stopWaitNanos;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.errorLogger = Objects.requireNonNull(errorLogger, "errorLogger");
        this.logged = new boolean[this.steps.size()];
        this.lastLoggedNanos = new long[this.steps.size()];
        this.unlogged = new long[this.steps.size()];
    }

    /** The server's driver: AWF commits, then the world-operation watch, once a second. Not started. */
    public static AwfMaintenanceDriver standard() {
        return new AwfMaintenanceDriver(List.of(
                new Step("AWF commits", AwfEngine::maintainAll),
                new Step("world operation watch", dev.iyanz.sourbycraft.awf.world.AuroraWorldsRuntime::watchOperations)),
            daemonThread(), PERIOD_NANOS, System::nanoTime, SourbyLogger::error);
    }

    /** One daemon thread, fixed delay. */
    static Scheduler daemonThread() {
        return daemonThread(() -> new ScheduledThreadPoolExecutor(1, task -> {
            final Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        }));
    }

    static Scheduler daemonThread(final Supplier<ScheduledThreadPoolExecutor> executors) {
        return (pass, period) -> {
            final ScheduledThreadPoolExecutor executor = executors.get();
            // The handle exists before anything is scheduled, and every failed exit shuts the
            // executor down, so a start that fails part-way leaves no thread behind.
            final Cancellation handle = new Cancellation() {
                @Override
                public void cancel() {
                    executor.shutdown();
                }

                @Override
                public boolean await(final long nanos) throws InterruptedException {
                    return executor.awaitTermination(nanos, TimeUnit.NANOSECONDS);
                }
            };
            try {
                executor.setRemoveOnCancelPolicy(true);
                executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
                executor.scheduleWithFixedDelay(pass, period, period, TimeUnit.NANOSECONDS);
                return handle;
            } catch (final Throwable failure) {
                executor.shutdownNow();
                throw failure;
            }
        };
    }

    /** Starts the schedule. Does nothing when already started or closed. A failed start can be retried. */
    public void start() {
        synchronized (this.lifecycleLock) {
            if (this.started || this.stopped) return;
            this.cancellation = this.scheduler.start(this::pass, this.periodNanos);
            this.started = true;
        }
    }

    /** Whether the schedule was started and not closed. */
    public boolean running() {
        synchronized (this.lifecycleLock) {
            return this.started && !this.stopped;
        }
    }

    /** Passes that ran their steps. */
    public long passes() {
        return this.passes.get();
    }

    /** Passes skipped because another was still running. */
    public long overlaps() {
        return this.overlaps.get();
    }

    /** Whether a thread is waiting for the pass lock (a close waiting for a pass in flight). */
    boolean closeWaitingForPass() {
        return this.passLock.hasQueuedThreads();
    }

    /** Waits until closed and quiescent; whether that happened within {@code nanos}. */
    boolean awaitTerminated(final long nanos) throws InterruptedException {
        return this.terminated.await(nanos, TimeUnit.NANOSECONDS);
    }

    /** One pass of every step; never throws. Skipped after {@link #close()} or while another runs. */
    void pass() {
        if (this.stopped) return;
        if (!this.passLock.tryLock()) {
            this.overlaps.incrementAndGet();
            return;
        }
        try {
            if (this.stopped) return;
            this.passes.incrementAndGet();
            for (int i = 0; i < this.steps.size(); ++i) {
                // Between steps too: a close during a step ends the pass after that step.
                if (i > 0 && this.stopped) return;
                try {
                    this.steps.get(i).action().run();
                } catch (final Throwable failure) {
                    this.report(i, failure);
                }
            }
        } finally {
            this.passLock.unlock();
            // A close that gave up waiting for this pass, or that ran inside it, finishes here.
            if (this.stopped) this.terminated.countDown();
        }
    }

    private void report(final int step, final Throwable failure) {
        final long now = this.nanoClock.getAsLong();
        if (this.logged[step] && now - this.lastLoggedNanos[step] < ERROR_LOG_INTERVAL_NANOS) {
            ++this.unlogged[step];
            return;
        }
        final long skipped = this.unlogged[step];
        this.logged[step] = true;
        this.lastLoggedNanos[step] = now;
        this.unlogged[step] = 0L;
        try {
            this.errorLogger.accept("Aurora World Fabric maintenance step '" + this.steps.get(step).name()
                + "' failed; retrying next pass" + (skipped > 0L ? " (" + skipped
                + " more failure(s) of this step since the last report were not logged)" : ""), failure);
        } catch (final Throwable ignored) {
            // A broken logger must not stop the upkeep it reports on.
        }
    }

    /**
     * Stops the schedule. Idempotent. No pass starts after the first close, and a pass in flight
     * ends after the step it is running. This waits, up to the stop timeout in total, for the
     * schedule's thread and for that pass; a second, concurrent close waits for the same end.
     *
     * <p>Called from one of the driver's own steps it does not wait (it would wait for itself):
     * the pass ends after that step. When the wait runs out the overrun is logged and the pass
     * still ends after its current step, which cannot be interrupted part-way.</p>
     */
    @Override
    public void close() {
        final boolean first;
        final Cancellation stopping;
        synchronized (this.lifecycleLock) {
            first = !this.stopped;
            this.stopped = true;
            stopping = this.cancellation;
            this.cancellation = null;
        }
        if (this.passLock.isHeldByCurrentThread()) {
            // Inside a step: stop the schedule, but waiting here would be waiting for ourselves.
            if (first) this.cancelQuietly(stopping);
            return;
        }
        final long deadline = this.nanoClock.getAsLong() + this.stopWaitNanos;
        try {
            if (!first) {
                // Another close is stopping it (or did): wait for the same end it waits for.
                this.terminated.await(this.stopWaitNanos, TimeUnit.NANOSECONDS);
                return;
            }
            this.cancelQuietly(stopping);
            if (stopping != null) stopping.await(this.remaining(deadline));
            if (this.passLock.tryLock(this.remaining(deadline), TimeUnit.NANOSECONDS)) {
                this.passLock.unlock();
                this.terminated.countDown();
            } else {
                this.errorLogger.accept("Aurora World Fabric maintenance pass still running "
                    + TimeUnit.NANOSECONDS.toMillis(this.stopWaitNanos) + " ms after close; it ends after its"
                    + " current step", null);
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private long remaining(final long deadline) {
        return Math.max(0L, deadline - this.nanoClock.getAsLong());
    }

    private void cancelQuietly(final Cancellation stopping) {
        if (stopping == null) return;
        try {
            stopping.cancel();
        } catch (final Throwable failure) {
            this.errorLogger.accept("Aurora World Fabric maintenance did not stop cleanly", failure);
        }
    }
}
