package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.bridge.BridgeRuntimeTest.FakeTask;
import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import dev.iyanz.sourbycraft.retention.Reachability;
import dev.iyanz.sourbycraft.retention.Reachability.Tracker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * What the Aurora Bridge keeps about a plugin's tasks, and that every way a task ends lets go of it.
 *
 * <p>The bridge indexes each scheduled task in several maps ({@code scheduled}, the per-plugin task
 * index, {@code running}, {@code workers}) and each entry strongly holds the owning plugin object, the
 * target entity and the task. A leak there pins a plugin's classloader across a reload, or a removed
 * entity (and its world) for as long as the server runs. Each test drives the real release path
 * (completion, cancel, disable, quarantine, entity retirement, a throwing body) and then checks, through
 * a reference queue, that the owner, task and entity became collectable; the same objects are first shown
 * to be held while the task is live, so a pass is not vacuous.</p>
 *
 * <p>What is kept on purpose, and asserted as such: the per-plugin admission record, telemetry counters
 * and rate-limit windows are keyed by plugin <em>name</em> (strings and counters only) because quarantine
 * must survive a reload of the same plugin.</p>
 */
class BridgeRetentionTest {

    /** Like the scheduler: a job lives until it has run once (one-shot) or its handle is cancelled. */
    static final class Executor implements BridgeRuntime.Executor {
        private final class Job {
            final Runnable body;
            final long period;
            final Runnable retired;

            Job(final Runnable body, final long period, final Runnable retired) {
                this.body = body;
                this.period = period;
                this.retired = retired;
            }
        }

        private final List<Job> jobs = new ArrayList<>();

        private BridgeRuntime.Handle add(final Runnable body, final long period, final Runnable retired) {
            final Job job = new Job(body, period, retired);
            synchronized (this.jobs) {
                this.jobs.add(job);
            }
            return () -> {
                synchronized (this.jobs) {
                    this.jobs.remove(job);
                }
            };
        }

        @Override
        public BridgeRuntime.Handle global(final Object owner, final Runnable body, final long delay, final long period) {
            return add(body, period, null);
        }

        @Override
        public BridgeRuntime.Handle async(final Object owner, final Runnable body, final long delay, final long period) {
            return add(body, period, null);
        }

        @Override
        public BridgeRuntime.Handle region(final Object owner, final dev.iyanz.sourbycraft.execution.region.RegionAnchor anchor,
                                           final Runnable body, final long delay, final long period) {
            return add(body, period, null);
        }

        @Override
        public BridgeRuntime.Handle entity(final Object owner, final Object entity, final Runnable body,
                                           final long delay, final long period, final Runnable retired) {
            return add(body, period, retired);
        }

        int live() {
            synchronized (this.jobs) {
                return this.jobs.size();
            }
        }

        /** Runs the oldest job once, as a scheduler tick; a one-shot is then forgotten by the scheduler. */
        void fireFirst() {
            final Job job;
            synchronized (this.jobs) {
                job = this.jobs.getFirst();
                if (job.period <= 0) this.jobs.remove(job);
            }
            job.body.run();
        }

        /** Runs the most recently added job once (used when older jobs must stay queued). */
        void fireLast() {
            final Job job;
            synchronized (this.jobs) {
                job = this.jobs.getLast();
                if (job.period <= 0) this.jobs.remove(job);
            }
            job.body.run();
        }

        /** The entity was removed before the job ran: the scheduler retires it instead of running it. */
        void retireFirst() {
            final Job job;
            synchronized (this.jobs) {
                job = this.jobs.removeFirst();
            }
            job.retired.run();
        }
    }

    /** A task targeting an entity; every object it holds is registered with a tracker. */
    private static final class Targeted extends FakeTask {
        private final Object entity;

        Targeted(final int id, final long period, final Object entity, final Runnable body) {
            super(id, true, period, body);
            this.entity = entity;
        }

        @Override
        public Object targetEntity() {
            return this.entity;
        }
    }

    private final Executor executor = new Executor();
    private final List<String> warnings = new ArrayList<>();
    private final BridgeTelemetry telemetry = new BridgeTelemetry();

    private BridgeRuntime bridge() {
        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, this.executor, this.telemetry,
            (message, thrown) -> this.warnings.add(message));
        assertTrue(bridge.admit("Legacy"));
        return bridge;
    }

    /** Schedules one task and returns trackers for everything the bridge can be holding for it. */
    private List<Tracker> schedule(final BridgeRuntime bridge, final int id, final boolean sync, final long period,
                                   final boolean entityTask, final Runnable body) {
        final Object owner = new Object() {
            @Override public String toString() { return "plugin instance"; }
        };
        final Object entity = new Object();
        final FakeTask task = entityTask ? new Targeted(id, period, entity, body) : new FakeTask(id, sync, period, body);
        assertTrue(bridge.submit("Legacy", owner, task, 0));
        final List<Tracker> trackers = new ArrayList<>();
        trackers.add(Reachability.track("owning plugin object of task " + id, owner));
        trackers.add(Reachability.track("task " + id, task));
        if (entityTask) trackers.add(Reachability.track("target entity of task " + id, entity));
        return trackers;
    }

    private static void assertHeld(final List<Tracker> trackers) {
        trackers.forEach(Tracker::assertStillReachable);
    }

    @Test
    void aFinishedOneShotTaskReleasesItsOwnerEntityAndTask() throws Exception {
        final BridgeRuntime bridge = bridge();
        final List<Tracker> held = schedule(bridge, 1, true, -1, true, () -> { });
        assertHeld(held);
        assertTrue(bridge.knows(1));
        assertEquals(1, bridge.pending("Legacy"));

        this.executor.fireFirst();

        assertFalse(bridge.knows(1));
        assertEquals(0, bridge.pending("Legacy"));
        assertEquals(0, bridge.pendingHandles().size());
        Reachability.assertAllCollected(held);
    }

    @Test
    void aTaskThatThrowsIsStillReleasedAndNothingKeepsCountingItAsRunning() throws Exception {
        final BridgeRuntime bridge = bridge();
        final List<Tracker> held = schedule(bridge, 2, true, -1, true, () -> { throw new IllegalStateException("plugin bug"); });
        assertHeld(held);

        this.executor.fireFirst();

        assertFalse(bridge.knows(2));
        assertFalse(bridge.running(2));
        assertTrue(bridge.activeWorkers().isEmpty());
        assertEquals(0, bridge.pending("Legacy"));
        assertFalse(this.warnings.isEmpty(), "the failure was reported, not swallowed");
        Reachability.assertAllCollected(held);
    }

    @Test
    void aRepeatingTaskIsHeldUntilItIsCancelledThenReleased() throws Exception {
        final BridgeRuntime bridge = bridge();
        final List<Tracker> held = schedule(bridge, 3, true, 20, true, () -> { });
        this.executor.fireFirst();
        this.executor.fireFirst();
        assertTrue(bridge.knows(3), "a repeating task stays scheduled between runs");
        assertHeld(held);

        assertTrue(bridge.cancel(3));

        assertFalse(bridge.knows(3));
        assertEquals(0, bridge.pending("Legacy"));
        assertEquals(0, this.executor.live(), "the scheduler was told to drop it");
        Reachability.assertAllCollected(held);
    }

    @Test
    void disablingAPluginReleasesEverythingItScheduledAndAdmissionStaysClosed() throws Exception {
        final BridgeRuntime bridge = bridge();
        final List<Tracker> held = new ArrayList<>();
        held.addAll(schedule(bridge, 10, true, -1, true, () -> { }));
        held.addAll(schedule(bridge, 11, false, 20, false, () -> { }));
        held.addAll(schedule(bridge, 12, true, 5, false, () -> { }));
        assertHeld(held);
        assertEquals(3, bridge.pending("Legacy"));

        assertEquals(3, bridge.disable("Legacy"));

        assertEquals(0, bridge.pending("Legacy"));
        assertEquals(0, this.executor.live());
        Reachability.assertAllCollected(held);
        final FakeTask late = new FakeTask(13, true, -1, () -> { });
        assertFalse(bridge.submit("Legacy", new Object(), late, 0), "disabled: later submissions are rejected");
        assertTrue(late.cancelled);
        assertEquals(0, bridge.pending("Legacy"));
        // By design the admission record survives: a reload of the same plugin name reopens it.
        assertTrue(bridge.isBridged("Legacy"));
        assertTrue(bridge.reopen("Legacy"));
    }

    @Test
    void anEntityRemovedBeforeItsTaskRanRetiresTheTaskAndLetsTheEntityGo() throws Exception {
        final BridgeRuntime bridge = bridge();
        final List<Tracker> held = schedule(bridge, 20, true, -1, true, () -> fail("must not run"));
        assertHeld(held);

        this.executor.retireFirst();

        assertFalse(bridge.knows(20));
        assertEquals(0, bridge.pending("Legacy"));
        assertTrue(this.telemetry.stats("Legacy").lastFailure().contains("retired"));
        Reachability.assertAllCollected(held);
    }

    @Test
    void quarantineCancelsAndReleasesEverythingThePluginHad() throws Exception {
        final BridgeRuntime bridge = bridge();
        final List<Tracker> held = new ArrayList<>();
        held.addAll(schedule(bridge, 30, true, 20, true, () -> { }));
        held.addAll(schedule(bridge, 31, false, 20, false, () -> { }));
        assertHeld(held);
        // Three region-ownership violations quarantine the plugin (threshold 3 in bridge()).
        for (int i = 0; i < 3; i++) {
            final int id = 40 + i;
            schedule(bridge, id, true, -1, false, () -> {
                throw new UnsupportedOperationException("Unsupported in region threading");
            });
            this.executor.fireLast();
        }

        assertTrue(bridge.quarantined("Legacy"));
        assertEquals(0, bridge.pending("Legacy"));
        assertEquals(0, this.executor.live());
        Reachability.assertAllCollected(held);
    }

    @Test
    void aRunningAsyncBodyIsCountedUntilItEndsEvenWhenItsPluginIsDisabledUnderneathIt() throws Exception {
        final BridgeRuntime bridge = bridge();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final List<Tracker> held = schedule(bridge, 50, false, -1, false, () -> {
            started.countDown();
            try {
                finish.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        final Thread worker = new Thread(this.executor::fireFirst, "retention-worker");
        worker.start();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertTrue(bridge.running(50));
        assertEquals(1, bridge.runningAsync("Legacy"));
        assertEquals(1, bridge.activeWorkers().size());

        bridge.disable("Legacy");
        assertEquals(1, bridge.runningAsync("Legacy"), "a claimed body drains; disable does not lie about it");
        assertEquals(1, bridge.activeWorkers().size());

        finish.countDown();
        worker.join(10_000);
        assertFalse(worker.isAlive());

        assertFalse(bridge.running(50));
        assertEquals(0, bridge.runningAsync("Legacy"));
        assertTrue(bridge.activeWorkers().isEmpty());
        assertFalse(bridge.knows(50));
        Reachability.assertAllCollected(held);
    }

    @Test
    void manyPluginsAndTasksLeaveOnlyNameKeyedRecordsBehind() {
        final BridgeRuntime bridge = bridge();
        final int plugins = 20;
        int id = 100;
        for (int p = 0; p < plugins; p++) {
            final String name = "Plugin" + p;
            assertTrue(bridge.admit(name));
            for (int t = 0; t < 10; t++) {
                assertTrue(bridge.submit(name, new Object(), new FakeTask(id++, false, -1, () -> { }), 0));
            }
        }
        while (this.executor.live() > 0) {
            this.executor.fireFirst();
        }
        for (int p = 0; p < plugins; p++) {
            assertEquals(0, bridge.pending("Plugin" + p));
        }
        assertTrue(bridge.pendingHandles().isEmpty());
        assertEquals(plugins + 1, this.telemetry.snapshot().size(), "one counters record per plugin name, nothing per task");
        for (int i = 100; i < id; i++) {
            assertFalse(bridge.knows(i));
        }
    }
}
