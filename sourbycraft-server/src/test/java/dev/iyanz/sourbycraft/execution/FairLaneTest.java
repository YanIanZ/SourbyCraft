package dev.iyanz.sourbycraft.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class FairLaneTest {

    /** Worker threads stood in for by a queue the test drains by hand, one task at a time. */
    private static final class Workers implements Executor {
        final ArrayDeque<Runnable> started = new ArrayDeque<>();

        @Override
        public void execute(final Runnable task) {
            this.started.add(task);
        }

        void runOne() {
            this.started.poll().run();
        }

        void runAll() {
            while (!this.started.isEmpty()) runOne();
        }
    }

    private static Runnable record(final List<String> log, final String what) {
        return () -> log.add(what);
    }

    @Test
    void queuedWorkIsDispatchedRoundRobinAcrossOwnersNotFirstComeFirstServed() {
        final Workers workers = new Workers();
        final FairLane lane = new FairLane("test", 1, 64, 64, owner -> 1, workers);
        final List<String> log = new ArrayList<>();
        for (int i = 0; i < 5; i++) lane.execute("hog", record(log, "hog" + i));
        lane.execute("light", record(log, "light0"));
        lane.execute("light", record(log, "light1"));
        workers.runAll();
        assertEquals(List.of("hog0", "hog1", "light0", "hog2", "light1", "hog3", "hog4"), log,
            "hog0 started at once and hog1 was next in turn before light queued; then owners alternate");
        assertEquals(0, lane.queued());
        assertEquals(0, lane.running());
    }

    @Test
    void anOwnersWeightIsHowManyTasksItGetsPerTurn() {
        final Workers workers = new Workers();
        final FairLane lane = new FairLane("test", 1, 64, 64, owner -> owner.equals("heavy") ? 2 : 1, workers);
        final List<String> log = new ArrayList<>();
        lane.execute("first", record(log, "first"));                 // starts at once, holds the thread
        for (int i = 0; i < 4; i++) lane.execute("heavy", record(log, "h" + i));
        for (int i = 0; i < 2; i++) lane.execute("light", record(log, "l" + i));
        workers.runAll();
        assertEquals(List.of("first", "h0", "h1", "l0", "h2", "h3", "l1"), log);
    }

    @Test
    void noMoreThanTheThreadBudgetRunsAtOnce() {
        final Workers workers = new Workers();
        final FairLane lane = new FairLane("test", 2, 64, 64, owner -> 1, workers);
        for (int i = 0; i < 5; i++) lane.execute("p", () -> {});
        assertEquals(2, workers.started.size(), "two threads' worth started");
        assertEquals(2, lane.running());
        assertEquals(3, lane.queued());
        workers.runOne();
        assertEquals(2, workers.started.size(), "a finished task hands its thread to the next one");
        workers.runAll();
        assertEquals(0, lane.running());
    }

    @Test
    void thePerOwnerLimitKeepsQueueRoomForOtherOwners() {
        final Workers workers = new Workers();
        final FairLane lane = new FairLane("test", 1, 6, 3, owner -> 1, workers);
        lane.execute("hog", () -> {});                                // running, not queued
        for (int i = 0; i < 3; i++) lane.execute("hog", () -> {});
        final RejectedExecutionException hog = assertThrows(RejectedExecutionException.class, () -> lane.execute("hog", () -> {}));
        assertTrue(hog.getMessage().contains("hog"), hog.getMessage());
        for (int i = 0; i < 3; i++) lane.execute("light", () -> {});
        assertThrows(RejectedExecutionException.class, () -> lane.execute("third", () -> {}), "the lane itself is full");
        assertEquals(6, lane.queued());
        assertEquals(Map.of("hog", 1L, "third", 1L), lane.rejectedByOwner());
        workers.runAll();
        lane.execute("hog", () -> {});
        workers.runAll();
    }

    @Test
    void aFailingTaskFreesItsThreadAndIsCounted() {
        final Workers workers = new Workers();
        final FairLane lane = new FairLane("test", 1, 4, 4, owner -> 1, workers);
        final List<String> log = new ArrayList<>();
        lane.execute("p", () -> { throw new IllegalStateException("boom"); });
        lane.execute("p", record(log, "after"));
        assertThrows(IllegalStateException.class, workers::runOne);
        workers.runAll();
        assertEquals(List.of("after"), log);
        assertEquals(1L, lane.failed());
        assertEquals(1L, lane.completed());
    }

    @Test
    void shutdownRefusesNewWorkAndReturnsTheQueuedTasks() {
        final Workers workers = new Workers();
        final FairLane lane = new FairLane("test", 1, 4, 4, owner -> 1, workers);
        final Runnable running = () -> {};
        final Runnable queuedA = () -> {};
        final Runnable queuedB = () -> {};
        lane.execute("a", running);
        lane.execute("a", queuedA);
        lane.execute("b", queuedB);
        final List<Runnable> pending = lane.shutdownNow();
        assertEquals(2, pending.size());
        assertTrue(pending.contains(queuedA) && pending.contains(queuedB));
        assertThrows(RejectedExecutionException.class, () -> lane.execute("a", () -> {}));
        workers.runAll();                                             // the running task finishes; nothing else starts
        assertEquals(0, lane.running());
    }

    @Test
    void onRealThreadsEveryAdmittedTaskRunsOnALaneThread() throws Exception {
        final FairLane lane = new FairLane("real", "SourbyCraft-FairTest-", 3, 200, 100, owner -> 1);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(200);
        final java.util.Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 100; i++) {
            for (final String owner : List.of("a", "b")) {
                lane.execute(owner, () -> {
                    threads.add(Thread.currentThread().getName());
                    done.countDown();
                });
            }
        }
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "every task ran");
        assertTrue(threads.stream().allMatch(name -> name.startsWith("SourbyCraft-FairTest-")), threads.toString());
        assertTrue(threads.size() <= 3, "never more threads than the budget: " + threads);
        lane.shutdownNow();
    }

    @Test
    void budgetsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new FairLane("x", 0, 1, 1, o -> 1, Runnable::run));
        assertThrows(IllegalArgumentException.class, () -> new FairLane("x", 1, 0, 1, o -> 1, Runnable::run));
        assertThrows(IllegalArgumentException.class, () -> new FairLane("x", 1, 4, 5, o -> 1, Runnable::run));
        assertThrows(IllegalArgumentException.class, () -> new FairLane("x", 1, 4, 0, o -> 1, Runnable::run));
    }
}
