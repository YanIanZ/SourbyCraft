package gg.pufferfish.pufferfish.util;

import gg.pufferfish.pufferfish.PufferfishLogger;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class AsyncExecutor {

    private static volatile ForkJoinPool POOL;
    private static final int DEFAULT_THREADS = 2;

    @Deprecated
    public AsyncExecutor(String threadName) {}

    @Deprecated public void start() {}
    @Deprecated public void kill() {}
    @Deprecated public void submit(Runnable r) { submitToPool(r); }

    public static void initPool(int threadCount) {
        shutdownPool();
        if (threadCount <= 0) threadCount = DEFAULT_THREADS;
        POOL = new ForkJoinPool(threadCount);
        PufferfishLogger.LOGGER.log(Level.INFO, "Started async pool with " + threadCount + " threads (ForkJoinPool)");
    }

    public static void shutdownPool() {
        if (POOL != null) {
            POOL.shutdown();
            try {
                if (!POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                    POOL.shutdownNow();
                }
            } catch (InterruptedException e) {
                POOL.shutdownNow();
                Thread.currentThread().interrupt();
            }
            POOL = null;
        }
    }

    public static boolean isActive() {
        return POOL != null && !POOL.isShutdown();
    }

    public static void submitToPool(Runnable task) {
        ForkJoinPool pool = POOL;
        if (pool == null || pool.isShutdown()) {
            task.run();
            return;
        }
        pool.execute(task);
    }
}
