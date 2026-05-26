package dev.iyanz.sourbycraft.async;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class AsyncWorkerPoolTest {

    @Test
    void submitAndDrainProducesDiff() throws Exception {
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        AsyncWorkerPool<Integer, Integer> pool = new AsyncWorkerPool<>(
            "test", exec, 16, 3, 1000, 5.0, n -> n * 2);
        try {
            assertTrue(pool.submit(5));
            assertTrue(pool.submit(10));
            Thread.sleep(100);
            List<Integer> diffs = new ArrayList<>();
            pool.drainDiffs(diffs::add);
            assertEquals(2, diffs.size());
            assertTrue(diffs.contains(10));
            assertTrue(diffs.contains(20));
        } finally {
            pool.shutdown();
            exec.shutdownNow();
        }
    }

    @Test
    void submitTracksQueueDepth() throws Exception {
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        AsyncWorkerPool<Integer, Integer> pool = new AsyncWorkerPool<>(
            "test", exec, 16, 3, 1000, 5.0,
            n -> { try { Thread.sleep(200); } catch (InterruptedException ignored) {} return n; });
        try {
            for (int i = 0; i < 5; i++) pool.submit(i);
            Thread.sleep(20);
            // Queue depth should be observed > 0 (since consumer loop polls 50ms intervals)
            assertTrue(pool.metrics().snapshot().queueDepthHigh >= 1);
        } finally {
            pool.shutdown();
            exec.shutdownNow();
        }
    }

    @Test
    void circuitBreakerTripsAfterTimeouts() throws Exception {
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        // multiplier 5, but avg starts at 0 => floor 100ms.
        // submit tasks that sleep 2 seconds => 3 timeouts => breaker trips.
        AsyncWorkerPool<Integer, Integer> pool = new AsyncWorkerPool<>(
            "test", exec, 16, 3, 200, 5.0,
            n -> { try { Thread.sleep(2000); } catch (InterruptedException ignored) {} return n; });
        try {
            pool.submit(1);
            pool.submit(2);
            pool.submit(3);
            // Wait for watchdog cycles (deadline=100ms => ~5 cycles)
            Thread.sleep(1000);
            assertTrue(pool.breakerTripped(), "Breaker should be tripped after 3 timeouts");
            assertFalse(pool.submit(99), "Submit must be rejected once breaker is tripped");
        } finally {
            pool.shutdown();
            exec.shutdownNow();
        }
    }
}
