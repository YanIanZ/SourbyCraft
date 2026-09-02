package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.RegionTickMetrics;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    metrics.tickStarted(i);
                    metrics.tickCompleted(tick(i), 1L);
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
                    assertTrue(snapshot.activeTickStartNanos() >= 1L);
                    assertTrue(snapshot.activeTickStartNanos() <= TRANSITIONS);
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
    }

    @Test
    void stalledTickIsVisibleBeforeCompletionAndNeverDoubleCounted() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        final long start = TimeUnit.SECONDS.toNanos(10L);
        metrics.tickStarted(start);

        final RegionTickMetrics.Snapshot stalled = metrics.snapshot(start + TimeUnit.MILLISECONDS.toNanos(51L));
        assertEquals(start, stalled.activeTickStartNanos());
        assertEquals(0L, stalled.fiveSeconds().sampleCount());

        metrics.tickCompleted(new TickTime(TimeUtil.DEADLINE_NOT_SET, start, start, 0L,
            start + TimeUnit.MILLISECONDS.toNanos(60L), 0L, 0L, 0L, false), TICK_INTERVAL);

        final RegionTickMetrics.Snapshot completed = metrics.snapshot(start + TimeUnit.MILLISECONDS.toNanos(60L));
        assertEquals(RegionTickMetrics.INACTIVE, completed.activeTickStartNanos());
        assertEquals(1L, completed.fiveSeconds().sampleCount());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(60L), completed.fiveSeconds().executionNanos());
    }

    private static TickTime tick(final long start) {
        return new TickTime(start - 1L, start, start, 0L, start, 0L, 0L, 0L, false);
    }
}
