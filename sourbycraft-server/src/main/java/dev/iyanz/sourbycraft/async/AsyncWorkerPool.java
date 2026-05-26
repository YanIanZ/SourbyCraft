package dev.iyanz.sourbycraft.async;

import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Generic snapshot → diff worker pool.
 * <ul>
 *   <li>Backpressure: bounded queue, {@link #submit(Object)} returns false when full or breaker tripped.</li>
 *   <li>Watchdog: rolling-avg deadline, cancels overdue tasks.</li>
 *   <li>Circuit breaker: trips after N consecutive timeouts.</li>
 * </ul>
 *
 * Workers run on the supplied {@link ExecutorService} (typically virtual threads).
 * Diffs are drained on the main thread via {@link #drainDiffs(Consumer)}.
 */
public final class AsyncWorkerPool<S, D> {

    private final String name;
    private final ExecutorService executor;
    private final long circuitCooldownMs;
    private final Function<S, D> compute;

    private final BlockingQueue<S> queue;
    private final BlockingQueue<D> diffs;
    private final PoolMetrics metrics = new PoolMetrics();
    private final Watchdog watchdog;
    private final CircuitBreaker breaker;

    private volatile boolean running = true;

    public AsyncWorkerPool(
        String name,
        ExecutorService executor,
        int queueCap,
        int circuitFailureThreshold,
        long circuitCooldownMs,
        double watchdogMultiplier,
        Function<S, D> compute
    ) {
        this.name = name;
        this.executor = executor;
        this.circuitCooldownMs = circuitCooldownMs;
        this.compute = compute;
        this.queue = new ArrayBlockingQueue<>(queueCap);
        this.diffs = new LinkedBlockingQueue<>();
        this.watchdog = new Watchdog(watchdogMultiplier);
        this.breaker = new CircuitBreaker(circuitFailureThreshold, circuitCooldownMs);
        startConsumerLoop();
    }

    private void startConsumerLoop() {
        executor.submit(() -> {
            while (running) {
                S snap;
                try { snap = queue.poll(50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                if (snap == null) {
                    continue;
                }
                runOne(snap);
            }
        });
    }

    private void runOne(S snap) {
        long start = System.currentTimeMillis();
        long deadline = watchdog.deadlineMs();
        Future<D> future = CompletableFuture.supplyAsync(() -> compute.apply(snap), executor);
        try {
            D diff = future.get(deadline, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            metrics.recordCompletion(elapsed);
            watchdog.recordCompletion(elapsed);
            breaker.recordSuccess();
            if (diff != null) diffs.offer(diff);
        } catch (TimeoutException te) {
            future.cancel(true);
            metrics.recordTimeout();
            breaker.recordFailure();
        } catch (Exception e) {
            metrics.recordTimeout();
            breaker.recordFailure();
        }
    }

    /** Submit a snapshot. Returns false if queue full or breaker tripped. */
    public boolean submit(S snap) {
        if (breaker.tripped()) return false;
        boolean accepted = queue.offer(snap);
        if (accepted) {
            metrics.recordSubmit();
            metrics.observeQueueDepth(queue.size());
        }
        return accepted;
    }

    /** Drain available diffs onto the consumer. Call from main thread per tick. */
    public void drainDiffs(Consumer<D> consumer) {
        D d;
        while ((d = diffs.poll()) != null) consumer.accept(d);
    }

    public PoolMetrics metrics() { return metrics; }
    public boolean breakerTripped() { return breaker.tripped(); }
    public String name() { return name; }

    public void shutdown() {
        running = false;
    }
}
