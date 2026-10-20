package dev.iyanz.sourbycraft.execution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

/**
 * A budgeted lane whose queue is split by owner (for the bridge, the plugin): a fixed number of
 * threads, a fixed total queue, and per owner a sub-queue with its own limit. Queued work is
 * started in weighted round-robin over the owners that have work: each owner's turn starts up to
 * {@code weight(owner)} of its tasks in submission order, then the next owner's turn begins. So
 * one owner with a deep backlog delays another owner's next task by at most one turn of each
 * other owner, instead of by its whole backlog as in a single FIFO queue ({@link GovernedLane}).
 *
 * <p>Admission: a task is rejected with {@link RejectedExecutionException} when the lane's queue
 * is full or its owner already has {@code perOwnerQueue} tasks queued; never run on the caller.
 * With {@code perOwnerQueue} equal to the queue capacity no owner is limited on its own, which
 * is {@link GovernedLane}'s admission. Running tasks do not count against either limit.</p>
 *
 * <p><b>Status:</b> not wired. {@code BRIDGE_IO} still runs on a {@link GovernedLane}; using this
 * lane there, its setting names and its defaults are a later change. Nothing here is measured.</p>
 */
public final class FairLane {

    private final String name;
    private final int threads;
    private final int queueCapacity;
    private final int perOwnerQueue;
    private final ToIntFunction<Object> weights;
    private final Executor workers;

    // Guarded by this.
    private final Map<Object, ArrayDeque<Runnable>> queues = new HashMap<>();
    /** Owners with queued work, in turn order, except {@link #current}. */
    private final ArrayDeque<Object> turns = new ArrayDeque<>();
    private Object current;
    private int picksLeft;
    private int queued;
    private int running;
    private boolean shutdown;
    private long completed;
    private long failed;
    private final Map<Object, Long> rejected = new HashMap<>();

    /**
     * A lane on its own daemon threads, named {@code threadPrefix + n} for {@link ExecutionLane}
     * attribution; idle threads time out.
     *
     * @param weights tasks an owner may start per turn; values below 1 count as 1
     */
    public FairLane(final String name, final String threadPrefix, final int threads, final int queueCapacity,
                    final int perOwnerQueue, final ToIntFunction<Object> weights) {
        this(name, threads, queueCapacity, perOwnerQueue, weights, pool(threadPrefix, threads));
    }

    /** As above, starting tasks on {@code workers}; for tests. At most {@code threads} are handed to it at once. */
    FairLane(final String name, final int threads, final int queueCapacity, final int perOwnerQueue,
             final ToIntFunction<Object> weights, final Executor workers) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("a lane needs at least one thread and one queue slot");
        }
        if (perOwnerQueue < 1 || perOwnerQueue > queueCapacity) {
            throw new IllegalArgumentException("the per-owner queue must be between 1 and the queue capacity");
        }
        this.name = name;
        this.threads = threads;
        this.queueCapacity = queueCapacity;
        this.perOwnerQueue = perOwnerQueue;
        this.weights = weights;
        this.workers = workers;
    }

    private static Executor pool(final String threadPrefix, final int threads) {
        final AtomicInteger next = new AtomicInteger(1);
        // The lane hands over a task only while fewer than `threads` of its tasks are in the pool
        // (a finishing task can still hold its thread for a moment), so `threads` queue slots suffice.
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(threads), task -> {
                final Thread thread = new Thread(task, threadPrefix + next.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Queues {@code task} for {@code owner}, or rejects it; see the class comment. */
    public void execute(final Object owner, final Runnable task) {
        synchronized (this) {
            if (this.shutdown) throw reject(owner, "is shut down");
            final ArrayDeque<Runnable> queue = this.queues.get(owner);
            if (this.running < this.threads && this.queued == 0) {
                // Idle capacity and nobody waiting: start it now.
                this.running++;
            } else {
                if (this.queued >= this.queueCapacity) throw reject(owner, "is full (" + this.queueCapacity + " queued)");
                if (queue != null && queue.size() >= this.perOwnerQueue) {
                    throw reject(owner, "holds " + this.perOwnerQueue + " queued tasks of " + owner + ", its per-owner limit");
                }
                if (queue == null) {
                    final ArrayDeque<Runnable> created = new ArrayDeque<>();
                    created.add(task);
                    this.queues.put(owner, created);
                    if (!owner.equals(this.current)) this.turns.addLast(owner);
                } else {
                    queue.add(task);
                }
                this.queued++;
                pump();
                return;
            }
        }
        start(task);
    }

    private RejectedExecutionException reject(final Object owner, final String why) {
        this.rejected.merge(owner, 1L, Long::sum);
        return new RejectedExecutionException(this.name + " lane " + why + "; rejected a task of " + owner);
    }

    /**
     * Starts queued tasks while threads are free. Called with the monitor held; handing a task to
     * the pool only enqueues it there, so no task ever runs on the submitting thread.
     */
    private void pump() {
        while (!this.shutdown && this.running < this.threads && this.queued > 0) {
            if (this.current == null || this.picksLeft <= 0) {
                if (this.current != null && this.queues.containsKey(this.current)) this.turns.addLast(this.current);
                this.current = this.turns.pollFirst();
                this.picksLeft = Math.max(1, this.weights.applyAsInt(this.current));
            }
            final ArrayDeque<Runnable> queue = this.queues.get(this.current);
            final Runnable next = queue.pollFirst();
            this.picksLeft--;
            this.queued--;
            this.running++;
            if (queue.isEmpty()) {
                this.queues.remove(this.current);
                this.current = null;
            }
            start(next);
        }
    }

    private void start(final Runnable task) {
        try {
            this.workers.execute(() -> run(task));
        } catch (final RuntimeException refused) {
            synchronized (this) {
                this.running--;
                this.failed++;
            }
            throw refused;
        }
    }

    private void run(final Runnable task) {
        boolean ok = false;
        try {
            task.run();
            ok = true;
        } finally {
            synchronized (this) {
                this.running--;
                if (ok) this.completed++;
                else this.failed++;
                pump();
            }
        }
    }

    /**
     * Stops accepting work and returns the queued tasks without running them (the objects passed
     * to {@link #execute}). Running tasks finish; none start after them.
     */
    public synchronized List<Runnable> shutdownNow() {
        this.shutdown = true;
        final List<Runnable> pending = new ArrayList<>();
        this.queues.values().forEach(pending::addAll);
        this.queues.clear();
        this.turns.clear();
        this.current = null;
        this.queued = 0;
        return pending;
    }

    public synchronized int queued() {
        return this.queued;
    }

    public synchronized int running() {
        return this.running;
    }

    public synchronized long completed() {
        return this.completed;
    }

    public synchronized long failed() {
        return this.failed;
    }

    /** Rejections per owner since the lane was made. */
    public synchronized Map<Object, Long> rejectedByOwner() {
        return Map.copyOf(this.rejected);
    }
}
