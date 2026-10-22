package io.papermc.paper.threadedregions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.Priority;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

/**
 * Region task queues: ENG-4 (a split must not die on a cancelled task whose section unloaded) and
 * the cancel-only drain an unloading world runs (ENG-3). Runs the real queue code; only the world is
 * a mock carrying a regioniser for its section shift.
 */
@org.bukkit.support.environment.Normal
class RegionTaskQueueDrainTest {

    private final ServerLevel level = mock(ServerLevel.class);
    private final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser =
        new ThreadedRegionizer<>(2, 0.5, 1, 1, 4, this.level, null);
    private final RegionizedTaskQueue.WorldRegionTaskData world;
    private final RegionizedTaskQueue.RegionTaskQueueData queueData = new RegionizedTaskQueue.RegionTaskQueueData(null, null);

    RegionTaskQueueDrainTest() throws Exception {
        final Field field = net.minecraft.server.level.ServerLevel.class.getField("regioniser");
        field.setAccessible(true);
        field.set(this.level, this.regioniser);
        this.world = new RegionizedTaskQueue.WorldRegionTaskData(this.level);
    }

    private RegionizedTaskQueue.PrioritisedQueue tickQueue() throws Exception {
        final Field field = RegionizedTaskQueue.RegionTaskQueueData.class.getDeclaredField("tickTaskQueue");
        field.setAccessible(true);
        return (RegionizedTaskQueue.PrioritisedQueue) field.get(this.queueData);
    }

    /** A task pushed straight into the queue, as queue() does once it found the region's queue. */
    private PrioritisedExecutor.PrioritisedTask queued(final RegionizedTaskQueue.PrioritisedQueue queue, final int chunkX,
                                                      final int chunkZ, final Runnable run) throws Exception {
        final Class<?> type = Class.forName(RegionizedTaskQueue.PrioritisedQueue.class.getName() + "$ChunkBasedPriorityTask");
        final Constructor<?> constructor = type.getDeclaredConstructor(RegionizedTaskQueue.WorldRegionTaskData.class,
            int.class, int.class, boolean.class, Runnable.class, Priority.class);
        constructor.setAccessible(true);
        final Object task = constructor.newInstance(this.world, chunkX, chunkZ, false, run, Priority.NORMAL);
        final Method push = RegionizedTaskQueue.PrioritisedQueue.class.getDeclaredMethod("tryPush", type);
        push.setAccessible(true);
        assertEquals(Boolean.TRUE, push.invoke(queue, task));
        return (PrioritisedExecutor.PrioritisedTask) task;
    }

    @Test
    void aSplitDropsACancelledTaskWhoseSectionIsGone() throws Exception {
        final RegionizedTaskQueue.PrioritisedQueue queue = tickQueue();
        final AtomicInteger ran = new AtomicInteger();
        final PrioritisedExecutor.PrioritisedTask task = queued(queue, 100, 100, ran::incrementAndGet);
        assertTrue(task.cancel());
        // No new region owns the task's section: it unloaded once the cancel released its ticket.
        queue.split(false, this.regioniser, new Long2ReferenceOpenHashMap<>());
        assertEquals(0, ran.get());
        assertFalse(task.execute(), "a cancelled task never runs");
    }

    @Test
    void aSplitStillRefusesALiveTaskWithoutARegion() throws Exception {
        // The invariant is kept for work that can still run: losing it silently would be worse.
        final RegionizedTaskQueue.PrioritisedQueue queue = tickQueue();
        queued(queue, 100, 100, () -> {});
        assertThrows(IllegalStateException.class,
            () -> queue.split(false, this.regioniser, new Long2ReferenceOpenHashMap<>()));
    }

    @Test
    void theUnloadDrainCancelsEveryQueuedTaskWithoutRunningIt() throws Exception {
        final RegionizedTaskQueue.PrioritisedQueue queue = tickQueue();
        final AtomicInteger ran = new AtomicInteger();
        final PrioritisedExecutor.PrioritisedTask first = queued(queue, 0, 0, ran::incrementAndGet);
        final PrioritisedExecutor.PrioritisedTask second = queued(queue, 16, 0, ran::incrementAndGet);
        final PrioritisedExecutor.PrioritisedTask cancelledEarlier = queued(queue, 32, 0, ran::incrementAndGet);
        assertTrue(cancelledEarlier.cancel());
        assertTrue(this.queueData.hasTasks());

        assertEquals(2, this.queueData.sourbyCancelQueuedTasks(), "only tasks that could still run are counted");
        assertFalse(this.queueData.hasTasks());
        assertFalse(first.execute());
        assertFalse(second.cancel(), "already cancelled");
        assertEquals(Priority.COMPLETING, first.getPriority());
        assertEquals(0, ran.get());
        assertEquals(0, this.queueData.sourbyCancelQueuedTasks());
    }
}
