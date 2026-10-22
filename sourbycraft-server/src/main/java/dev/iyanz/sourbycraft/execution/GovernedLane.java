package dev.iyanz.sourbycraft.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One budgeted lane of the Resource Governor: a fixed number of threads and a fixed-capacity queue.
 *
 * <p>Work beyond both is rejected with {@link RejectedExecutionException}. It is never run on the
 * caller — a caller-runs fallback would put the overflow on whatever thread submitted it, which for
 * work reaching here from a scheduler is exactly the thread the lane exists to protect. The budget
 * is what the operator (or the code's documented default) set; it is never resized at runtime.</p>
 */
public final class GovernedLane implements Executor {

    /** Counters at one instant. */
    public record Stats(String name, int threads, int queueCapacity, int active, int queued, int peakQueued,
                        long submitted, long rejected, long completed, long failed) {}

    private final String name;
    private final int threads;
    private final int queueCapacity;
    private final ThreadPoolExecutor pool;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicInteger peakQueued = new AtomicInteger();

    /**
     * @param threadPrefix thread name prefix, which {@link ExecutionLane} uses for attribution
     */
    public GovernedLane(final String name, final String threadPrefix, final int threads, final int queueCapacity) {
        this(name, threadPrefix, threads, queueCapacity, Thread.NORM_PRIORITY);
    }

    /**
     * @param threadPrefix   thread name prefix, which {@link ExecutionLane} uses for attribution
     * @param threadPriority priority of the lane's threads; a background CPU lane passes less than
     *                       {@link Thread#NORM_PRIORITY} so it yields to region tick threads
     */
    public GovernedLane(final String name, final String threadPrefix, final int threads, final int queueCapacity,
                        final int threadPriority) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("a lane needs at least one thread and one queue slot");
        }
        this.name = name;
        this.threads = threads;
        this.queueCapacity = queueCapacity;
        final AtomicInteger next = new AtomicInteger(1);
        this.pool = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity), task -> {
                final Thread thread = new Thread(task, threadPrefix + next.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(threadPriority);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        // Idle lanes hold no threads.
        this.pool.allowCoreThreadTimeOut(true);
    }

    @Override
    public void execute(final Runnable task) {
        this.submitted.incrementAndGet();
        try {
            this.pool.execute(new Counted(task));
        } catch (final RejectedExecutionException full) {
            this.rejected.incrementAndGet();
            throw full;
        }
        this.peakQueued.accumulateAndGet(this.pool.getQueue().size(), Math::max);
    }

    /**
     * Whether a task offered now would be queued rather than rejected. Best effort: another
     * submitter can take the last slot in between.
     */
    public boolean hasCapacity() {
        return !this.pool.isShutdown() && this.pool.getQueue().remainingCapacity() > 0;
    }

    public boolean isShutdown() {
        return this.pool.isShutdown();
    }

    /** Threads the lane holds right now (idle threads time out, so this can be below the budget). */
    public int poolSize() {
        return this.pool.getPoolSize();
    }

    public Stats stats() {
        return new Stats(this.name, this.threads, this.queueCapacity, this.pool.getActiveCount(),
            this.pool.getQueue().size(), this.peakQueued.get(), this.submitted.get(), this.rejected.get(),
            this.completed.get(), this.failed.get());
    }

    /**
     * Stops accepting work, interrupts running tasks and returns the queued ones without running
     * them. The returned objects are the ones passed to {@link #execute}, so a caller that queued a
     * {@code FutureTask} can cancel it. Does not wait for running tasks to finish.
     */
    public List<Runnable> shutdownNow() {
        final List<Runnable> pending = new ArrayList<>();
        for (final Runnable queued : this.pool.shutdownNow()) {
            pending.add(queued instanceof Counted counted ? counted.task : queued);
        }
        return pending;
    }

    /** Stops accepting work and waits up to {@code timeoutMillis} for queued work to finish. */
    public boolean shutdown(final long timeoutMillis) throws InterruptedException {
        this.pool.shutdown();
        return this.pool.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** Counts the outcome of one task; keeps the submitted task so {@link #shutdownNow} can return it. */
    private final class Counted implements Runnable {
        private final Runnable task;

        Counted(final Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            try {
                this.task.run();
                GovernedLane.this.completed.incrementAndGet();
            } catch (final Throwable thrown) {
                GovernedLane.this.failed.incrementAndGet();
                throw thrown;
            }
        }
    }
}
