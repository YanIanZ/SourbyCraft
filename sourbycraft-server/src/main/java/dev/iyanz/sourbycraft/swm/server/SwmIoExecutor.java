package dev.iyanz.sourbycraft.swm.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded thread pool for SWM serialization, compression, and loader I/O. */
public final class SwmIoExecutor {

    private static final int THREADS = Math.max(2, Math.min(4,
            Runtime.getRuntime().availableProcessors() / 2));

    private final ExecutorService pool;

    public SwmIoExecutor() {
        AtomicInteger idx = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "SWM-IO-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = Executors.newFixedThreadPool(THREADS, factory);
    }

    public ExecutorService pool() {
        return this.pool;
    }

    /** Shut down, draining queued tasks. Blocks up to 30s. */
    public void shutdown() {
        this.pool.shutdown();
        try {
            if (!this.pool.awaitTermination(30, TimeUnit.SECONDS)) {
                this.pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
