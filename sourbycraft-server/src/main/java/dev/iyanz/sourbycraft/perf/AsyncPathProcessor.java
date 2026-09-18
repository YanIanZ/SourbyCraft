package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
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
 * <p>Degrades safely while running: if the pool is not yet started or saturated (the bounded queue
 * rejects), the work runs inline on the calling thread — a slow path, never a dropped path.
 *
 * <p>Shutdown is the one case that does not degrade to inline. Once stopped, admission is refused
 * outright: the region threads are trying to stop, and a CPU-bound solve on one of them delays
 * exactly that. Callers still receive a completed future, so nothing is left believing a solve is
 * in flight.
 */
public final class AsyncPathProcessor {

    private static final Logger LOGGER = Logger.getLogger("SourbyCraft:AsyncPath");

    private AsyncPathProcessor() {}

    private static volatile boolean enabled = false;
    private static volatile ThreadPoolExecutor pool;
    /** Terminal until the pool is started again: admission is refused, not degraded to inline. */
    private static volatile boolean stopped = false;
    /** Solves admitted to the pool whose futures have not completed yet. */
    private static final AtomicInteger OUTSTANDING = new AtomicInteger();

    /**
     * How many admitted solves have not completed.
     *
     * <p>The pool's own queue is bounded, but each completed solve then enqueues a delivery on
     * an entity's scheduler, and that queue is not ours to bound. This is the count that makes
     * the difference observable.</p>
     */
    public static int outstanding() {
        return OUTSTANDING.get();
    }

    /** Live toggle (reloadable). Starts the pool the first time it is turned on. */
    public static void setEnabled(final boolean on) {
        if (on) {
            ensureStarted();
        }
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Idempotent. Sizes the pool to a quarter of the cores (min 1) — pathfinding is a background cost. */
    public static synchronized void ensureStarted() {
        stopped = false;                 // Starting again lifts the refusal a shutdown installed.
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
            (task, executor) -> {
                if (executor.isShutdown()) throw new RejectedExecutionException("Path executor stopped");
                task.run();
            });
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
        // The pool's own flag covers its lifecycle; the runtime's covers the server's. A solve
        // arriving while the server is going down must not start, even if this pool has not been
        // told to stop yet -- shutdown runs in an order, and this is not first.
        if (stopped || dev.iyanz.sourbycraft.core.AuroraRuntime.stopping()) {
            // Refuse, rather than degrading to an inline solve as a not-yet-started pool does.
            // The two look alike but are not: after shutdown the region threads are trying to
            // stop, and running a CPU-bound A* on one of them delays exactly that. The caller
            // still receives a completed future, so its pending bookkeeping is released and no
            // request is left outstanding.
            return CompletableFuture.completedFuture(null);
        }
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
        final FutureTask<T> task = new FutureTask<>(solve::get) {
            @Override protected void done() {
                if (isCancelled()) { future.complete(null); return; }
                try { future.complete(get()); }
                catch (Exception failure) {
                    LOGGER.log(Level.WARNING, "AsyncPath solve failed", failure);
                    future.complete(null);
                }
            }
        };
        OUTSTANDING.incrementAndGet();
        future.whenComplete((result, failure) -> {
            OUTSTANDING.decrementAndGet();
            if (future.isCancelled()) task.cancel(true);
        });
        try { p.execute(task); }
        catch (RejectedExecutionException rejected) { task.cancel(false); }
        return future;
    }

    /**
     * Stops admission and disposes of everything outstanding.
     *
     * <p>Distinct from {@link #setEnabled}, which only stops callers offering new work: this is
     * terminal, refusing submissions until the pool is started again. Every admitted solve is
     * cancelled, which completes its future with {@code null}, which runs the caller's release —
     * so no caller is left believing a solve is still in flight.</p>
     */
    public static synchronized void shutdown() {
        enabled = false;
        stopped = true;
        final ThreadPoolExecutor p = pool;
        pool = null;
        if (p != null) {
            final int admitted = OUTSTANDING.get();
            for (final Runnable pending : p.shutdownNow()) {
                if (pending instanceof FutureTask<?> task) task.cancel(false);
            }
            if (admitted > 0) {
                LOGGER.info("AsyncPath: disposing " + admitted + " outstanding solve(s)");
            }
        }
    }
}
