package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsyncPathShutdownTest {
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
            AsyncPathProcessor.shutdown();
        }
    }
}
