package dev.iyanz.sourbycraft.io;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Sized worker pool dedicated to region NIO. Platform threads (predictable
 * NIO backpressure beats virtual-thread pinning on FileChannel).
 *
 * Backpressure is the queue cap on the underlying {@link ThreadPoolExecutor}.
 */
public final class RegionIoPool {

    private final ThreadPoolExecutor pool;

    public RegionIoPool(int size, int queueCap) {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "SourbyCraft-RegionIo");
            t.setDaemon(true);
            return t;
        };
        this.pool = new ThreadPoolExecutor(
            size, size, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCap),
            tf,
            new ThreadPoolExecutor.AbortPolicy());
    }

    /** Submit an op operating on the given buffer; returns the operation's result buffer. */
    public CompletableFuture<ByteBuffer> submit(Function<ByteBuffer, ByteBuffer> op, ByteBuffer input) {
        CompletableFuture<ByteBuffer> f = new CompletableFuture<>();
        pool.submit(() -> {
            try { f.complete(op.apply(input)); }
            catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f;
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
