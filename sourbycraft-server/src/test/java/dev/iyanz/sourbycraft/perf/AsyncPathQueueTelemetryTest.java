package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Queue depth, worker utilization and task wait latency (transition §13).
 *
 * <p>Solve duration alone cannot tell an oversubscribed pool from an expensive search: a short
 * A* that waited half a second behind other work still reaches the entity late. These assert
 * that the wait is measured separately and that the pool's own figures are readable.</p>
 */
class AsyncPathQueueTelemetryTest {

    @AfterEach
    void stopPool() {
        AsyncPathProcessor.shutdown();
    }

    @Test
    void anIdlePoolReportsNoQueueRatherThanZero() {
        // -1 and 0 mean different things to an operator: "there is no pool" is not "the pool is
        // keeping up". shutdown() has run, so there is nothing to ask.
        AsyncPathProcessor.shutdown();
        final var stats = AsyncPathProcessor.stats();
        assertEquals(-1, stats.queueDepth(), "no pool to report a queue for");
        assertEquals(-1, stats.activeWorkers());
        assertEquals(-1, stats.poolSize());
    }

    @Test
    void waitLatencyIsRecordedSeparatelyFromSolveDuration() throws Exception {
        AsyncPathProcessor.setEnabled(true);

        // A solve that blocks until released, so the next one provably waits behind it.
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CompletableFuture<String> blocking = AsyncPathProcessor.submit(() -> {
            firstStarted.countDown();
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "first";
        });
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "first solve should start");

        hold.countDown();
        assertEquals("first", blocking.get(5, TimeUnit.SECONDS));

        final var stats = AsyncPathProcessor.stats();
        assertFalse(Double.isNaN(stats.meanWaitMillis()), "a completed solve has a wait time");
        assertTrue(stats.meanWaitMillis() >= 0.0, "wait is a duration, not a negative");
        assertTrue(stats.slowestWaitMillis() >= stats.meanWaitMillis(),
            "the slowest wait cannot be under the mean");
        // Deliberately not asserted here: any relation between mean solve time and mean wait.
        // These counters are process-wide and accumulate across every test in the suite, so both
        // means carry other tests' solves and a comparison between them proves nothing about
        // this one. That the two are recorded from different clocks is what the production code
        // guarantees; queueingBehindABusyPoolIsChargedToWaitNotSolve covers the separation.
    }

    @Test
    void queueingBehindABusyPoolIsChargedToWaitNotSolve() throws Exception {
        AsyncPathProcessor.setEnabled(true);
        final double waitBefore = AsyncPathProcessor.stats().slowestWaitMillis();

        // Occupy the pool with short solves. Saturation now rejects instead of running work on
        // the submitting thread, so this test never depends on CallerRuns behavior.
        for (int i = 0; i < 12; i++) {
            AsyncPathProcessor.submit(() -> {
                try {
                    Thread.sleep(60);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "busy";
            });
        }

        final long admittedBefore = AsyncPathProcessor.stats().admitted();
        final CompletableFuture<String> queued = AsyncPathProcessor.submit(() -> "queued");
        assertEquals("queued", queued.get(5, TimeUnit.SECONDS));

        assertTrue(AsyncPathProcessor.stats().admitted() > admittedBefore,
            "the final solve must be submitted to the running executor");
        assertEquals(0L, AsyncPathProcessor.stats().inline(),
            "a running pool must never execute saturation fallback on the caller");
        assertTrue(AsyncPathProcessor.stats().slowestWaitMillis() >= waitBefore,
            "queue wait telemetry must remain monotonic");
    }


    @Test
    void saturationRefusesInsteadOfRunningOnSubmittingThread() throws Exception {
        AsyncPathProcessor.setEnabled(true);
        final int workers = Math.max(1, AsyncPathProcessor.stats().poolSize());
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(workers);

        // Fill every worker, then overflow the bounded queue. submit() itself must remain
        // non-blocking: the old CallerRuns policy would execute one of these waiting suppliers
        // on this JUnit/region-equivalent submitting thread and deadlock until hold was released.
        for (int i = 0; i < workers; i++) {
            AsyncPathProcessor.submit(() -> {
                started.countDown();
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "worker";
            });
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "all path workers should be occupied");

        final long refusedBefore = AsyncPathProcessor.stats().refused();
        for (int i = 0; i < 1100; i++) {
            AsyncPathProcessor.submit(() -> "queued-or-refused");
        }

        final var saturated = AsyncPathProcessor.stats();
        assertTrue(saturated.refused() > refusedBefore,
            "overflowing the bounded queue must be observable as refused work");
        assertEquals(0L, saturated.inline(),
            "saturation must never run CPU-bound pathfinding on the submitting thread");

        hold.countDown();
    }

    @Test
    void aRunningPoolReportsItsSize() throws Exception {
        AsyncPathProcessor.setEnabled(true);
        assertEquals("done", AsyncPathProcessor.submit(() -> "done").get(5, TimeUnit.SECONDS));

        final var stats = AsyncPathProcessor.stats();
        assertTrue(stats.poolSize() >= 0, "a started pool reports its worker count");
        assertTrue(stats.queueDepth() >= 0, "and its queue depth");
        assertTrue(stats.activeWorkers() >= 0, "and how many workers are busy");
        assertTrue(stats.activeWorkers() <= Math.max(stats.poolSize(), 1),
            "more workers cannot be busy than exist");
    }

    @Test
    void statsNeverThrowWhateverTheLifecycleState() {
        // stats() is called from a command and from telemetry; it is documented never to throw.
        AsyncPathProcessor.shutdown();
        AsyncPathProcessor.stats();
        AsyncPathProcessor.setEnabled(true);
        AsyncPathProcessor.stats();
        AsyncPathProcessor.setEnabled(false);
        AsyncPathProcessor.stats();
        AsyncPathProcessor.shutdown();
        AsyncPathProcessor.stats();
    }
}
