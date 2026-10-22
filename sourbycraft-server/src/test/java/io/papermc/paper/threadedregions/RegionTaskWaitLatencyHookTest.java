package io.papermc.paper.threadedregions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.Priority;
import dev.iyanz.sourbycraft.perf.RegionTaskWaitLatency;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

/**
 * Feature patch "time how long region tasks wait in their queue": drives the real patched
 * {@code RegionizedTaskQueue} (push and executeTask); only the world is a mock. Reads the
 * process-wide {@link RegionTaskWaitLatency#GLOBAL} as deltas around a single call, so it changes
 * no static state other tests depend on (JUnit runs this module's classes one at a time).
 */
@org.bukkit.support.environment.Normal
class RegionTaskWaitLatencyHookTest {

    private final ServerLevel level = mock(ServerLevel.class);
    private final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser =
        new ThreadedRegionizer<>(2, 0.5, 1, 1, 4, this.level, null);
    private final RegionizedTaskQueue.WorldRegionTaskData world;
    private final RegionizedTaskQueue.RegionTaskQueueData data;

    RegionTaskWaitLatencyHookTest() throws Exception {
        final Field field = ServerLevel.class.getField("regioniser");
        field.setAccessible(true);
        field.set(this.level, this.regioniser);
        this.world = new RegionizedTaskQueue.WorldRegionTaskData(this.level);
        this.data = new RegionizedTaskQueue.RegionTaskQueueData(this.world, null);
    }

    private static Class<?> taskType() throws ClassNotFoundException {
        return Class.forName(RegionizedTaskQueue.PrioritisedQueue.class.getName() + "$ChunkBasedPriorityTask");
    }

    private RegionizedTaskQueue.PrioritisedQueue queue(final boolean chunk) throws Exception {
        final Field field = RegionizedTaskQueue.RegionTaskQueueData.class.getDeclaredField(chunk ? "chunkQueue" : "tickTaskQueue");
        field.setAccessible(true);
        return (RegionizedTaskQueue.PrioritisedQueue) field.get(this.data);
    }

    private Object task(final boolean chunk, final Runnable run) throws Exception {
        final Constructor<?> constructor = taskType().getDeclaredConstructor(RegionizedTaskQueue.WorldRegionTaskData.class,
            int.class, int.class, boolean.class, Runnable.class, Priority.class);
        constructor.setAccessible(true);
        final Object task = constructor.newInstance(this.world, 0, 0, chunk, run, Priority.NORMAL);
        final Class<?> counterType = Class.forName(RegionizedTaskQueue.class.getName() + "$ReferenceCountData");
        final Constructor<?> counterConstructor = counterType.getDeclaredConstructor();
        counterConstructor.setAccessible(true);
        final Field counter = taskType().getDeclaredField("referenceCounter");
        counter.setAccessible(true);
        counter.set(task, counterConstructor.newInstance());
        return task;
    }

    private static void push(final RegionizedTaskQueue.PrioritisedQueue queue, final Object task) throws Exception {
        final Method tryPush = RegionizedTaskQueue.PrioritisedQueue.class.getDeclaredMethod("tryPush", taskType());
        tryPush.setAccessible(true);
        assertEquals(Boolean.TRUE, tryPush.invoke(queue, task));
    }

    private static boolean execute(final RegionizedTaskQueue.PrioritisedQueue queue) throws Exception {
        final Method method = RegionizedTaskQueue.PrioritisedQueue.class.getDeclaredMethod("executeTask");
        method.setAccessible(true);
        return (boolean) method.invoke(queue);
    }

    private static Field stampField() throws Exception {
        final Field field = taskType().getDeclaredField("sourbyQueuedAtNanos");
        field.setAccessible(true);
        return field;
    }

    private static long executed(final RegionTaskWaitLatency.Kind kind) {
        return RegionTaskWaitLatency.GLOBAL.snapshot(kind).executed();
    }

    private static long atLeast(final RegionTaskWaitLatency.Kind kind, final long micros) {
        final long[] buckets = RegionTaskWaitLatency.GLOBAL.snapshot(kind).buckets();
        long n = 0L;
        for (int b = 64 - Long.numberOfLeadingZeros(micros); b < buckets.length; b++) n += buckets[b];
        return n;
    }

    @Test
    void theFirstPushStampsTheTaskAndARePushKeepsTheStamp() throws Exception {
        final RegionizedTaskQueue.PrioritisedQueue tick = queue(false);
        final Object task = task(false, () -> {});
        assertEquals(RegionTaskWaitLatency.UNSTAMPED, stampField().getLong(task), "a new task is unstamped");

        push(tick, task);
        final long stamp = stampField().getLong(task);
        assertNotEquals(RegionTaskWaitLatency.UNSTAMPED, stamp);

        push(tick, task); // what a priority change does: the same task pushed again
        assertEquals(stamp, stampField().getLong(task), "a re-push keeps the first stamp");
        assertTrue(execute(tick));
    }

    @Test
    void runningATickTaskRecordsItsWaitUnderTick() throws Exception {
        final RegionizedTaskQueue.PrioritisedQueue tick = queue(false);
        final AtomicInteger ran = new AtomicInteger();
        final Object task = task(false, ran::incrementAndGet);
        push(tick, task);
        // Back-date the stamp instead of sleeping: the task "has waited" at least 50 ms.
        stampField().setLong(task, System.nanoTime() - 50_000_000L);
        final long tickBefore = executed(RegionTaskWaitLatency.Kind.TICK);
        final long chunkBefore = executed(RegionTaskWaitLatency.Kind.CHUNK);
        final long longBefore = atLeast(RegionTaskWaitLatency.Kind.TICK, 50_000L);

        assertTrue(execute(tick));

        assertEquals(1, ran.get());
        assertEquals(tickBefore + 1, executed(RegionTaskWaitLatency.Kind.TICK));
        assertEquals(chunkBefore, executed(RegionTaskWaitLatency.Kind.CHUNK));
        assertEquals(longBefore + 1, atLeast(RegionTaskWaitLatency.Kind.TICK, 50_000L), "the 50 ms wait lands in its bucket");
    }

    @Test
    void runningAChunkTaskRecordsUnderChunk() throws Exception {
        final RegionizedTaskQueue.PrioritisedQueue chunk = queue(true);
        push(chunk, task(true, () -> {}));
        final long chunkBefore = executed(RegionTaskWaitLatency.Kind.CHUNK);

        assertTrue(execute(chunk));

        assertEquals(chunkBefore + 1, executed(RegionTaskWaitLatency.Kind.CHUNK));
    }

    @Test
    void aCancelledTaskIsNotRecorded() throws Exception {
        final RegionizedTaskQueue.PrioritisedQueue tick = queue(false);
        final Object task = task(false, () -> {});
        push(tick, task);
        assertTrue(((PrioritisedExecutor.PrioritisedTask) task).cancel());
        final long before = executed(RegionTaskWaitLatency.Kind.TICK);

        execute(tick);

        assertEquals(before, executed(RegionTaskWaitLatency.Kind.TICK));
    }
}
