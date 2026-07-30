package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SourbyCraft — the worker pool behind async pathfinding (Phase 1a of the async-offload MT uplift).
 *
 * <p>Pathfinding A* is CPU-bound, so this is a small <b>bounded platform-thread</b> pool (never virtual
 * threads — those are for I/O and would just oversubscribe the cores). The caller builds the immutable
 * {@link net.minecraft.world.level.SnapshotPathRegion} and its per-solve {@code PathFinder}/{@code
 * NodeEvaluator} on the region thread, then hands a closure here that runs the solve against that
 * snapshot off-thread. Nothing in the submitted work may touch the live world — see {@code
 * SnapshotPathRegion} for why.
 *
 * <p>Degrades safely: if the pool is disabled, not yet started, or saturated (the bounded queue rejects),
 * the work runs inline on the calling thread — a slow path, never a dropped path.
 */
public final class AsyncPathProcessor {

    private static final Logger LOGGER = Logger.getLogger("SourbyCraft:AsyncPath");

    private AsyncPathProcessor() {}

    private static volatile boolean enabled = false;
    private static volatile ThreadPoolExecutor pool;

    /** Live toggle (reloadable). Starts the pool the first time it is turned on. */
    public static void setEnabled(final boolean on) {
        enabled = on;
        if (on) {
            ensureStarted();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Idempotent. Sizes the pool to a quarter of the cores (min 1) — pathfinding is a background cost. */
    public static synchronized void ensureStarted() {
        if (pool != null && !pool.isShutdown()) {
            return;
        }
        final int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        final AtomicInteger idx = new AtomicInteger();
        // Bounded queue: if solves back up faster than the pool drains, the rejection handler runs the
        // solve inline on the caller rather than letting the queue grow without bound.
        final ThreadPoolExecutor p = new ThreadPoolExecutor(
            threads, threads, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            r -> {
                final Thread t = new Thread(r, "SourbyCraft-AsyncPath-" + idx.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1); // yield to region tick threads under contention
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());
        p.allowCoreThreadTimeOut(true);
        pool = p;
        LOGGER.info("AsyncPath: worker pool started (" + threads + " thread(s))");
    }

    /**
     * Run {@code solve} off the region thread and complete the returned future with its result. The
     * {@code solve} supplier MUST already be closed over an immutable snapshot (no live-world reads).
     * On any failure the future completes with {@code null} (the caller falls back to keeping/omitting a
     * path — never a crash). If the pool is unavailable the solve runs inline and the future is already
     * completed on return.
     */
    public static <T> CompletableFuture<T> submit(final Supplier<T> solve) {
        final ThreadPoolExecutor p = pool;
        if (p == null || p.isShutdown()) {
            // Not started — run inline so callers still get a result.
            try {
                return CompletableFuture.completedFuture(solve.get());
            } catch (final Throwable t) {
                LOGGER.log(Level.WARNING, "AsyncPath inline solve failed", t);
                return CompletableFuture.completedFuture(null);
            }
        }
        final CompletableFuture<T> future = new CompletableFuture<>();
        try {
            p.execute(() -> {
                try {
                    future.complete(solve.get());
                } catch (final Throwable t) {
                    LOGGER.log(Level.WARNING, "AsyncPath solve failed", t);
                    future.complete(null);
                }
            });
        } catch (final Throwable rejected) {
            // CallerRunsPolicy already handles saturation; this covers shutdown races.
            try {
                future.complete(solve.get());
            } catch (final Throwable t) {
                future.complete(null);
            }
        }
        return future;
    }

    public static void shutdown() {
        final ThreadPoolExecutor p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
        }
    }
}
