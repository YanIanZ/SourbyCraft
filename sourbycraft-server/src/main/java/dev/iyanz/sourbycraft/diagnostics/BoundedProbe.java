package dev.iyanz.sourbycraft.diagnostics;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs a read that might block (it touches live server state) on a helper thread and gives up after a
 * deadline. The stall recorder must never be the thread that hangs: a probe that blocks costs one line
 * in the dump and nothing else, and while it is still blocked no second probe is started behind it.
 */
final class BoundedProbe implements AutoCloseable {

    // One thread and a queue of one: a probe is only submitted when the previous one has finished,
    // so the bound is never reached; it is there so nothing can ever queue without limit.
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1), runnable -> {
            final Thread thread = new Thread(runnable, "SourbyCraft-StallRecorder-Probe");
            thread.setDaemon(true);
            return thread;
        });
    private Future<?> pending;

    /** The probe's value, or {@code fallback} when it is still running after {@code timeoutMillis} or a previous probe has not finished. */
    synchronized <T> T call(final Supplier<T> probe, final long timeoutMillis, final T fallback) {
        if (this.pending != null && !this.pending.isDone()) {
            return fallback;
        }
        final Future<T> future = this.executor.submit(probe::get);
        this.pending = future;
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException | java.util.concurrent.ExecutionException failure) {
            return fallback;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return fallback;
        }
    }

    @Override
    public void close() {
        this.executor.shutdownNow();
    }
}
