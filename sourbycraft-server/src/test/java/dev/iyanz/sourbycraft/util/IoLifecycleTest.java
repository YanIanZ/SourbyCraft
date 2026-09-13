package dev.iyanz.sourbycraft.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class IoLifecycleTest {
    @Test void saturationRejectsWithoutBlockingCallerAndShutdownRejects() throws Exception {
        final BoundedIoExecutor executor = new BoundedIoExecutor(1);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                assertTrue(Thread.currentThread().isVirtual());
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
            executor.shutdown();
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void shutdownCannotLazilyResurrectExecutor() {
        VirtualExecutor.init();
        VirtualExecutor.shutdown();
        assertThrows(RejectedExecutionException.class, VirtualExecutor::executor);
        assertTrue(VirtualExecutor.supply(() -> 1).isCompletedExceptionally());
    }

    @Test void cancellationInterruptsBlockingIo() throws Exception {
        VirtualExecutor.init();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        try {
            final var future = VirtualExecutor.supply(() -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); }
                return 1;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            future.cancel(true);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        } finally { VirtualExecutor.shutdown(); }
    }
}
