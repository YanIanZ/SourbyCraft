package dev.iyanz.sourbycraft.util;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Blocking administrative I/O only. Fixed admission bound, no queue, never blocks the caller. */
final class BoundedIoExecutor extends AbstractExecutorService {
    private final ExecutorService delegate = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("SourbyCraft-IO-", 0L).factory());
    private final Semaphore permits;

    BoundedIoExecutor(final int maximumTasks) {
        if (maximumTasks < 1) throw new IllegalArgumentException("maximumTasks must be positive");
        this.permits = new Semaphore(maximumTasks);
    }

    @Override
    public void execute(final Runnable task) {
        java.util.Objects.requireNonNull(task, "task");
        if (!permits.tryAcquire()) throw new RejectedExecutionException("SourbyCraft I/O capacity reached");
        try {
            delegate.execute(() -> {
                try { task.run(); }
                finally { permits.release(); }
            });
        } catch (RuntimeException | Error failure) {
            permits.release();
            throw failure;
        }
    }

    @Override public void shutdown() { delegate.shutdown(); }
    @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
    @Override public boolean isShutdown() { return delegate.isShutdown(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
