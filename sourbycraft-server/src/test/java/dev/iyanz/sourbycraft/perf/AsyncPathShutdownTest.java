package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shutdown disposal and the separation of the cancellation operations.
 *
 * <p>Stopping admission, cancelling a computation, retiring an owner and invalidating a result
 * are four different things. The first two are here; retiring an owner is in
 * {@link AsyncPathCompletionTest}; invalidating a result is the path-identity check in patch
 * 0006, which needs a bootstrapped server to exercise and is covered by the trace in
 * {@code docs/architecture/execution-contract.md} rather than by a test here.</p>
 */
class AsyncPathShutdownTest {

    @AfterEach
    void stopPool() {
        AsyncPathProcessor.shutdown();
    }

    @Test void shutdownCompletesQueuedPathFuture() throws Exception {
        final int workers = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        final var started = new CountDownLatch(workers);
        final var release = new CountDownLatch(1);
        AsyncPathProcessor.setEnabled(true);
        try {
            for (int i = 0; i < workers; i++) {
                AsyncPathProcessor.submit(() -> {
                    started.countDown();
                    try { release.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    return null;
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final var queued = AsyncPathProcessor.submit(() -> "must not run");
            AsyncPathProcessor.shutdown();
            assertNull(queued.get(5, TimeUnit.SECONDS));
            assertFalse(AsyncPathProcessor.isEnabled());
        } finally {
            release.countDown();
        }
    }

    @Test void admissionIsRefusedAfterShutdownRatherThanRunningInline() throws Exception {
        // A not-yet-started pool degrades to an inline solve on purpose. A stopped one must not:
        // that would run CPU-bound work on a region thread that is trying to shut down.
        AsyncPathProcessor.setEnabled(true);
        AsyncPathProcessor.shutdown();

        final var ran = new AtomicBoolean();
        final var refused = AsyncPathProcessor.submit(() -> { ran.set(true); return "solved"; });

        assertNull(refused.get(5, TimeUnit.SECONDS));
        assertFalse(ran.get(), "a refused submission must not run the solve anywhere");
    }

    @Test void aRefusedSubmissionStillCompletesSoTheCallerReleases() throws Exception {
        // Disposal is the point: a caller left with a future that never completes never clears
        // its pending flag, and never offers another solve for that mob again.
        AsyncPathProcessor.setEnabled(true);
        AsyncPathProcessor.shutdown();

        assertTrue(AsyncPathProcessor.submit(() -> "solved").isDone());
    }

    @Test void startingAgainLiftsTheRefusal() throws Exception {
        AsyncPathProcessor.setEnabled(true);
        AsyncPathProcessor.shutdown();
        AsyncPathProcessor.setEnabled(true);

        assertEquals("solved", AsyncPathProcessor.submit(() -> "solved").get(5, TimeUnit.SECONDS));
    }

    @Test void everyAdmittedSolveIsAccountedForUntilItCompletes() throws Exception {
        AsyncPathProcessor.setEnabled(true);
        final var release = new CountDownLatch(1);
        final var started = new CountDownLatch(1);

        final var running = AsyncPathProcessor.submit(() -> {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return "solved";
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(AsyncPathProcessor.outstanding() >= 1, "an admitted solve must be counted");

        release.countDown();
        assertEquals("solved", running.get(5, TimeUnit.SECONDS));
        assertEquals(0, awaitOutstandingSettled(), "a completed solve must stop being counted");
    }

    @Test void cancellingAComputationIsNotShuttingThePoolDown() throws Exception {
        // Separate operations with separate outcomes: the solve is abandoned, the pool keeps
        // taking work, and admission is untouched.
        AsyncPathProcessor.setEnabled(true);
        final var release = new CountDownLatch(1);
        final var started = new CountDownLatch(1);

        final var cancelled = AsyncPathProcessor.submit(() -> {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return "solved";
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        cancelled.cancel(true);

        assertTrue(cancelled.isCancelled());
        assertThrows(CancellationException.class, () -> cancelled.get(1, TimeUnit.SECONDS));
        assertTrue(AsyncPathProcessor.isEnabled(), "cancelling one solve must not stop admission");
        assertEquals("next", AsyncPathProcessor.submit(() -> "next").get(5, TimeUnit.SECONDS));
        release.countDown();
    }

    private static int awaitOutstandingSettled() throws InterruptedException {
        for (int attempt = 0; attempt < 100 && AsyncPathProcessor.outstanding() != 0; attempt++) {
            Thread.sleep(10L);
        }
        return AsyncPathProcessor.outstanding();
    }
}
