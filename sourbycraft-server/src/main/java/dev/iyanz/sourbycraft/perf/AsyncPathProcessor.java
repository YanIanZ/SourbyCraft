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
 * {@link dev.iyanz.aurora.level.SnapshotPathRegion} and its per-solve {@code PathFinder}/{@code
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
    private static final java.util.concurrent.atomic.AtomicLong ADMITTED =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong INLINE =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong REFUSED =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SOLVE_NANOS =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SOLVED =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SLOWEST_NANOS =
        new java.util.concurrent.atomic.AtomicLong();
    /** Time a solve spent queued before a worker picked it up. T7 task wait latency. */
    private static final java.util.concurrent.atomic.AtomicLong WAIT_NANOS =
        new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SLOWEST_WAIT_NANOS =
        new java.util.concurrent.atomic.AtomicLong();

    /**
     * What the pool has actually been doing.
     *
     * <p>{@code inline} is the number that decides whether this feature is helping. When the
     * bounded queue fills, the solve runs on the submitting thread — a region thread — so an
     * inline solve is the feature doing its work in the one place it exists to avoid, after
     * already paying to build the immutable snapshot. A rising inline count means the pool is
     * undersized for the workload and async pathfinding is costing more than it saves.</p>
     *
     * @param admitted      solves handed to the pool
     * @param inline        solves the pool refused and the caller ran itself
     * @param refused       submissions declined outright, which happens only after shutdown
     * @param outstanding   admitted solves that have not completed
     * @param meanMillis    mean solve duration, or NaN before anything has solved
     * @param slowestMillis the slowest single solve seen
     * @param queueDepth    solves accepted but not yet picked up by a worker, or -1 when the
     *                      pool is not running
     * @param activeWorkers workers currently running a solve, or -1 when the pool is not running
     * @param poolSize      worker threads the pool currently holds, or -1 when it is not running
     * @param meanWaitMillis    mean time a solve waited in the queue, or NaN before any ran
     * @param slowestWaitMillis the longest a single solve waited before starting
     */
    public record PathStats(long admitted, long inline, long refused, int outstanding,
                            double meanMillis, double slowestMillis,
                            int queueDepth, int activeWorkers, int poolSize,
                            double meanWaitMillis, double slowestWaitMillis) {}

    /**
     * A snapshot of the pool's counters. Never blocks and never throws.
     *
     * <p>Queue depth, active workers and pool size are read from the executor at call time
     * rather than tracked, so they cost nothing while the server is running and simply report
     * {@code -1} when there is no pool to ask.</p>
     */
    public static PathStats stats() {
        final long solved = SOLVED.get();
        final ThreadPoolExecutor p = pool;
        final boolean running = p != null && !p.isShutdown();
        return new PathStats(ADMITTED.get(), INLINE.get(), REFUSED.get(), OUTSTANDING.get(),
            solved == 0L ? Double.NaN : SOLVE_NANOS.get() / (double) solved / 1.0E6,
            SLOWEST_NANOS.get() / 1.0E6,
            running ? p.getQueue().size() : -1,
            running ? p.getActiveCount() : -1,
            running ? p.getPoolSize() : -1,
            solved == 0L ? Double.NaN : WAIT_NANOS.get() / (double) solved / 1.0E6,
            SLOWEST_WAIT_NANOS.get() / 1.0E6);
    }

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
                // Counted, because this is the pool doing its work on a region thread: the one
                // place the feature exists to keep free. See PathStats#inline.
                INLINE.incrementAndGet();
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
            REFUSED.incrementAndGet();
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
        final long submittedAt = System.nanoTime();
        final FutureTask<T> task = new FutureTask<>(() -> {
            final long began = System.nanoTime();
            // Queue wait, recorded separately from solve duration because they fail differently:
            // a long solve means the A* is expensive, a long wait means the pool is too small for
            // the arrival rate. Both can be inside budget while the pair is not.
            final long waited = began - submittedAt;
            WAIT_NANOS.addAndGet(waited);
            SLOWEST_WAIT_NANOS.accumulateAndGet(waited, Math::max);
            try {
                return solve.get();
            } finally {
                // Solve duration, not queue wait: how long the A* itself took is what says
                // whether the pool is sized for the work, and it is comparable to a tick budget.
                final long took = System.nanoTime() - began;
                SOLVE_NANOS.addAndGet(took);
                SOLVED.incrementAndGet();
                SLOWEST_NANOS.accumulateAndGet(took, Math::max);
            }
        }) {
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
        ADMITTED.incrementAndGet();
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
