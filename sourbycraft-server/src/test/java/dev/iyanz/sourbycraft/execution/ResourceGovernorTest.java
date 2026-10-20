package dev.iyanz.sourbycraft.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Fixed budgets, rejection instead of caller-runs, and threads attributed to a lane. */
class ResourceGovernorTest {

    @Test
    void workBeyondTheBudgetIsRejectedNeverRunOnTheCaller() throws Exception {
        final GovernedLane lane = new GovernedLane("test", "SourbyCraft-Storage-", 1, 1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        lane.execute(() -> { started.countDown(); await(release); });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        lane.execute(() -> {});                       // queued
        final Thread caller = Thread.currentThread();
        final AtomicReference<Thread> ranOn = new AtomicReference<>();
        assertThrows(RejectedExecutionException.class, () -> lane.execute(() -> ranOn.set(Thread.currentThread())));
        release.countDown();
        assertTrue(lane.shutdown(5_000));
        assertNotEquals(caller, ranOn.get());
        final GovernedLane.Stats stats = lane.stats();
        assertEquals(3, stats.submitted());
        assertEquals(1, stats.rejected());
        assertEquals(2, stats.completed());
        assertEquals(1, stats.peakQueued());
    }

    @Test
    void laneThreadsAreAttributedToTheirExecutionLane() throws Exception {
        final GovernedLane lane = ResourceGovernor.GLOBAL.lane(ResourceGovernor.Lane.BRIDGE_IO);
        final AtomicReference<String> name = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        lane.execute(() -> { name.set(Thread.currentThread().getName()); done.countDown(); });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(ExecutionLane.PLUGIN_ASYNC, ExecutionLane.of(name.get()));
        assertEquals(ExecutionLane.WORLD_IO, ExecutionLane.of(ResourceGovernor.Lane.STORAGE.threadPrefix() + "1"));
        assertSame(lane, ResourceGovernor.GLOBAL.lane(ResourceGovernor.Lane.BRIDGE_IO));
    }

    @Test
    void aFailingTaskIsCountedAndTheLaneKeepsWorking() throws Exception {
        final GovernedLane lane = new GovernedLane("f", "SourbyCraft-Storage-", 1, 4);
        lane.execute(() -> { throw new IllegalStateException("boom"); });
        final CountDownLatch after = new CountDownLatch(1);
        lane.execute(after::countDown);
        assertTrue(after.await(5, TimeUnit.SECONDS));
        assertTrue(lane.shutdown(5_000));
        assertEquals(1, lane.stats().failed());
    }

    @Test
    void configuredBudgetsApplyToLanesCreatedAfterwards() {
        final ResourceGovernor governor = new ResourceGovernor();
        governor.configure(new dev.iyanz.sourbycraft.config.AuroraConfig.Scheduler(3, 7, 2, 5));
        assertEquals(3, governor.lane(ResourceGovernor.Lane.BRIDGE_IO).stats().threads());
        assertEquals(7, governor.lane(ResourceGovernor.Lane.BRIDGE_IO).stats().queueCapacity());
        assertEquals(2, governor.lane(ResourceGovernor.Lane.STORAGE).stats().threads());
        governor.configure(dev.iyanz.sourbycraft.config.AuroraConfig.Scheduler.DEFAULT);
        assertEquals(3, governor.lane(ResourceGovernor.Lane.BRIDGE_IO).stats().threads(), "never resized");
    }

    @Test
    void aBudgetNeedsAThreadAndAQueueSlot() {
        assertThrows(IllegalArgumentException.class, () -> new GovernedLane("x", "p-", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new GovernedLane("x", "p-", 1, 0));
    }

    @Test
    void thePathfindingLaneKeepsTheAsyncPathBudgetPriorityAndThreadNames() throws Exception {
        final ResourceGovernor governor = new ResourceGovernor();
        final GovernedLane lane = governor.lane(ResourceGovernor.Lane.PATHFINDING);
        try {
            assertEquals(Math.max(1, Runtime.getRuntime().availableProcessors() / 4), lane.stats().threads());
            assertEquals(ResourceGovernor.PATHFINDING_QUEUE, lane.stats().queueCapacity());
            final AtomicReference<Thread> ranOn = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            lane.execute(() -> { ranOn.set(Thread.currentThread()); done.countDown(); });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals("SourbyCraft-AsyncPath-1", ranOn.get().getName());
            assertEquals(ExecutionLane.ASYNC_COMPUTE, ExecutionLane.of(ranOn.get().getName()));
            assertEquals(Thread.NORM_PRIORITY - 1, ranOn.get().getPriority(), "yields to region tick threads");
            assertTrue(ranOn.get().isDaemon());
        } finally {
            lane.shutdownNow();
        }
    }

    @Test
    void shutdownNowHandsBackTheQueuedTasksThemselvesUnrun() throws Exception {
        final GovernedLane lane = new GovernedLane("t", "SourbyCraft-Storage-", 1, 4);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        lane.execute(() -> { started.countDown(); await(release); });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        final java.util.concurrent.FutureTask<String> queued = new java.util.concurrent.FutureTask<>(() -> "ran");
        lane.execute(queued);
        assertTrue(lane.hasCapacity());
        final java.util.List<Runnable> pending = lane.shutdownNow();
        release.countDown();
        // The caller cancels what it submitted, so it must get its own objects back, not wrappers.
        assertEquals(java.util.List.of(queued), pending);
        assertTrue(lane.isShutdown());
        assertTrue(!lane.hasCapacity(), "a stopped lane admits nothing");
        assertTrue(!queued.isDone(), "a task handed back never ran");
    }

    @Test
    void hasCapacityIsFalseOnceTheQueueIsFull() throws Exception {
        final GovernedLane lane = new GovernedLane("c", "SourbyCraft-Storage-", 1, 1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        lane.execute(() -> { started.countDown(); await(release); });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(lane.hasCapacity());
        lane.execute(() -> {});
        assertTrue(!lane.hasCapacity());
        release.countDown();
        assertTrue(lane.shutdown(5_000));
    }

    @Test
    void restartReplacesOnlyAStoppedLane() {
        final ResourceGovernor governor = new ResourceGovernor();
        final GovernedLane first = governor.restart(ResourceGovernor.Lane.PATHFINDING);
        assertSame(first, governor.restart(ResourceGovernor.Lane.PATHFINDING), "a live lane is kept");
        assertSame(first, governor.lane(ResourceGovernor.Lane.PATHFINDING));
        first.shutdownNow();
        final GovernedLane second = governor.restart(ResourceGovernor.Lane.PATHFINDING);
        assertNotEquals(first, second);
        assertTrue(!second.isShutdown());
        assertSame(second, governor.lane(ResourceGovernor.Lane.PATHFINDING));
        second.shutdownNow();
    }

    @Test
    void asyncPathSolvesRunOnTheGovernedPathfindingLane() throws Exception {
        dev.iyanz.sourbycraft.perf.AsyncPathProcessor.ensureStarted();
        try {
            final long before = submittedOnPathfinding();
            final String thread = dev.iyanz.sourbycraft.perf.AsyncPathProcessor
                .submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
            assertTrue(thread.startsWith(ResourceGovernor.Lane.PATHFINDING.threadPrefix()), thread);
            assertEquals(before + 1, submittedOnPathfinding());
        } finally {
            dev.iyanz.sourbycraft.perf.AsyncPathProcessor.shutdown();
        }
    }

    private static long submittedOnPathfinding() {
        return ResourceGovernor.GLOBAL.stats().stream()
            .filter(s -> s.name().equals(ResourceGovernor.Lane.PATHFINDING.display()))
            .mapToLong(GovernedLane.Stats::submitted).findFirst().orElse(0L);
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
