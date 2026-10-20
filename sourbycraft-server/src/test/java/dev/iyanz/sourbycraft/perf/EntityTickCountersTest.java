package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class EntityTickCountersTest {

    @Test
    void eachWorldKeepsItsOwnExactTotalsAndMaxima() {
        final EntityTickCounters counters = new EntityTickCounters();
        counters.record("world", 10, 2_000_000L);
        counters.record("world", 30, 6_000_000L);
        counters.record("world_nether", 4, 500_000L);

        final List<EntityTickCounters.Snapshot> snapshots = counters.snapshot();
        assertEquals(2, snapshots.size());
        final EntityTickCounters.Snapshot world = snapshots.get(0);
        assertEquals("world", world.world());
        assertEquals(40L, world.entityTicks());
        assertEquals(2L, world.loops());
        assertEquals(8_000_000L, world.loopNanos());
        assertEquals(30L, world.maxEntitiesInOneLoop());
        assertEquals(6_000_000L, world.maxLoopNanos());
        assertEquals(20.0, world.meanEntitiesPerLoop(), 1e-9);
        assertEquals(4.0, world.meanLoopMillis(), 1e-9);
        assertEquals(6.0, world.maxLoopMillis(), 1e-9);

        final EntityTickCounters.Snapshot nether = snapshots.get(1);
        assertEquals("world_nether", nether.world());
        assertEquals(4L, nether.entityTicks());
        assertEquals(1L, nether.loops());
    }

    @Test
    void anEmptyLoopCountsAsALoopAndANegativeDurationAsZero() {
        final EntityTickCounters counters = new EntityTickCounters();
        counters.record("world", 0, -5L);
        final EntityTickCounters.Snapshot s = counters.snapshot().get(0);
        assertEquals(0L, s.entityTicks());
        assertEquals(1L, s.loops());
        assertEquals(0L, s.loopNanos());
        assertEquals(0L, s.maxLoopNanos());
    }

    @Test
    void noWorldMeansNoSnapshotAndZeroMeans() {
        final EntityTickCounters counters = new EntityTickCounters();
        assertTrue(counters.snapshot().isEmpty());
        final EntityTickCounters.Snapshot none = new EntityTickCounters.Snapshot("w", 0, 0, 0, 0, 0);
        assertEquals(0.0, none.meanEntitiesPerLoop());
        assertEquals(0.0, none.meanLoopMillis());
    }

    @Test
    void concurrentRegionThreadsLoseNoCount() throws InterruptedException {
        final EntityTickCounters counters = new EntityTickCounters();
        final int threads = 4;
        final int loops = 10_000;
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int ticked = t + 1;
            final Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < loops; i++) {
                    counters.record("world", ticked, 1L);
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (final Thread worker : workers) {
            worker.join();
        }
        final EntityTickCounters.Snapshot s = counters.snapshot().get(0);
        assertEquals((long) threads * loops, s.loops());
        assertEquals((1L + 2L + 3L + 4L) * loops, s.entityTicks());
        assertEquals(4L, s.maxEntitiesInOneLoop());
    }

    @Test
    void theStaticHookFeedsTheGlobalCounters() {
        final String world = "entity-tick-counters-test-" + System.nanoTime();
        EntityTickCounters.regionTicked(world, 7, 1_000L);
        final EntityTickCounters.Snapshot s = EntityTickCounters.GLOBAL.snapshot().stream()
            .filter(x -> x.world().equals(world)).findFirst().orElseThrow();
        assertEquals(7L, s.entityTicks());
        assertEquals(1L, s.loops());
    }
}
