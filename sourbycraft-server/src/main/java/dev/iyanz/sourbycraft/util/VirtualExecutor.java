package dev.iyanz.sourbycraft.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Virtual-thread-based executor replacing AsyncExecutor and SwmIoExecutor.
 * Uses Java 25 virtual threads — unbounded, lightweight (~1KB stack each).
 */
public final class VirtualExecutor {

    private static final Logger LOGGER = Logger.getLogger("SourbyCraft:VirtualExecutor");
    private static volatile ExecutorService EXECUTOR;

    private VirtualExecutor() {}

    /** Initialize the virtual thread executor. Safe to call multiple times. */
    public static void init() {
        if (EXECUTOR != null && !EXECUTOR.isShutdown()) return;
        EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
        LOGGER.info("Virtual thread executor initialized");
    }

    /** Returns the shared virtual thread executor. */
    public static ExecutorService executor() {
        if (EXECUTOR == null || EXECUTOR.isShutdown()) {
            init();
        }
        return EXECUTOR;
    }

    /** Run a task on a virtual thread. Fire-and-forget. */
    public static void run(Runnable task) {
        executor().submit(task);
    }

    /** Submit a task and return a CompletableFuture. */
    public static <T> CompletableFuture<T> supply(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor().submit(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Shutdown, draining queued tasks. Blocks up to 30s. */
    public static void shutdown() {
        ExecutorService exec = EXECUTOR;
        if (exec == null) return;
        EXECUTOR = null;
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
