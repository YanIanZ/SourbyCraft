package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit ENG-7/ENG-14: a refused async path solve must leave the navigation free to run its
 * synchronous recompute (patch 0006 declines the offload when {@code hasCapacity()} is false or
 * {@code trySubmit} returns {@code null}), the capacity check must be answerable before the
 * snapshot is built, and solver failures must not log once per mob per recompute.
 */
class AsyncPathRefusalTest {

    @AfterEach
    void stopPool() {
        AsyncPathProcessor.shutdown();
        AsyncPathProcessor.resetFailureLogForTest();
    }

    @Test
    void aSaturatedPoolHasNoCapacityAndRefusesWithNull() throws Exception {
        AsyncPathProcessor.setEnabled(true);
        final int workers = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        final CountDownLatch started = new CountDownLatch(workers);
        final CountDownLatch hold = new CountDownLatch(1);
        try {
            for (int i = 0; i < workers; i++) {
                AsyncPathProcessor.trySubmit(() -> {
                    started.countDown();
                    try {
                        hold.await(5, TimeUnit.SECONDS);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return "worker";
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(AsyncPathProcessor.hasCapacity(), "an empty queue has room");
            // Fill the bounded queue (1024) exactly; nothing can drain it while the workers are held.
            for (int i = 0; i < 1024; i++) {
                assertTrue(AsyncPathProcessor.trySubmit(() -> "queued") != null);
            }
            assertFalse(AsyncPathProcessor.hasCapacity(), "a full queue is reported before any snapshot is paid for");

            final long refusedBefore = AsyncPathProcessor.stats().refused();
            final int outstandingBefore = AsyncPathProcessor.outstanding();
            final AtomicBoolean ran = new AtomicBoolean();
            assertNull(AsyncPathProcessor.trySubmit(() -> { ran.set(true); return "late"; }),
                "a refusal is null, so the caller runs its synchronous path");
            assertEquals(refusedBefore + 1, AsyncPathProcessor.stats().refused());
            assertEquals(outstandingBefore, AsyncPathProcessor.outstanding(), "a refused solve is not outstanding");
            assertFalse(ran.get(), "never run on the submitting thread");
            assertNull(AsyncPathProcessor.submit(() -> "late").get(1, TimeUnit.SECONDS),
                "submit keeps its completed-null contract");
        } finally {
            hold.countDown();
        }
    }

    @Test
    void aStoppedPoolHasNoCapacity() {
        AsyncPathProcessor.setEnabled(true);
        AsyncPathProcessor.shutdown();
        assertFalse(AsyncPathProcessor.hasCapacity());
        assertNull(AsyncPathProcessor.trySubmit(() -> "never"));
    }

    @Test
    void solverFailureLogsAreRateLimited() {
        AsyncPathProcessor.resetFailureLogForTest();
        final long window = AsyncPathProcessor.FAILURE_LOG_WINDOW_NANOS;
        assertEquals(0L, AsyncPathProcessor.admitFailureLog(0L), "the first failure logs");
        assertEquals(-1L, AsyncPathProcessor.admitFailureLog(1L));
        assertEquals(-1L, AsyncPathProcessor.admitFailureLog(window - 1));
        assertEquals(2L, AsyncPathProcessor.admitFailureLog(window), "the next window logs and counts the suppressed");
        assertEquals(-1L, AsyncPathProcessor.admitFailureLog(window + 1));
    }
}
