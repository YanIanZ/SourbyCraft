package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Admission, routing, cancellation and quarantine, against a fake executor. */
class BridgeRuntimeTest {

    /** Records scheduled work; {@link #fire} runs it as the scheduler would. */
    static final class FakeExecutor implements BridgeRuntime.Executor {
        record Job(String lane, Runnable body, long delay, long period, AtomicBoolean cancelled) {}
        final List<Job> jobs = new ArrayList<>();

        @Override
        public BridgeRuntime.Handle global(final Object owner, final Runnable body, final long delay, final long period) {
            return add("global", body, delay, period);
        }

        @Override
        public BridgeRuntime.Handle async(final Object owner, final Runnable body, final long delay, final long period) {
            return add("async", body, delay, period);
        }

        final List<dev.iyanz.sourbycraft.execution.region.RegionAnchor> anchors = new ArrayList<>();

        @Override
        public BridgeRuntime.Handle region(final Object owner, final dev.iyanz.sourbycraft.execution.region.RegionAnchor anchor,
                                           final Runnable body, final long delay, final long period) {
            this.anchors.add(anchor);
            return add("region", body, delay, period);
        }

        final List<Object> entities = new ArrayList<>();
        final List<Runnable> retirements = new ArrayList<>();
        /** Simulates an entity already removed at scheduling time: Folia returns no task. */
        boolean entityAlreadyRemoved;

        @Override
        public BridgeRuntime.Handle entity(final Object owner, final Object entity, final Runnable body,
                                           final long delay, final long period, final Runnable retired) {
            this.entities.add(entity);
            this.retirements.add(retired);
            if (this.entityAlreadyRemoved) {
                retired.run();
                return () -> {};
            }
            return add("entity", body, delay, period);
        }

        /** Folia's retire path: the entity was removed before the job's body ran. */
        void retire(final int index) {
            this.retirements.get(index).run();
        }

        private BridgeRuntime.Handle add(final String lane, final Runnable body, final long delay, final long period) {
            final Job job = new Job(lane, body, delay, period, new AtomicBoolean());
            this.jobs.add(job);
            return () -> job.cancelled().set(true);
        }

        void fire(final int index) {
            final Job job = this.jobs.get(index);
            if (!job.cancelled().get()) job.body().run();
        }
    }

    static class FakeTask implements BridgeRuntime.Task {
        final int id;
        final boolean sync;
        final long period;
        final Runnable body;
        boolean cancelled;
        final AtomicInteger runs = new AtomicInteger();

        FakeTask(final int id, final boolean sync, final long period, final Runnable body) {
            this.id = id; this.sync = sync; this.period = period; this.body = body;
        }

        @Override public int id() { return this.id; }
        @Override public boolean sync() { return this.sync; }
        @Override public long period() { return this.period; }
        @Override public boolean cancelled() { return this.cancelled; }
        @Override public void markCancelled() { this.cancelled = true; }
        @Override public void run() { this.runs.incrementAndGet(); this.body.run(); }
    }

    private final FakeExecutor executor = new FakeExecutor();
    private final List<String> warnings = new ArrayList<>();

    private BridgeRuntime runtime(final BridgeMode mode, final int quarantineAfter) {
        return new BridgeRuntime(mode, () -> quarantineAfter, this.executor, new BridgeTelemetry(),
            (message, thrown) -> this.warnings.add(message));
    }

    @Test
    void offAdmitsNothing() {
        final BridgeRuntime bridge = runtime(BridgeMode.OFF, 3);
        assertFalse(bridge.admit("Legacy"));
        assertFalse(bridge.isBridged("Legacy"));
    }

    @Test
    void syncGoesToTheGlobalRegionAndAsyncToTheAsyncScheduler() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        assertTrue(bridge.admit("Legacy"));
        assertTrue(bridge.submit("Legacy", null, new FakeTask(1, true, -1, () -> {}), 5));
        assertTrue(bridge.submit("Legacy", null, new FakeTask(2, false, 20, () -> {}), 0));
        assertEquals("global", this.executor.jobs.get(0).lane());
        assertEquals(5, this.executor.jobs.get(0).delay());
        assertEquals(0, this.executor.jobs.get(0).period(), "a Bukkit period of -1 runs once");
        assertEquals("async", this.executor.jobs.get(1).lane());
        assertEquals(20, this.executor.jobs.get(1).period());
        assertEquals(2, bridge.telemetry().stats("Legacy").schedulerRedirects());
    }

    @Test
    void aPluginTheBridgeDidNotAdmitIsRejected() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        final FakeTask task = new FakeTask(1, true, -1, () -> {});
        assertFalse(bridge.submit("Stranger", null, task, 0));
        assertTrue(task.cancelled);
        assertTrue(this.executor.jobs.isEmpty());
    }

    @Test
    void aOneShotTaskIsForgottenAfterItRuns() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("Legacy");
        final FakeTask task = new FakeTask(1, true, -1, () -> {});
        bridge.submit("Legacy", null, task, 0);
        assertEquals(1, bridge.pending("Legacy"));
        this.executor.fire(0);
        assertEquals(1, task.runs.get());
        assertEquals(0, bridge.pending("Legacy"));
    }

    @Test
    void cancellingStopsARepeatingTask() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("Legacy");
        final FakeTask task = new FakeTask(7, true, 1, () -> {});
        bridge.submit("Legacy", null, task, 0);
        this.executor.fire(0);
        assertTrue(bridge.cancel(7));
        assertTrue(task.cancelled);
        assertTrue(this.executor.jobs.get(0).cancelled().get());
        this.executor.fire(0);
        assertEquals(1, task.runs.get());
        assertFalse(bridge.cancel(7), "a second cancel finds nothing");
    }

    @Test
    void aTaskCancelledByItsOwnFlagStopsAtItsNextRun() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("Legacy");
        final FakeTask task = new FakeTask(3, false, 1, () -> {});
        bridge.submit("Legacy", null, task, 0);
        task.cancelled = true;
        this.executor.fire(0);
        assertEquals(0, task.runs.get());
        assertTrue(this.executor.jobs.get(0).cancelled().get());
    }

    @Test
    void anOrdinaryExceptionIsRecordedButNeverQuarantines() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 1);
        bridge.admit("Legacy");
        bridge.submit("Legacy", null, new FakeTask(1, true, 1, () -> { throw new NullPointerException("bug"); }), 0);
        this.executor.fire(0);
        this.executor.fire(0);
        final BridgeTelemetry.PluginStats stats = bridge.telemetry().stats("Legacy");
        assertEquals(0, stats.fatalViolations());
        assertFalse(stats.quarantined());
        assertNotNull(stats.lastFailure());
        assertEquals(2, this.warnings.size());
    }

    @Test
    void repeatedViolationsQuarantineThePluginAndCancelItsTasks() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 2);
        bridge.admit("Legacy");
        final Runnable violate = () -> { throw new UnsupportedOperationException("Unsupported in region threading"); };
        bridge.submit("Legacy", null, new FakeTask(1, true, 1, violate), 0);
        final FakeTask innocent = new FakeTask(2, false, 1, () -> {});
        bridge.submit("Legacy", null, innocent, 0);

        this.executor.fire(0);
        assertFalse(bridge.quarantined("Legacy"));
        this.executor.fire(0);
        assertTrue(bridge.quarantined("Legacy"));
        assertTrue(innocent.cancelled, "quarantine cancels every bridged task");
        assertEquals(0, bridge.pending("Legacy"));

        final FakeTask late = new FakeTask(3, true, -1, () -> {});
        assertFalse(bridge.submit("Legacy", null, late, 0));
        assertEquals(1, bridge.telemetry().stats("Legacy").rejectedOperations());
        assertTrue(this.warnings.get(this.warnings.size() - 1).contains("quarantined"));
    }

    @Test
    void cancelAllTouchesOnlyThatPlugin() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("A");
        bridge.admit("B");
        bridge.submit("A", null, new FakeTask(1, true, 1, () -> {}), 0);
        bridge.submit("B", null, new FakeTask(2, true, 1, () -> {}), 0);
        assertEquals(1, bridge.cancelAll("A"));
        assertEquals(0, bridge.pending("A"));
        assertEquals(1, bridge.pending("B"));
    }

    @Test
    void aCancelThatRacesTheExecutorStillCancelsTheRealTask() {
        final BridgeRuntime[] holder = new BridgeRuntime[1];
        final List<AtomicBoolean> real = new ArrayList<>();
        final BridgeRuntime.Executor cancelling = new BridgeRuntime.Executor() {
            @Override public BridgeRuntime.Handle global(Object o, Runnable b, long d, long p) {
                holder[0].cancel(9); // cancelled before the executor returns its handle
                final AtomicBoolean flag = new AtomicBoolean();
                real.add(flag);
                return () -> flag.set(true);
            }
            @Override public BridgeRuntime.Handle async(Object o, Runnable b, long d, long p) { return global(o, b, d, p); }
            @Override public BridgeRuntime.Handle region(Object o, dev.iyanz.sourbycraft.execution.region.RegionAnchor a,
                                                         Runnable b, long d, long p) { return global(o, b, d, p); }
            @Override public BridgeRuntime.Handle entity(Object o, Object e, Runnable b, long d, long p,
                                                         Runnable r) { return global(o, b, d, p); }
        };
        holder[0] = new BridgeRuntime(BridgeMode.SAFE, () -> 3, cancelling, new BridgeTelemetry(), (m, t) -> {});
        holder[0].admit("Legacy");
        holder[0].submit("Legacy", null, new FakeTask(9, true, 1, () -> {}), 0);
        assertTrue(real.get(0).get());
    }

    private static final dev.iyanz.sourbycraft.execution.region.RegionAnchor HERE =
        new dev.iyanz.sourbycraft.execution.region.RegionAnchor("world", 12, -4);

    private BridgeRuntime routed(final FakeExecutor executor,
                                 final java.util.function.Supplier<dev.iyanz.sourbycraft.execution.region.RegionAnchor> caller,
                                 final dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute route) {
        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, executor, new BridgeTelemetry(),
            (m, t) -> {}, caller, () -> route);
        bridge.admit("Legacy");
        return bridge;
    }

    @Test
    void aSyncTaskFromARegionRunsOnThatRegion() {
        final FakeExecutor executor = new FakeExecutor();
        final BridgeRuntime bridge = routed(executor, () -> HERE,
            dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.CALLER_REGION);
        final FakeTask task = new FakeTask(1, true, 0, () -> {});
        assertTrue(bridge.submit("Legacy", null, task, 0));
        assertEquals("region", executor.jobs.get(0).lane());
        assertEquals(HERE, executor.anchors.get(0));
        executor.fire(0);
        assertEquals(1, task.runs.get());
        assertEquals(1, bridge.telemetry().stats("Legacy").ownerHandoffs());
    }

    @Test
    void aSyncTaskFromOutsideAnyRegionRunsGlobally() {
        final FakeExecutor executor = new FakeExecutor();
        final BridgeRuntime bridge = routed(executor, () -> null,
            dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.CALLER_REGION);
        bridge.submit("Legacy", null, new FakeTask(1, true, 0, () -> {}), 0);
        assertEquals("global", executor.jobs.get(0).lane());
        assertEquals(0, bridge.telemetry().stats("Legacy").ownerHandoffs());
    }

    @Test
    void theGlobalRouteIgnoresTheCallersRegion() {
        final FakeExecutor executor = new FakeExecutor();
        final int[] lookups = {0};
        final BridgeRuntime bridge = routed(executor, () -> { lookups[0]++; return HERE; },
            dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.GLOBAL);
        bridge.submit("Legacy", null, new FakeTask(1, true, 0, () -> {}), 0);
        assertEquals("global", executor.jobs.get(0).lane());
        assertEquals(0, lookups[0], "no region lookup when it would not be used");
    }

    @Test
    void asyncTasksNeverGoToARegion() {
        final FakeExecutor executor = new FakeExecutor();
        final BridgeRuntime bridge = routed(executor, () -> HERE,
            dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.CALLER_REGION);
        bridge.submit("Legacy", null, new FakeTask(1, false, 0, () -> {}), 0);
        assertEquals("async", executor.jobs.get(0).lane());
    }

    @Test
    void aRegionTimerIsCancelledLikeAnyOther() {
        final FakeExecutor executor = new FakeExecutor();
        final BridgeRuntime bridge = routed(executor, () -> HERE,
            dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.CALLER_REGION);
        final FakeTask timer = new FakeTask(5, true, 20, () -> {});
        bridge.submit("Legacy", null, timer, 1);
        assertEquals(20, executor.jobs.get(0).period());
        assertTrue(bridge.cancel(5));
        assertTrue(executor.jobs.get(0).cancelled().get());
    }

    @Test
    void isQueuedAndIsCurrentlyRunningSeeBridgedTasks() {
        final FakeExecutor executor = new FakeExecutor();
        final BridgeRuntime bridge = routed(executor, () -> null,
            dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.GLOBAL);
        final boolean[] runningInside = {false};
        final FakeTask task = new FakeTask(7, true, 0, () -> runningInside[0] = bridge.running(7));
        bridge.submit("Legacy", null, task, 5);
        assertTrue(bridge.knows(7), "queued until it runs");
        assertTrue(!bridge.running(7));
        executor.fire(0);
        assertTrue(runningInside[0], "running while its body executes");
        assertTrue(!bridge.knows(7) && !bridge.running(7), "a finished one-shot is neither");
    }

    @Test
    void aRepeatingTaskStaysQueuedUntilCancelled() {
        final FakeExecutor executor = new FakeExecutor();
        final BridgeRuntime bridge = routed(executor, () -> null,
            dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.GLOBAL);
        bridge.submit("Legacy", null, new FakeTask(8, true, 10, () -> {}), 0);
        executor.fire(0);
        assertTrue(bridge.knows(8));
        bridge.cancel(8);
        assertTrue(!bridge.knows(8));
    }

    @Test
    void pendingHandlesListScheduledTasksUntilTheyFinish() {
        final FakeExecutor executor = new FakeExecutor();
        final BridgeRuntime bridge = routed(executor, () -> null,
            dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.GLOBAL);
        final Object serverTask = new Object();
        final FakeTask task = new FakeTask(11, true, 0, () -> {}) {
            @Override public Object handle() { return serverTask; }
        };
        bridge.submit("Legacy", null, task, 5);
        assertEquals(java.util.List.of(serverTask), bridge.pendingHandles());
        executor.fire(0);
        assertTrue(bridge.pendingHandles().isEmpty(), "a finished one-shot is no longer pending");
    }
    @Test
    void schedulerAdmissionFailureLeavesNoPendingTask() {
        final BridgeRuntime.Executor rejecting = new BridgeRuntime.Executor() {
            @Override public BridgeRuntime.Handle global(Object o, Runnable b, long d, long p) {
                throw new java.util.concurrent.RejectedExecutionException("stopping");
            }
            @Override public BridgeRuntime.Handle async(Object o, Runnable b, long d, long p) { return global(o, b, d, p); }
            @Override public BridgeRuntime.Handle region(Object o, dev.iyanz.sourbycraft.execution.region.RegionAnchor a,
                                                         Runnable b, long d, long p) { return global(o, b, d, p); }
            @Override public BridgeRuntime.Handle entity(Object o, Object e, Runnable b, long d, long p,
                                                         Runnable r) { return global(o, b, d, p); }
        };
        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, rejecting,
            new BridgeTelemetry(), (m, t) -> this.warnings.add(m));
        bridge.admit("A");
        final FakeTask task = new FakeTask(20, true, 0, () -> {});
        assertFalse(bridge.submit("A", null, task, 0));
        assertTrue(task.cancelled());
        assertFalse(bridge.knows(20));
        assertEquals(0, bridge.pending("A"));
        assertEquals(1, bridge.telemetry().stats("A").rejectedOperations());
        assertEquals(0, bridge.telemetry().stats("A").fatalViolations());
        assertNotNull(bridge.telemetry().stats("A").lastFailure());
    }

    @Test
    void ioSaturationCancelsTheTaskAndItsLateTimerHandle() {
        final AtomicBoolean timerCancelled = new AtomicBoolean();
        final BridgeRuntime.Executor saturated = new BridgeRuntime.Executor() {
            @Override public BridgeRuntime.Handle global(Object o, Runnable b, long d, long p) { throw new AssertionError(); }
            @Override public BridgeRuntime.Handle async(Object o, Runnable b, long d, long p) { throw new AssertionError(); }
            @Override public BridgeRuntime.Handle async(Object o, Runnable b, long d, long p, Runnable rejected) {
                rejected.run();
                return () -> timerCancelled.set(true);
            }
            @Override public BridgeRuntime.Handle region(Object o, dev.iyanz.sourbycraft.execution.region.RegionAnchor a,
                                                         Runnable b, long d, long p) { throw new AssertionError(); }
            @Override public BridgeRuntime.Handle entity(Object o, Object e, Runnable b, long d, long p,
                                                         Runnable r) { throw new AssertionError(); }
        };
        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, saturated, new BridgeTelemetry(), (m, t) -> {});
        bridge.admit("A");
        final FakeTask task = new FakeTask(21, false, 0, () -> failIfRun());
        assertFalse(bridge.submit("A", null, task, 0));
        assertTrue(timerCancelled.get());
        assertTrue(task.cancelled());
        assertFalse(bridge.knows(21));
        assertEquals(0, bridge.pending("A"));
        assertEquals(1, bridge.telemetry().stats("A").rejectedOperations());
    }

    private static void failIfRun() {
        throw new AssertionError("rejected task must not run");
    }

    @Test
    void disableRacingSchedulerAdmissionClosesOnlyThatPluginsTasks() {
        final BridgeRuntime[] bridge = new BridgeRuntime[1];
        final AtomicBoolean timerCancelled = new AtomicBoolean();
        final BridgeRuntime.Executor disabling = new BridgeRuntime.Executor() {
            @Override public BridgeRuntime.Handle global(Object o, Runnable b, long d, long p) {
                bridge[0].disable("A");
                return () -> timerCancelled.set(true);
            }
            @Override public BridgeRuntime.Handle async(Object o, Runnable b, long d, long p) { return global(o, b, d, p); }
            @Override public BridgeRuntime.Handle region(Object o, dev.iyanz.sourbycraft.execution.region.RegionAnchor a,
                                                         Runnable b, long d, long p) { return global(o, b, d, p); }
            @Override public BridgeRuntime.Handle entity(Object o, Object e, Runnable b, long d, long p,
                                                         Runnable r) { return global(o, b, d, p); }
        };
        bridge[0] = new BridgeRuntime(BridgeMode.SAFE, () -> 3, disabling, new BridgeTelemetry(), (m, t) -> {});
        bridge[0].admit("A");
        bridge[0].admit("B");
        assertFalse(bridge[0].submit("A", null, new FakeTask(22, true, 1, () -> {}), 0));
        assertTrue(timerCancelled.get());
        assertFalse(bridge[0].submit("A", null, new FakeTask(23, true, 1, () -> {}), 0));
        assertTrue(bridge[0].submit("B", null, new FakeTask(24, true, 1, () -> {}), 0));
        assertEquals(0, bridge[0].pending("A"));
        assertEquals(1, bridge[0].pending("B"));
    }

    @Test
    void manyPluginsKeepIndependentIndexesThroughCompletionAndCancellation() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        for (int i = 0; i < 100; i++) {
            final String name = "Plugin" + i;
            bridge.admit(name);
            bridge.submit(name, null, new FakeTask(i * 2, true, 0, () -> {}), 0);
            bridge.submit(name, null, new FakeTask(i * 2 + 1, false, 10, () -> {}), 0);
        }
        assertEquals(2, bridge.cancelAll("Plugin50"));
        for (int i = 0; i < 100; i++) {
            if (i == 50) continue;
            assertEquals(2, bridge.pending("Plugin" + i));
            this.executor.fire(i * 2);
            assertEquals(1, bridge.pending("Plugin" + i));
            assertEquals(1, bridge.cancelAll("Plugin" + i));
        }
        assertTrue(bridge.pendingHandles().isEmpty());
    }

    @Test
    void duplicateTaskIdsNeverReplaceAnotherPluginsTask() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("A");
        bridge.admit("B");
        final FakeTask original = new FakeTask(25, true, 1, () -> {});
        assertTrue(bridge.submit("A", null, original, 0));
        assertFalse(bridge.submit("B", null, new FakeTask(25, true, 1, () -> {}), 0));
        assertEquals(1, bridge.pending("A"));
        assertEquals(0, bridge.pending("B"));
        this.executor.fire(0);
        assertEquals(1, original.runs.get());
    }

    @Test
    void runningRemainsTrueUntilEveryOverlappingInvocationFinishes() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("A");
        final AtomicInteger depth = new AtomicInteger();
        final FakeTask timer = new FakeTask(26, false, 1, () -> {
            if (depth.incrementAndGet() == 1) {
                this.executor.fire(0);
                assertTrue(bridge.running(26), "outer invocation still runs after the inner one finishes");
                bridge.cancel(26);
                assertTrue(bridge.running(26), "cancellation does not finish an active body");
            }
            depth.decrementAndGet();
        });
        bridge.submit("A", null, timer, 0);
        this.executor.fire(0);
        assertFalse(bridge.running(26));
        assertFalse(bridge.knows(26));
        assertEquals(0, bridge.pending("A"));
        assertTrue(this.warnings.isEmpty());
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final long TWENTY_MS = 20_000_000L;

    @Test
    void aRegionBodyIsTimedInTheRegionLaneOnly() {
        final FakeExecutor executor = new FakeExecutor();
        final BridgeRuntime bridge = routed(executor, () -> HERE,
            dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.CALLER_REGION);
        assertTrue(bridge.submit("Legacy", null, new FakeTask(1, true, 0, () -> sleep(20)), 0));
        assertEquals(0, bridge.telemetry().stats("Legacy").bodyTimes().region().count(), "queued, not yet run");
        executor.fire(0);
        final BridgeTelemetry.BodyTimes times = bridge.telemetry().stats("Legacy").bodyTimes();
        assertEquals(1, times.region().count());
        assertTrue(times.region().totalNanos() >= TWENTY_MS, "total " + times.region().totalNanos());
        assertTrue(times.region().maxNanos() >= TWENTY_MS);
        assertTrue(times.region().p99Nanos() >= TWENTY_MS);
        assertEquals(0, times.global().count());
        assertEquals(0, times.async().count());
    }

    @Test
    void globalAndAsyncBodiesAreTimedInTheirOwnLanes() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("Legacy");
        bridge.submit("Legacy", null, new FakeTask(1, true, -1, () -> sleep(20)), 0);
        bridge.submit("Legacy", null, new FakeTask(2, false, -1, () -> sleep(20)), 0);
        this.executor.fire(0);
        BridgeTelemetry.BodyTimes times = bridge.telemetry().stats("Legacy").bodyTimes();
        assertEquals(1, times.global().count());
        assertTrue(times.global().p50Nanos() >= TWENTY_MS);
        assertEquals(0, times.async().count());
        assertEquals(0, times.region().count());
        this.executor.fire(1);
        times = bridge.telemetry().stats("Legacy").bodyTimes();
        assertEquals(1, times.global().count());
        assertEquals(1, times.async().count());
        assertTrue(times.async().totalNanos() >= TWENTY_MS);
        assertEquals(0, times.region().count());
    }

    @Test
    void aThrowingBodyIsStillTimed() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("Legacy");
        bridge.submit("Legacy", null, new FakeTask(1, true, -1, () -> {
            sleep(20);
            throw new NullPointerException("bug");
        }), 0);
        this.executor.fire(0);
        final BridgeTelemetry.PluginStats stats = bridge.telemetry().stats("Legacy");
        assertEquals(1, stats.bodyTimes().global().count());
        assertTrue(stats.bodyTimes().global().maxNanos() >= TWENTY_MS);
        assertNotNull(stats.lastFailure());
    }

    @Test
    void theRecentWindowStaysBounded() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("Legacy");
        bridge.submit("Legacy", null, new FakeTask(1, true, 1, () -> {}), 0);
        final int runs = BridgeTelemetry.BODY_WINDOW + 44;
        for (int i = 0; i < runs; i++) this.executor.fire(0);
        final BridgeTelemetry.BodyTime global = bridge.telemetry().stats("Legacy").bodyTimes().global();
        assertEquals(runs, global.count());
        assertEquals(BridgeTelemetry.BODY_WINDOW, global.samples());
        assertTrue(global.p50Nanos() <= global.p99Nanos() && global.p99Nanos() <= global.maxNanos());
    }

    @Test
    void noBodyMeansZeroTimes() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("Legacy");
        final BridgeTelemetry.BodyTime region = bridge.telemetry().stats("Legacy").bodyTimes().region();
        assertEquals(new BridgeTelemetry.BodyTime(0, 0, 0, 0, 0, 0), region);
        assertEquals(0.0, region.meanMillis());
    }
}
