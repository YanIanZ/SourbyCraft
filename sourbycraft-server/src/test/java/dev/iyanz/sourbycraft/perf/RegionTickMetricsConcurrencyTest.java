package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.RegionTickMetrics;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTickMetricsConcurrencyTest {

    private static final int TRANSITIONS = 10_000_000;
    private static final long TICK_INTERVAL = TimeUnit.MILLISECONDS.toNanos(50L);

    @Test
    void activeMarkerAndPublishedSequenceRemainCoherentAcrossTenMillionTransitions() throws Exception {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread writer = Thread.ofPlatform().start(() -> {
            started.countDown();
            try {
                for (int i = 1; i <= TRANSITIONS; ++i) {
                    final long start = (long)i * TimeUnit.MILLISECONDS.toNanos(1L);
                    metrics.tickStarted(start);
                    metrics.tickCompleted(tick(start), TimeUnit.MILLISECONDS.toNanos(1L));
                }
            } catch (final Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });

        started.await();
        long lastActive = 0L;
        long lastSequence = 0L;
        try {
            while (writer.isAlive()) {
                final RegionTickMetrics.Snapshot snapshot = metrics.snapshot(TRANSITIONS + 1L);
                assertTrue(snapshot.sequence() >= lastSequence);
                if (snapshot.activeTickStartNanos() != RegionTickMetrics.INACTIVE) {
                    assertTrue(snapshot.activeTickStartNanos() >= lastActive);
                    assertTrue(snapshot.activeTickStartNanos() > snapshot.sampledAtNanos(),
                        "a completed published tick must not simultaneously remain active");
                    lastActive = snapshot.activeTickStartNanos();
                }
                lastSequence = snapshot.sequence();
            }
        } catch (final Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
        writer.join();
        if (failure.get() != null) {
            throw new AssertionError("publication observation failed", failure.get());
        }
        assertEquals(RegionTickMetrics.INACTIVE, metrics.snapshot(TRANSITIONS + 1L).activeTickStartNanos());
        assertTrue(metrics.snapshot(Long.MAX_VALUE).sequence() >= 10_000L);
    }

    @Test
    void blockedWriterPublishesStallBeforeReleaseAndDoesNotDoubleCount() throws Exception {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        final long start = TimeUnit.SECONDS.toNanos(10L);
        final CountDownLatch tickStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread writer = Thread.ofPlatform().start(() -> {
            metrics.tickStarted(start);
            tickStarted.countDown();
            try {
                assertTrue(release.await(10L, TimeUnit.SECONDS));
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            metrics.tickCompleted(new TickTime(TimeUtil.DEADLINE_NOT_SET, start, start, 0L,
                start + TimeUnit.MILLISECONDS.toNanos(60L), 0L, 0L, 0L, false), TICK_INTERVAL);
        });

        assertTrue(tickStarted.await(10L, TimeUnit.SECONDS));
        final RegionTickMetrics.Snapshot stalled = metrics.snapshot(start + TimeUnit.MILLISECONDS.toNanos(51L));
        assertEquals(start, stalled.activeTickStartNanos());
        assertEquals(0L, stalled.fiveSeconds().sampleCount());

        release.countDown();
        writer.join(TimeUnit.SECONDS.toMillis(10L));
        assertFalse(writer.isAlive());

        final RegionTickMetrics.Snapshot completed = metrics.snapshot(start + TimeUnit.MILLISECONDS.toNanos(60L));
        assertEquals(RegionTickMetrics.INACTIVE, completed.activeTickStartNanos());
        assertEquals(1L, completed.fiveSeconds().sampleCount());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(60L), completed.fiveSeconds().executionNanos());
    }

    private static TickTime tick(final long start) {
        return new TickTime(start - TimeUnit.MILLISECONDS.toNanos(1L), start, start, 0L, start, 0L, 0L, 0L, false);
    }
}
