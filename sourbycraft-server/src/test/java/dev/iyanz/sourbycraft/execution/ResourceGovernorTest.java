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

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
