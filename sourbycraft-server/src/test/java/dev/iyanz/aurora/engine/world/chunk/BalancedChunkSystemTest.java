package dev.iyanz.aurora.engine.world.chunk;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.Priority;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine world/chunk: {@link BalancedChunkSystem}, the chunk system's shared worker pool, and
 * its {@link BalancedChunkSystem.OrderedStreamGroup} queues.
 *
 * <p>The ordering and halting tests build a pool with <b>zero</b> worker threads and drive the group by
 * hand with {@code executeTask()}, so they are exact and need no timing. The lifecycle tests use real
 * workers and only wait on latches or thread death, with generous bounds that a healthy run never
 * approaches.</p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BalancedChunkSystemTest {

    private static final long TIME_SLICE_NS = 1_000_000L;
    private final List<BalancedChunkSystem> pools = new ArrayList<>();
    private final Set<String> workerNames = ConcurrentHashMap.newKeySet();

    @AfterEach
    void stopEveryPool() {
        // every registered pool is stopped even if an earlier one fails to, and a pool whose workers do not
        // exit is reported: leaked daemon workers would run on into the next test
        final List<String> failures = new ArrayList<>();
        for (final BalancedChunkSystem pool : this.pools) {
            try {
                pool.shutdown(false);
                if (!pool.joinInterruptable(20_000L)) {
                    failures.add("workers still alive 20 s after shutdown: " + java.util.Arrays.toString(pool.getAliveThreads()));
                }
            } catch (final Throwable thrown) {
                failures.add(thrown.toString());
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }

    private BalancedChunkSystem pool(final int workers) {
        final AtomicInteger counter = new AtomicInteger();
        final BalancedChunkSystem pool = new BalancedChunkSystem(TIME_SLICE_NS, workers, runnable -> {
            final String name = "test-chunk-worker-" + counter.incrementAndGet();
            this.workerNames.add(name);
            final Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }, "test");
        this.pools.add(pool);
        return pool;
    }

    private static boolean drained(final BalancedChunkSystem.OrderedStreamGroup group) {
        return !group.executeTask();
    }

    @Test
    void aPoolWithoutWorkersRunsNothingByItself() throws InterruptedException {
        final BalancedChunkSystem pool = pool(0);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final AtomicBoolean ran = new AtomicBoolean();
        group.createExecutor().queueTask(() -> ran.set(true));

        Thread.sleep(50);   // nothing could run it; this only gives a wrongly started thread a chance to show
        assertFalse(ran.get());
        assertTrue(group.hasAnyTasks());
        assertEquals(0, pool.getCoreThreads().length);
        assertTrue(group.executeTask(), "the caller can run it");
        assertTrue(ran.get());
        assertFalse(group.hasAnyTasks());
    }

    @Test
    void theGroupRunsTheHighestPriorityFirstAcrossQueuesAndOldestFirstWithinAPriority() {
        final BalancedChunkSystem pool = pool(0);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor first = group.createExecutor();
        final ChunkSystemExecutor second = group.createExecutor();
        final List<String> order = new ArrayList<>();

        first.queueTask(() -> order.add("first-normal-1"), Priority.NORMAL);
        second.queueTask(() -> order.add("second-high-1"), Priority.HIGH);
        first.queueTask(() -> order.add("first-low"), Priority.LOW);
        second.queueTask(() -> order.add("second-normal-2"), Priority.NORMAL);
        first.queueTask(() -> order.add("first-high-2"), Priority.HIGH);
        second.queueTask(() -> order.add("second-blocking"), Priority.BLOCKING);

        while (group.executeTask()) {
            // drain
        }

        assertEquals(List.of(
            "second-blocking",
            "second-high-1", "first-high-2",
            "first-normal-1", "second-normal-2",
            "first-low"
        ), order);
    }

    @Test
    void raisingATasksPriorityMovesItAheadWhileItIsQueued() {
        final BalancedChunkSystem pool = pool(0);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor queue = group.createExecutor();
        final List<String> order = new ArrayList<>();
        queue.queueTask(() -> order.add("a"), Priority.NORMAL);
        final PrioritisedExecutor.PrioritisedTask late = queue.queueTask(() -> order.add("b"), Priority.LOW);

        assertTrue(late.raisePriority(Priority.HIGH));
        assertEquals(Priority.HIGH, late.getPriority());
        while (group.executeTask()) {
            // drain
        }

        assertEquals(List.of("b", "a"), order);
    }

    @Test
    void aCancelledTaskNeverRunsAndLeavesNoWorkBehind() {
        final BalancedChunkSystem pool = pool(0);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor queue = group.createExecutor();
        final AtomicBoolean ran = new AtomicBoolean();
        final PrioritisedExecutor.PrioritisedTask task = queue.queueTask(() -> ran.set(true));

        assertTrue(task.isQueued());
        assertTrue(task.cancel());
        assertFalse(task.isQueued());
        assertTrue(drained(group));

        assertFalse(ran.get());
        assertFalse(group.hasAnyTasks());
        assertFalse(task.cancel(), "cancelling twice reports nothing to cancel");
    }

    @Test
    void aTaskThatWasCreatedButNotQueuedIsInvisibleToTheGroupUntilQueued() {
        final BalancedChunkSystem pool = pool(0);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor queue = group.createExecutor();
        final AtomicBoolean ran = new AtomicBoolean();
        final PrioritisedExecutor.PrioritisedTask task = queue.createTask(() -> ran.set(true));

        assertTrue(drained(group));
        assertFalse(ran.get());
        assertTrue(task.queue());
        assertFalse(task.queue(), "queueing twice does not run it twice");
        assertTrue(group.executeTask());
        assertTrue(ran.get());
        assertEquals(1, queue.getTotalTasksScheduled());
        assertEquals(1, queue.getTotalTasksExecuted());
    }

    @Test
    void haltRemovesAQueueFromTheGroupWithoutShuttingItDown() {
        final BalancedChunkSystem pool = pool(0);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor kept = group.createExecutor();
        final ChunkSystemExecutor halted = group.createExecutor();
        final List<String> ran = new ArrayList<>();
        kept.queueTask(() -> ran.add("kept"));
        halted.queueTask(() -> ran.add("halted"));

        assertTrue(halted.isActive());
        halted.halt();

        assertFalse(halted.isActive(), "halted with nothing running is not active");
        assertFalse(halted.isShutdown(), "halt is not shutdown");
        while (group.executeTask()) {
            // drain
        }
        assertEquals(List.of("kept"), ran, "the group no longer serves a halted queue");

        assertTrue(halted.executeTask(), "its own tasks can still be run by whoever holds the queue");
        assertEquals(List.of("kept", "halted"), ran);
    }

    @Test
    void aHaltedQueueStaysActiveWhileATaskOfItsOwnIsRunning() {
        final BalancedChunkSystem pool = pool(0);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor queue = group.createExecutor();
        final List<Boolean> activeInside = new ArrayList<>();
        queue.queueTask(() -> activeInside.add(queue.isActive()));
        queue.halt();

        assertFalse(queue.isActive());
        assertTrue(queue.executeTask());

        assertEquals(List.of(true), activeInside);
        assertFalse(queue.isActive(), "the running counter is back to zero afterwards");
    }

    @Test
    void aTaskThatThrowsStillReleasesTheActiveCounter() {
        final BalancedChunkSystem pool = pool(0);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor queue = group.createExecutor();
        queue.queueTask(() -> {
            throw new IllegalStateException("task failure");
        });
        queue.halt();

        assertThrows(IllegalStateException.class, queue::executeTask);

        assertFalse(queue.isActive(), "a failed task must not leave a halted queue active forever");
    }

    @Test
    void aShutdownQueueStaysActiveUntilItsBacklogIsDrained() {
        final BalancedChunkSystem pool = pool(0);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor queue = group.createExecutor();
        final AtomicInteger ran = new AtomicInteger();
        queue.queueTask(ran::incrementAndGet);
        queue.queueTask(ran::incrementAndGet);

        assertTrue(queue.shutdown());
        assertTrue(queue.isShutdown());
        assertTrue(queue.isActive(), "queued work remains after shutdown");
        assertFalse(queue.shutdown(), "second shutdown reports it was already down");

        final AtomicBoolean late = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> queue.queueTask(() -> late.set(true)), "a shutdown queue accepts no new work");
        final PrioritisedExecutor.PrioritisedTask created = queue.createTask(() -> late.set(true));
        assertThrows(IllegalStateException.class, created::queue, "creating is allowed, queueing is not");

        while (group.executeTask()) {
            // drain
        }
        assertEquals(2, ran.get());
        assertFalse(late.get());
        assertFalse(queue.isActive());
    }

    @Test
    void subOrderIsSharedAcrossTheQueuesOfAGroupSoSamePriorityIsFifoOverall() {
        final BalancedChunkSystem pool = pool(0);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor a = group.createExecutor();
        final ChunkSystemExecutor b = group.createExecutor();
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            (i % 2 == 0 ? a : b).queueTask(() -> order.add(n));
        }
        while (group.executeTask()) {
            // drain
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5), order);
    }

    @Test
    void everyCallGetsTheSameSharedGroupAndNewQueuesRegisterInIt() {
        final BalancedChunkSystem pool = pool(0);
        assertTrue(pool.createChunkOrderedStreamGroup() == pool.createChunkOrderedStreamGroup());
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup(new java.util.concurrent.atomic.AtomicLong());
        assertTrue(group == pool.createChunkOrderedStreamGroup(), "the supplied counter is ignored");
        assertFalse(group.hasAnyTasks());
        group.createExecutor().queueTask(() -> { });
        assertTrue(pool.createChunkOrderedStreamGroup().hasAnyTasks());
    }

    @Test
    void joiningAPoolThatWasNeverShutDownIsAProgrammingError() {
        final BalancedChunkSystem pool = pool(0);
        assertThrows(IllegalStateException.class, () -> pool.join(10L));
        assertThrows(IllegalStateException.class, () -> pool.joinInterruptable(10L));
    }

    @Test
    void afterHaltNoNewQueuesOrGroupsAreHandedOutAndTheThreadCountIsFrozen() throws InterruptedException {
        final BalancedChunkSystem pool = pool(2);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor queue = group.createExecutor();

        pool.halt(false);

        assertThrows(IllegalStateException.class, group::createExecutor);
        assertThrows(IllegalStateException.class, pool::createChunkOrderedStreamGroup);
        assertFalse(queue.isShutdown(), "halt(false) leaves the queues alone");
        final int before = pool.getCoreThreads().length;
        pool.adjustThreadCount(6);
        assertEquals(before, pool.getCoreThreads().length, "adjustThreadCount is ignored once shut down");
        assertTrue(pool.joinInterruptable(20_000L), "halted workers exit");
        assertEquals(0, pool.getAliveThreads().length);
    }

    @Test
    void haltWithQueueShutdownShutsEveryQueueDown() throws InterruptedException {
        final BalancedChunkSystem pool = pool(1);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor one = group.createExecutor();
        final ChunkSystemExecutor two = group.createExecutor();

        pool.halt(true);

        assertTrue(one.isShutdown());
        assertTrue(two.isShutdown());
        assertTrue(pool.joinInterruptable(20_000L));
    }

    @Test
    void workersRunEveryQueuedTaskOnTheirOwnThreads() throws InterruptedException {
        final BalancedChunkSystem pool = pool(3);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor one = group.createExecutor();
        final ChunkSystemExecutor two = group.createExecutor();
        final int tasks = 300;
        final CountDownLatch done = new CountDownLatch(tasks);
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final AtomicInteger sum = new AtomicInteger();

        assertEquals(3, pool.getCoreThreads().length);
        assertEquals(3, pool.getAliveThreads().length);
        // both getters return the worker Threads themselves (they used to throw ArrayStoreException)
        for (final Thread thread : pool.getCoreThreads()) {
            assertTrue(this.workerNames.contains(thread.getName()), thread.getName());
            assertTrue(thread.isAlive());
        }
        assertEquals(java.util.Set.of(pool.getCoreThreads()), java.util.Set.of(pool.getAliveThreads()));
        for (int i = 0; i < tasks; i++) {
            final int n = i;
            (i % 2 == 0 ? one : two).queueTask(() -> {
                threads.add(Thread.currentThread().getName());
                sum.addAndGet(n);
                done.countDown();
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "workers drained " + (tasks - done.getCount()) + "/" + tasks);
        assertEquals(tasks * (tasks - 1) / 2, sum.get());
        assertTrue(this.workerNames.containsAll(threads), "tasks ran on the pool's own threads: " + threads);
        assertFalse(threads.contains(Thread.currentThread().getName()));
    }

    @Test
    void shutdownWithWaitRunsTheBacklogAndStopsEveryWorker() {
        final BalancedChunkSystem pool = pool(2);
        final BalancedChunkSystem.OrderedStreamGroup group = pool.createChunkOrderedStreamGroup();
        final ChunkSystemExecutor queue = group.createExecutor();
        final AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 200; i++) {
            queue.queueTask(ran::incrementAndGet);
        }

        pool.shutdown(true);

        assertEquals(200, ran.get(), "shutdown(true) lets queued work finish before the workers go");
        assertEquals(0, pool.getAliveThreads().length);
        assertTrue(queue.isShutdown());
        assertThrows(IllegalStateException.class, group::createExecutor);
    }

    @Test
    void aTaskThatThrowsDoesNotKillTheWorkerThatRanIt() throws InterruptedException {
        final BalancedChunkSystem pool = pool(1);
        final ChunkSystemExecutor queue = pool.createChunkOrderedStreamGroup().createExecutor();
        final CountDownLatch survived = new CountDownLatch(1);

        queue.queueTask(() -> {
            throw new IllegalStateException("expected by BalancedChunkSystemTest");
        }, Priority.HIGH);
        queue.queueTask(survived::countDown, Priority.LOW);

        assertTrue(survived.await(30, TimeUnit.SECONDS), "the only worker must keep running after a failed task");
        assertEquals(1, pool.getAliveThreads().length);
    }

    @Test
    void shrinkingTheThreadCountStopsTheSurplusWorkersAndGrowingAddsNewOnes() throws InterruptedException {
        final BalancedChunkSystem pool = pool(1);
        assertEquals(1, pool.getCoreThreads().length);

        pool.adjustThreadCount(4);
        assertEquals(4, pool.getCoreThreads().length);
        assertEquals(4, pool.getAliveThreads().length);

        final Set<Thread> before = Set.of(pool.getCoreThreads());
        pool.adjustThreadCount(2);
        final Set<Thread> survivors = Set.of(pool.getCoreThreads());
        assertEquals(2, survivors.size());
        assertTrue(before.containsAll(survivors), "shrinking keeps existing workers, it starts none");
        final Set<Thread> removed = new java.util.HashSet<>(before);
        removed.removeAll(survivors);
        assertEquals(2, removed.size());
        for (final Thread thread : removed) {
            thread.join(20_000L);
            assertFalse(thread.isAlive(), "a removed worker exits: " + thread.getName());
        }
        for (final Thread thread : survivors) {
            assertTrue(thread.isAlive(), "a surviving worker keeps running: " + thread.getName());
        }
        assertEquals(2, pool.getAliveThreads().length);

        pool.adjustThreadCount(2);   // no change is a no-op
        assertEquals(2, pool.getCoreThreads().length);

        // the survivors still serve work
        final CountDownLatch ran = new CountDownLatch(1);
        pool.createChunkOrderedStreamGroup().createExecutor().queueTask(ran::countDown);
        assertTrue(ran.await(30, TimeUnit.SECONDS));
    }
}
