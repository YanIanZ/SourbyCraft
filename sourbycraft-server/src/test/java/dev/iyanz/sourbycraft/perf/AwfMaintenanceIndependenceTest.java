package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver;
import dev.iyanz.sourbycraft.awf.AwfMaintenanceDriverTesting;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AWF-3: periodic AWF commits no longer depend on the metrics collector's thread. */
@Timeout(30)
class AwfMaintenanceIndependenceTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);

    @Test
    void awfMaintenanceKeepsRunningWhileTheMetricsCollectorIsStalled() throws Exception {
        final CountDownLatch collectorEntered = new CountDownLatch(1);
        final CountDownLatch releaseCollector = new CountDownLatch(1);
        // Every read moves a second on, so the collector's loop never parks before collecting.
        final AtomicLong clock = new AtomicLong();
        final PerformanceCollector collector = new PerformanceCollector(new SourbyMetricsProvider(),
            (now, consumer) -> {
                collectorEntered.countDown();
                try {
                    releaseCollector.await();
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            },
            now -> { throw new IllegalStateException("not needed"); },
            () -> { throw new IllegalStateException("not needed"); },
            () -> clock.addAndGet(SECOND), System::currentTimeMillis, () -> 20.0,
            (message, failure) -> {});
        final CountDownLatch tenPasses = new CountDownLatch(10);
        final AwfMaintenanceDriver driver = AwfMaintenanceDriverTesting.onDaemonThread(
            TimeUnit.MILLISECONDS.toNanos(1L), tenPasses::countDown, (message, failure) -> {});
        try {
            collector.start();
            assertTrue(collectorEntered.await(10, TimeUnit.SECONDS), "the collector is inside a collection");

            driver.start();
            assertTrue(tenPasses.await(10, TimeUnit.SECONDS),
                "AWF maintenance ran ten passes while the collector was stalled");
            assertEquals(1L, releaseCollector.getCount(), "the collector was never released meanwhile");
            assertTrue(collector.worker().isAlive());
            assertEquals(Thread.State.WAITING, collector.worker().getState(), "still parked in the stalled collection");
        } finally {
            releaseCollector.countDown();
            driver.close();
            collector.close();
        }
    }

    @Test
    void theCollectorNoLongerCallsIntoAwf() throws Exception {
        final List<String> calls = new ArrayList<>();
        try (InputStream input = PerformanceCollector.class.getResourceAsStream("PerformanceCollector.class")) {
            assertTrue(input != null);
            final ClassModel model = ClassFile.of().parse(input.readAllBytes());
            for (final MethodModel method : model.methods()) {
                if (method.code().isEmpty()) continue;
                for (final CodeElement element : method.code().orElseThrow()) {
                    if (element instanceof InvokeInstruction invocation) {
                        final String owner = invocation.owner().asInternalName();
                        if (owner.startsWith("dev/iyanz/sourbycraft/awf/")) {
                            calls.add(method.methodName().stringValue() + " -> " + owner + "." + invocation.name().stringValue());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), calls, "AWF upkeep belongs to AwfMaintenanceDriver, not the metrics thread");
    }
}
