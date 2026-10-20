package io.papermc.paper.threadedregions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

/**
 * The lock-free queue depth the /perf scheduler view reads (feature patch "lock-free region task
 * queue depth and late tick counts"). Drives the real patched {@code RegionizedTaskQueue} code; only
 * the world, and for a split the target regions, are mocks. The invariant under test: whenever no
 * thread holds the queue's monitor, {@code sourbyQueued*Tasks()} equals {@code getScheduledTasks()},
 * the monitor-taking count upstream already had.
 */
@org.bukkit.support.environment.Normal
class RegionTaskQueueDepthTest {

    private final ServerLevel level = mock(ServerLevel.class);
    private final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser =
        new ThreadedRegionizer<>(2, 0.5, 1, 1, 4, this.level, null);
    private final RegionizedTaskQueue.WorldRegionTaskData world;

    RegionTaskQueueDepthTest() throws Exception {
        final Field field = ServerLevel.class.getField("regioniser");
        field.setAccessible(true);
        field.set(this.level, this.regioniser);
        this.world = new RegionizedTaskQueue.WorldRegionTaskData(this.level);
    }

    private static RegionizedTaskQueue.PrioritisedQueue queue(final RegionizedTaskQueue.RegionTaskQueueData data,
                                                              final boolean chunk) throws Exception {
        final Field field = RegionizedTaskQueue.RegionTaskQueueData.class.getDeclaredField(chunk ? "chunkQueue" : "tickTaskQueue");
        field.setAccessible(true);
        return (RegionizedTaskQueue.PrioritisedQueue) field.get(data);
    }

    private static Class<?> taskType() throws ClassNotFoundException {
        return Class.forName(RegionizedTaskQueue.PrioritisedQueue.class.getName() + "$ChunkBasedPriorityTask");
    }

    /** A task pushed straight into the queue, holding a live reference count so it can run. */
    private PrioritisedExecutor.PrioritisedTask push(final RegionizedTaskQueue.PrioritisedQueue queue, final int chunkX,
                                                    final int chunkZ, final Runnable run, final Priority priority) throws Exception {
        final Constructor<?> constructor = taskType().getDeclaredConstructor(RegionizedTaskQueue.WorldRegionTaskData.class,
            int.class, int.class, boolean.class, Runnable.class, Priority.class);
        constructor.setAccessible(true);
        final Object task = constructor.newInstance(this.world, chunkX, chunkZ, false, run, priority);
        final Class<?> counterType = Class.forName(RegionizedTaskQueue.class.getName() + "$ReferenceCountData");
        final Constructor<?> counterConstructor = counterType.getDeclaredConstructor();
        counterConstructor.setAccessible(true);
        final Field counter = taskType().getDeclaredField("referenceCounter");
        counter.setAccessible(true);
        counter.set(task, counterConstructor.newInstance());
        final Method tryPush = RegionizedTaskQueue.PrioritisedQueue.class.getDeclaredMethod("tryPush", taskType());
        tryPush.setAccessible(true);
        assertEquals(Boolean.TRUE, tryPush.invoke(queue, task));
        return (PrioritisedExecutor.PrioritisedTask) task;
    }

    private PrioritisedExecutor.PrioritisedTask push(final RegionizedTaskQueue.PrioritisedQueue queue, final Runnable run) throws Exception {
        return push(queue, 0, 0, run, Priority.NORMAL);
    }

    private static boolean execute(final RegionizedTaskQueue.PrioritisedQueue queue) throws Exception {
        final Method method = RegionizedTaskQueue.PrioritisedQueue.class.getDeclaredMethod("executeTask");
        method.setAccessible(true);
        return (boolean) method.invoke(queue);
    }

    private static void assertDepth(final RegionizedTaskQueue.RegionTaskQueueData data) throws Exception {
        assertEquals(queue(data, false).getScheduledTasks(), data.sourbyQueuedTickTasks(), "tick queue depth");
        assertEquals(queue(data, true).getScheduledTasks(), data.sourbyQueuedChunkTasks(), "chunk queue depth");
    }

    @Test
    void pushesCountUpAndEachRunCountsDown() throws Exception {
        final RegionizedTaskQueue.RegionTaskQueueData data = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        final RegionizedTaskQueue.PrioritisedQueue tick = queue(data, false);
        final AtomicInteger ran = new AtomicInteger();
        assertEquals(0, data.sourbyQueuedTickTasks());

        push(tick, ran::incrementAndGet);
        push(tick, ran::incrementAndGet);
        push(tick, ran::incrementAndGet);
        assertEquals(3, data.sourbyQueuedTickTasks());
        assertEquals(0, data.sourbyQueuedChunkTasks(), "the two queues are counted apart");

        assertTrue(execute(tick));
        assertEquals(2, data.sourbyQueuedTickTasks());
        assertDepth(data);
        assertTrue(execute(tick));
        assertTrue(execute(tick));
        assertFalse(execute(tick));
        assertEquals(3, ran.get());
        assertEquals(0, data.sourbyQueuedTickTasks());
        assertDepth(data);
    }

    @Test
    void aCancelledEntryCountsUntilItIsPolledLikeUpstreamsOwnCount() throws Exception {
        final RegionizedTaskQueue.RegionTaskQueueData data = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        final RegionizedTaskQueue.PrioritisedQueue tick = queue(data, false);
        final AtomicInteger ran = new AtomicInteger();
        final PrioritisedExecutor.PrioritisedTask cancelled = push(tick, ran::incrementAndGet);
        push(tick, ran::incrementAndGet);
        assertTrue(cancelled.cancel());

        assertEquals(2, data.sourbyQueuedTickTasks(), "a cancel does not touch the deque");
        assertDepth(data);
        // One executeTask polls the stale entry and the live one behind it.
        assertTrue(execute(tick));
        assertEquals(1, ran.get());
        assertEquals(0, data.sourbyQueuedTickTasks());
        assertDepth(data);
    }

    @Test
    void aStaleEntryAloneIsPolledAndUncountedEvenThoughNothingRuns() throws Exception {
        final RegionizedTaskQueue.RegionTaskQueueData data = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        final RegionizedTaskQueue.PrioritisedQueue tick = queue(data, false);
        assertTrue(push(tick, () -> {}).cancel());
        assertEquals(1, data.sourbyQueuedTickTasks());

        assertFalse(execute(tick));
        assertEquals(0, data.sourbyQueuedTickTasks());
    }

    @Test
    void aPollThatThrowsStillUncountsWhatItPolled() throws Exception {
        // A task that was never queue()d carries REFERENCE_COUNTER_NOT_SET; polling it throws. The
        // count must follow the deque, not the happy path.
        final RegionizedTaskQueue.RegionTaskQueueData data = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        final RegionizedTaskQueue.PrioritisedQueue tick = queue(data, false);
        final Constructor<?> constructor = taskType().getDeclaredConstructor(RegionizedTaskQueue.WorldRegionTaskData.class,
            int.class, int.class, boolean.class, Runnable.class, Priority.class);
        constructor.setAccessible(true);
        final Method tryPush = RegionizedTaskQueue.PrioritisedQueue.class.getDeclaredMethod("tryPush", taskType());
        tryPush.setAccessible(true);
        tryPush.invoke(tick, constructor.newInstance(this.world, 0, 0, false, (Runnable) () -> {}, Priority.NORMAL));
        assertEquals(1, data.sourbyQueuedTickTasks());

        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> execute(tick));
        assertEquals(0, data.sourbyQueuedTickTasks());
        assertDepth(data);
    }

    @Test
    void theUnloadDrainZeroesTheCount() throws Exception {
        final RegionizedTaskQueue.RegionTaskQueueData data = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        push(queue(data, false), () -> {});
        push(queue(data, false), () -> {});
        push(queue(data, true), () -> {});
        assertEquals(2, data.sourbyQueuedTickTasks());
        assertEquals(1, data.sourbyQueuedChunkTasks());

        assertEquals(3, data.sourbyCancelQueuedTasks());
        assertEquals(0, data.sourbyQueuedTickTasks());
        assertEquals(0, data.sourbyQueuedChunkTasks());
        assertDepth(data);
    }

    @Test
    void aMergeMovesTheCountToTheTargetAndZeroesTheSource() throws Exception {
        final RegionizedTaskQueue.RegionTaskQueueData from = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        final RegionizedTaskQueue.RegionTaskQueueData into = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        push(queue(from, false), () -> {});
        push(queue(from, false), () -> {});
        push(queue(from, true), () -> {});
        push(queue(into, false), () -> {});

        from.mergeInto(into);

        assertEquals(0, from.sourbyQueuedTickTasks(), "a merged-away queue owns no entries");
        assertEquals(0, from.sourbyQueuedChunkTasks());
        assertEquals(3, into.sourbyQueuedTickTasks());
        assertEquals(1, into.sourbyQueuedChunkTasks());
        assertDepth(into);
    }

    @Test
    void aSplitRoutesTheCountAndDropsCancelledEntries() throws Exception {
        final RegionizedTaskQueue.RegionTaskQueueData from = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        final RegionizedTaskQueue.RegionTaskQueueData west = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        final RegionizedTaskQueue.RegionTaskQueueData east = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        final Long2ReferenceOpenHashMap<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> into =
            new Long2ReferenceOpenHashMap<>();
        final int shift = this.regioniser.sectionChunkShift;
        into.put(CoordinateUtils.getChunkKey(0, 0), region(west));
        into.put(CoordinateUtils.getChunkKey(1, 0), region(east));
        final RegionizedTaskQueue.PrioritisedQueue tick = queue(from, false);
        push(tick, 0, 0, () -> {}, Priority.NORMAL);
        push(tick, 0, 0, () -> {}, Priority.HIGH);
        push(tick, 1 << shift, 0, () -> {}, Priority.NORMAL);
        assertTrue(push(tick, 1 << shift, 0, () -> {}, Priority.NORMAL).cancel());   // dropped by the split (0032)
        assertEquals(4, from.sourbyQueuedTickTasks());

        tick.split(false, this.regioniser, into);

        assertEquals(0, from.sourbyQueuedTickTasks());
        assertEquals(2, west.sourbyQueuedTickTasks());
        assertEquals(1, east.sourbyQueuedTickTasks());
        assertDepth(west);
        assertDepth(east);
    }

    @Test
    void randomOperationSequencesKeepTheCountEqualToTheLockedCount() throws Exception {
        final Random random = new Random(0x5eedL);
        final RegionizedTaskQueue.RegionTaskQueueData data = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        final List<PrioritisedExecutor.PrioritisedTask> live = new ArrayList<>();
        final Priority[] priorities = {Priority.HIGHEST, Priority.HIGH, Priority.NORMAL, Priority.LOW, Priority.IDLE};
        for (int step = 0; step < 2_000; ++step) {
            final boolean chunk = random.nextBoolean();
            final RegionizedTaskQueue.PrioritisedQueue queue = queue(data, chunk);
            switch (random.nextInt(4)) {
                case 0, 1 -> live.add(push(queue, 0, 0, () -> {}, priorities[random.nextInt(priorities.length)]));
                case 2 -> execute(queue);
                default -> {
                    if (!live.isEmpty()) {
                        live.remove(random.nextInt(live.size())).cancel();
                    }
                }
            }
            assertDepth(data);
        }
    }

    @Test
    void aConcurrentReaderNeverSeesANegativeOrRunawayCount() throws Exception {
        // Producers push while the owner drains; a reader without the monitor only ever sees counts
        // the queue really passed through (between 0 and everything pushed).
        final RegionizedTaskQueue.RegionTaskQueueData data = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
        final RegionizedTaskQueue.PrioritisedQueue tick = queue(data, false);
        final int producers = 4;
        final int perProducer = 5_000;
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicInteger bad = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; ++p) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; ++i) {
                        push(tick, () -> {});
                    }
                } catch (final Exception failure) {
                    throw new RuntimeException(failure);
                }
            }));
        }
        final Thread reader = Thread.ofPlatform().start(() -> {
            while (!done.get()) {
                final int seen = data.sourbyQueuedTickTasks();
                if (seen < 0 || seen > producers * perProducer) {
                    bad.incrementAndGet();
                }
            }
        });
        start.countDown();
        int ran = 0;
        while (ran < producers * perProducer) {
            if (execute(tick)) {
                ++ran;
            }
        }
        for (final Thread thread : threads) {
            thread.join();
        }
        done.set(true);
        reader.join();

        assertEquals(0, bad.get());
        assertEquals(0, data.sourbyQueuedTickTasks());
        assertDepth(data);
    }

    @SuppressWarnings("unchecked")
    private static ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region(
            final RegionizedTaskQueue.RegionTaskQueueData queues) {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
            mock(ThreadedRegionizer.ThreadedRegion.class);
        final TickRegions.TickRegionData data = mock(TickRegions.TickRegionData.class);
        when(region.getData()).thenReturn(data);
        when(data.getTaskQueueData()).thenReturn(queues);
        return region;
    }
}
