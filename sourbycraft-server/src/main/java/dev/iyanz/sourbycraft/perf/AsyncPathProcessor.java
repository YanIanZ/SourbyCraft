package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.execution.GovernedLane;
import dev.iyanz.sourbycraft.execution.ResourceGovernor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
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
 * <p>Overload is handled with backpressure, not CallerRuns: the pool never runs a refused solve on
 * the submitting thread. The navigation asks {@link #hasCapacity()} before it pays for a snapshot
 * and offers the solve with {@link #trySubmit}; when either says no, the offload is declined and
 * the navigation runs its ordinary synchronous recompute for that slot, exactly as with the
 * feature off. A refusal therefore never consumes the recompute slot without a path being
 * computed (the old behaviour kept the stale path and skipped the recompute). A no from
 * {@link #hasCapacity()} is counted as {@code declined} ({@link #noteDeclined}), a no from
 * {@link #trySubmit} as {@code refused}. {@link #submit} keeps the older contract of a completed
 * {@code null} future for callers that want it.</p>
 *
 * <p>A stopped pool or failed/stopping runtime uses the same refusal contract. Work admitted
 * before shutdown follows {@link #shutdown()}'s disposal rules.</p>
 *
 * <p>The threads and queue are the Resource Governor's {@link ResourceGovernor.Lane#PATHFINDING}
 * lane, so the budget is owned and reported ({@code /perf governor}) with the other SourbyCraft
 * lanes. This class keeps the admission policy, the solve and delivery telemetry and the
 * stop/start lifecycle; the result handoff to the entity owner is {@code AsyncPathCompletion}.</p>
 */
public final class AsyncPathProcessor {

    private static final Logger LOGGER = Logger.getLogger("SourbyCraft/AsyncPath");

    private AsyncPathProcessor() {}

    private static volatile boolean enabled = false;
    private static volatile GovernedLane pool;
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
    /** Offloads declined on {@link #hasCapacity()} before anything was submitted. */
    private static final java.util.concurrent.atomic.AtomicLong DECLINED =
        new java.util.concurrent.atomic.AtomicLong();
    /** Finished solves by how they ended (on the owner, or retired before it), indexed by {@link Delivery#ordinal()}. */
    private static final java.util.concurrent.atomic.AtomicLongArray DELIVERIES =
        new java.util.concurrent.atomic.AtomicLongArray(Delivery.values().length);
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
    /** Test seam: the clock for queue-wait and solve-duration accounting. */
    static volatile java.util.function.LongSupplier nanoClock = System::nanoTime;
    /** Solver failures are per mob and can repeat every recompute: log one per window. */
    static final long FAILURE_LOG_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final Object FAILURE_LOG_LOCK = new Object();
    private static long lastFailureLogNanos;
    private static boolean failureLogged;
    private static long suppressedFailures;

    /**
     * What the pool has actually been doing.
     *
     * <p>{@code inline} is retained for telemetry compatibility with older builds. Current
     * overload policy never executes a rejected solve on the submitting thread, so this counter
     * should remain zero. Saturation shows as {@code declined}, and as {@code refused} when the
     * queue fills between the capacity check and the submission.</p>
     *
     * <p>The last six count finished solves by how they ended: five by what the owner found, and
     * {@code retired} for a result that never reached the owner.</p>
     *
     * @param admitted      solves the executor accepted; a submission it rejected (saturation or a
     *                      concurrent shutdown) is counted in {@code refused} only; excludes
     *                      pre-start inline solves
     * @param inline        legacy caller-run count; should remain zero with current backpressure policy
     * @param refused       submissions {@link #trySubmit} turned away: the queue was saturated, the
     *                      pool stopped, the runtime failed/stopped, or executor admission raced
     *                      with shutdown; disjoint from admitted and declined
     * @param declined      offloads declined before any submission because {@link #hasCapacity()}
     *                      said no (full queue, stopped pool, failed/stopping runtime); the caller
     *                      ran its synchronous recompute instead; disjoint from admitted and refused
     * @param outstanding   admitted solves that have not completed
     * @param meanMillis    mean solve duration, or NaN before anything has solved
     * @param slowestMillis the slowest single solve seen
     * @param queueDepth    solves accepted but not yet picked up by a worker, or -1 when the
     *                      pool is not running
     * @param activeWorkers workers currently running a solve, or -1 when the pool is not running
     * @param poolSize      worker threads the pool currently holds, or -1 when it is not running
     * @param meanWaitMillis    mean time a solve waited in the queue, or NaN before any ran
     * @param slowestWaitMillis the longest a single solve waited before starting
     * @param applied         results that were still the requested route and were applied
     * @param staleSubject    results discarded because the owner handed back another entity object
     *                        (dimension transfer)
     * @param stalePath       results discarded because the navigation's path was replaced or
     *                        cleared while the solve ran
     * @param staleGeneration results discarded because the navigation's request generation moved
     *                        on (patch 0047), such as a {@code stop()} on an already-null path
     * @param noPath          results that were still valid but carried no path (none found, solver
     *                        failure, cancellation) or one without a target; nothing was applied
     * @param retired         results that never reached the owner: the entity was removed or unloaded
     *                        before its next owning tick (the handoff's retirement callback ran), or the
     *                        owner handoff refused the delivery; nothing was applied
     */
    public record PathStats(long admitted, long inline, long refused, long declined, int outstanding,
                            double meanMillis, double slowestMillis,
                            int queueDepth, int activeWorkers, int poolSize,
                            double meanWaitMillis, double slowestWaitMillis,
                            long applied, long staleSubject, long stalePath, long staleGeneration,
                            long noPath, long retired) {}

    /**
     * How a finished solve ended. The first five are counted by {@code AsyncPathRecompute.deliver} on the owner;
     * {@link #RETIRED} by the retired callback {@code AsyncPathRecompute} hands to {@code AsyncPathCompletion}.
     */
    enum Delivery {
        /** Still the requested route: applied to the navigation. */
        APPLIED,
        /** The owner handed back another entity object. */
        STALE_SUBJECT,
        /** The navigation's path was replaced or cleared meanwhile. */
        STALE_PATH,
        /** The navigation's request generation moved on meanwhile. */
        STALE_GENERATION,
        /** Still valid, but no path (or one without a target) to apply. */
        NO_PATH,
        /** Never reached the owner: it retired (entity removed or unloaded) or refused the handoff. */
        RETIRED
    }

    /**
     * A snapshot of the pool's counters. Never blocks and never throws.
     *
     * <p>Queue depth, active workers and pool size are read from the executor at call time
     * rather than tracked, so they cost nothing while the server is running and simply report
     * {@code -1} when there is no pool to ask.</p>
     */
    public static PathStats stats() {
        final long solved = SOLVED.get();
        final GovernedLane p = pool;
        final boolean running = p != null && !p.isShutdown();
        final GovernedLane.Stats lane = running ? p.stats() : null;
        return new PathStats(ADMITTED.get(), INLINE.get(), REFUSED.get(), DECLINED.get(), OUTSTANDING.get(),
            solved == 0L ? Double.NaN : SOLVE_NANOS.get() / (double) solved / 1.0E6,
            SLOWEST_NANOS.get() / 1.0E6,
            running ? lane.queued() : -1,
            running ? lane.active() : -1,
            running ? p.poolSize() : -1,
            solved == 0L ? Double.NaN : WAIT_NANOS.get() / (double) solved / 1.0E6,
            SLOWEST_WAIT_NANOS.get() / 1.0E6,
            DELIVERIES.get(Delivery.APPLIED.ordinal()),
            DELIVERIES.get(Delivery.STALE_SUBJECT.ordinal()),
            DELIVERIES.get(Delivery.STALE_PATH.ordinal()),
            DELIVERIES.get(Delivery.STALE_GENERATION.ordinal()),
            DELIVERIES.get(Delivery.NO_PATH.ordinal()),
            DELIVERIES.get(Delivery.RETIRED.ordinal()));
    }

    /**
     * Counts how one finished solve ended. Thread-safe: the owner outcomes are counted on the entity's owning
     * thread, {@link Delivery#RETIRED} in the handoff's retirement callback, or, when the handoff refused the
     * delivery, on the thread that ran the solve's completion callback.
     */
    static void noteDelivery(final Delivery outcome) {
        DELIVERIES.incrementAndGet(outcome.ordinal());
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

    /** Worker threads the pool is sized to when it starts: a quarter of the processors, at least one. */
    public static int poolThreads() {
        return ResourceGovernor.pathfindingThreads();
    }

    /** Idempotent. Sizes the pool to a quarter of the cores (min 1) — pathfinding is a background cost. */
    public static synchronized void ensureStarted() {
        stopped = false;                 // Starting again lifts the refusal a shutdown installed.
        if (pool != null && !pool.isShutdown()) {
            return;
        }
        // The governed lane: bounded queue, rejection instead of CallerRuns. A refused solve goes
        // back to the navigation, which runs the vanilla synchronous recompute it runs with the
        // feature off, and the capacity check comes before the snapshot so a full pool costs no
        // more than that. CallerRuns created a positive feedback loop under load: snapshot cost +
        // rejected submission + full synchronous solve on the latency-critical owner thread.
        // Threads run below normal priority so they yield to region tick threads under contention.
        final GovernedLane p = ResourceGovernor.GLOBAL.restart(ResourceGovernor.Lane.PATHFINDING);
        pool = p;
        final int threads = p.stats().threads();
        LOGGER.info("AsyncPath: worker pool started (" + threads + " thread(s))");
    }

    /**
     * Computes a path result using the bounded worker pool. Before pool startup the historical
     * inline fallback is retained for lifecycle compatibility; once the pool is running, a full
     * queue is refused rather than executed on the caller. The supplier must capture an immutable
     * snapshot and must not read or mutate live world state.
     *
     * <p>A stopped pool or failed/stopping runtime refuses new work with a completed null result.
     * Solver failures also produce null. Explicit cancellation of the returned future remains
     * cancellation, and propagates to the submitted task. Result application must use an owner
     * handoff and validate freshness; future completion alone conveys no region ownership.</p>
     *
     * @param solve computation over an already captured snapshot
     * @param <T> result type
     * @return result future, possibly already completed by refusal or inline execution
     */
    public static <T> CompletableFuture<T> submit(final Supplier<T> solve) {
        final CompletableFuture<T> offered = trySubmit(solve);
        return offered != null ? offered : CompletableFuture.completedFuture(null);
    }

    /**
     * Whether a solve offered now would be admitted, checked before the caller pays for a
     * snapshot. Best effort: {@link #trySubmit} can still refuse when another region filled the
     * queue in between. A query only; a caller that declines on {@code false} reports it with
     * {@link #noteDeclined}.
     */
    public static boolean hasCapacity() {
        if (stopped || dev.iyanz.sourbycraft.core.AuroraRuntime.stopping()) return false;
        final GovernedLane p = pool;
        // Not started: trySubmit solves inline, as it always has.
        return p == null || p.isShutdown() || p.hasCapacity();
    }

    /**
     * Counts an offload declined because {@link #hasCapacity()} said no, under the mob's entity
     * type key. The request never reached {@link #trySubmit}, so it is neither admitted nor
     * refused; the caller runs its synchronous recompute instead.
     */
    static void noteDeclined(final String type) {
        DECLINED.incrementAndGet();
        AsyncPathTypeStats.slotFor(type).declined();
    }

    /**
     * As {@link #submit}, except that a refused solve returns {@code null} instead of a completed
     * future, so the caller can run its synchronous path instead. Refusals are counted.
     *
     * <p>The entity type comes from the per-thread hand-off ({@link AsyncPathTypeStats}); the
     * navigation's offload uses {@link #trySubmit(MobPathSnapshot, Supplier)} instead.</p>
     *
     * @return the result future, or {@code null} when the solve was refused and never ran
     */
    public static <T> CompletableFuture<T> trySubmit(final Supplier<T> solve) {
        // Type of the mob captured just before this call on this thread (AsyncPathTypeStats fallback).
        return trySubmit(AsyncPathTypeStats.takeSubject(), solve);
    }

    /**
     * As {@link #trySubmit(Supplier)}, attributing the solve to the entity type the snapshot
     * carries instead of relying on the per-thread hand-off (which this clears).
     */
    public static <T> CompletableFuture<T> trySubmit(final MobPathSnapshot subject, final Supplier<T> solve) {
        AsyncPathTypeStats.clearSubject();
        return trySubmit(subject.entityTypeKey(), solve);
    }

    private static <T> CompletableFuture<T> trySubmit(final String type, final Supplier<T> solve) {
        // One bucket per request: admission, completion and refusal all charge the same counters.
        final AsyncPathTypeStats.Slot slot = AsyncPathTypeStats.slotFor(type);
        // The pool's own flag covers its lifecycle; the runtime's covers the server's. A solve
        // arriving while the server is going down must not start, even if this pool has not been
        // told to stop yet -- shutdown runs in an order, and this is not first.
        if (stopped || dev.iyanz.sourbycraft.core.AuroraRuntime.stopping()) {
            // Refuse, rather than degrading to an inline solve as a not-yet-started pool does.
            // The two look alike but are not: after shutdown the region threads are trying to
            // stop, and running a CPU-bound A* on one of them delays exactly that. The caller
            // receives null (submit() turns it into a completed null future), releases its
            // pending bookkeeping itself, and no request is left outstanding.
            REFUSED.incrementAndGet();
            slot.refused();
            return null;
        }
        final GovernedLane p = pool;
        if (p == null || p.isShutdown()) {
            // Not started — run inline so callers still get a result.
            try {
                return CompletableFuture.completedFuture(solve.get());
            } catch (final Throwable t) {
                logSolveFailure("AsyncPath inline solve failed", t);
                return CompletableFuture.completedFuture(null);
            }
        }
        final CompletableFuture<T> future = new CompletableFuture<>();
        // One clock per submission, so swapping the seam never mixes two clocks in one measurement.
        final java.util.function.LongSupplier clock = nanoClock;
        final long submittedAt = clock.getAsLong();
        final FutureTask<T> task = new FutureTask<>(() -> {
            final long began = clock.getAsLong();
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
                final long took = clock.getAsLong() - began;
                SOLVE_NANOS.addAndGet(took);
                SOLVED.incrementAndGet();
                slot.solved();
                SLOWEST_NANOS.accumulateAndGet(took, Math::max);
            }
        }) {
            @Override protected void done() {
                if (isCancelled()) { future.complete(null); return; }
                try { future.complete(get()); }
                catch (Exception failure) {
                    logSolveFailure("AsyncPath solve failed", failure);
                    future.complete(null);
                }
            }
        };
        OUTSTANDING.incrementAndGet();
        ADMITTED.incrementAndGet();
        slot.admitted();
        future.whenComplete((result, failure) -> {
            OUTSTANDING.decrementAndGet();
            if (future.isCancelled()) task.cancel(true);
        });
        try {
            p.execute(task);
        } catch (RejectedExecutionException rejected) {
            // Saturation and a concurrent shutdown have the same result: the caller gets null; the
            // navigation's offload (AsyncPathRecompute) then releases its pending flag and runs its
            // synchronous recompute for this slot. Never run the A* on the rejecting thread here.
            // The task never ran and its future is dropped; release the outstanding count here.
            // A rejected execution is a refusal and not also an admission: roll the admission back
            // so admitted + refused counts each submission once.
            ADMITTED.decrementAndGet();
            slot.admissionRolledBack();
            REFUSED.incrementAndGet();
            slot.refused();
            OUTSTANDING.decrementAndGet();
            return null;
        }
        return future;
    }

    private static void logSolveFailure(final String message, final Throwable failure) {
        final long suppressed = admitFailureLog(System.nanoTime());
        if (suppressed < 0) return;
        LOGGER.log(Level.WARNING, message + (suppressed > 0 ? " (" + suppressed
            + " more solve failure(s) suppressed in the last 60 s)" : ""), failure);
    }

    /**
     * Rate limit for solver failure logs: one line per {@link #FAILURE_LOG_WINDOW_NANOS}.
     *
     * @return {@code -1} to suppress, otherwise how many failures were suppressed before this one
     */
    static long admitFailureLog(final long nowNanos) {
        synchronized (FAILURE_LOG_LOCK) {
            if (failureLogged && nowNanos - lastFailureLogNanos < FAILURE_LOG_WINDOW_NANOS) {
                suppressedFailures++;
                return -1L;
            }
            final long suppressed = suppressedFailures;
            failureLogged = true;
            lastFailureLogNanos = nowNanos;
            suppressedFailures = 0L;
            return suppressed;
        }
    }

    /** Test seam: forgets the failure log window. */
    static void resetFailureLogForTest() {
        synchronized (FAILURE_LOG_LOCK) {
            failureLogged = false;
            suppressedFailures = 0L;
        }
    }

    /**
     * Stops admission and disposes of everything outstanding.
     *
     * <p>Distinct from {@link #setEnabled}, which only stops callers offering new work: this is
     * terminal for this pool, refusing submissions until it is started again. Queued tasks are
     * cancelled and their result futures complete with {@code null} unless already cancelled by
     * the caller. Running tasks are interrupted, but this method does not await their termination:
     * a solver that ignores interruption may continue and complete its future later.</p>
     */
    public static synchronized void shutdown() {
        enabled = false;
        stopped = true;
        final GovernedLane p = pool;
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
