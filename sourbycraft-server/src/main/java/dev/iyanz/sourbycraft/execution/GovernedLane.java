package dev.iyanz.sourbycraft.execution;

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
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        // Idle lanes hold no threads.
        this.pool.allowCoreThreadTimeOut(true);
    }

    @Override
    public void execute(final Runnable task) {
        this.submitted.incrementAndGet();
        try {
            this.pool.execute(() -> {
                try {
                    task.run();
                    this.completed.incrementAndGet();
                } catch (final Throwable thrown) {
                    this.failed.incrementAndGet();
                    throw thrown;
                }
            });
        } catch (final RejectedExecutionException full) {
            this.rejected.incrementAndGet();
            throw full;
        }
        this.peakQueued.accumulateAndGet(this.pool.getQueue().size(), Math::max);
    }

    public Stats stats() {
        return new Stats(this.name, this.threads, this.queueCapacity, this.pool.getActiveCount(),
            this.pool.getQueue().size(), this.peakQueued.get(), this.submitted.get(), this.rejected.get(),
            this.completed.get(), this.failed.get());
    }

    /** Stops accepting work and waits up to {@code timeoutMillis} for queued work to finish. */
    public boolean shutdown(final long timeoutMillis) throws InterruptedException {
        this.pool.shutdown();
        return this.pool.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
