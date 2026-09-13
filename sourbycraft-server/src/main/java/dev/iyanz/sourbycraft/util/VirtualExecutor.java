package dev.iyanz.sourbycraft.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Virtual-thread-based executor replacing AsyncExecutor and SwmIoExecutor.
 * Java 25 blocking administrative I/O, at most 64 admitted tasks, no pending queue.
 * Bootstrap owns initialization; shutdown is terminal until an explicit init.
 */
public final class VirtualExecutor {

    private static final Logger LOGGER = Logger.getLogger("SourbyCraft:VirtualExecutor");
    private static volatile ExecutorService EXECUTOR;
    private static boolean stopped;

    private VirtualExecutor() {}

    /** Initialize the virtual thread executor. Safe to call multiple times. */
    public static synchronized void init() {
        if (EXECUTOR != null && !EXECUTOR.isShutdown()) return;
        EXECUTOR = new BoundedIoExecutor(64);
        stopped = false;
        LOGGER.info("Virtual thread executor initialized");
    }

    /** Returns the shared virtual thread executor. */
    public static synchronized ExecutorService executor() {
        if (stopped) throw new RejectedExecutionException("SourbyCraft I/O is stopped");
        ExecutorService exec = EXECUTOR;
        if (exec == null || exec.isShutdown()) {
            init();
            exec = EXECUTOR;
        }
        return exec;
    }

    /** Run a task on a virtual thread. Fire-and-forget. */
    public static void run(Runnable task) {
        executor().execute(() -> {
            try { task.run(); }
            catch (Throwable failure) { LOGGER.log(Level.WARNING, "I/O task failed", failure); }
        });
    }

    /** Submit a task and return a CompletableFuture. */
    public static <T> CompletableFuture<T> supply(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        final FutureTask<Void> submitted = new FutureTask<>(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, null);
        future.whenComplete((result, failure) -> {
            if (future.isCancelled()) submitted.cancel(true);
        });
        try {
            executor().execute(submitted);
        } catch (RejectedExecutionException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    /** Shutdown, draining queued tasks. Blocks up to 30s. */
    public static void shutdown() {
        final ExecutorService exec;
        synchronized (VirtualExecutor.class) {
            stopped = true;
            exec = EXECUTOR;
        }
        if (exec == null) return;
        exec.shutdown();
        try {
            if (!exec.awaitTermination(30, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Virtual thread executor shut down");
    }
}
