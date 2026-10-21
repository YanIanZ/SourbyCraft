package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute;
import dev.iyanz.sourbycraft.execution.region.RegionAnchor;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask.CancelledState;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask.ExecutionState;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

/**
 * The LightingLuminol adapter's {@code ScheduledTask} state machine, driven through a real
 * {@link BridgeRuntime} the way {@code CraftScheduler} (Paper patches 0003/0007/0009) wires it:
 * the adapter's callback becomes a Bukkit task, the bridge routes it, {@code BukkitTask#cancel}
 * reaches the bridge, and every bridge cancellation calls {@code bridgeCancelled()}.
 *
 * <p>States: IDLE -> RUNNING -> (FINISHED | IDLE) for one-shots/repeaters; IDLE -> CANCELLED;
 * RUNNING -> CANCELLED_RUNNING -> CANCELLED for repeaters cancelled during their body. A one-shot
 * cancelled while running finishes (Folia's contract).</p>
 */
class FoliaSchedulerCompatibilityTest {

    /** Mirrors {@code CraftTask} + {@code CraftScheduler.bridgeTask} for a sync Bukkit task. */
    final class FakeBukkitTask implements BukkitTask {
        final int id;
        final Plugin owner;
        final Runnable callback;
        final long period;
        volatile boolean cancelled;
        final AtomicInteger cancelCalls = new AtomicInteger();

        FakeBukkitTask(final int id, final Plugin owner, final Runnable callback, final long period) {
            this.id = id; this.owner = owner; this.callback = callback; this.period = period;
        }

        @Override public int getTaskId() { return this.id; }
        @Override public Plugin getOwner() { return this.owner; }
        @Override public boolean isSync() { return true; }
        @Override public boolean isCancelled() { return this.cancelled; }

        /** {@code CraftTask#cancel} -> {@code CraftScheduler#cancelTask}: bridge, else a no-op (0006/0009). */
        @Override public void cancel() {
            this.cancelCalls.incrementAndGet();
            FoliaSchedulerCompatibilityTest.this.bridge.cancel(this.id);
        }

        BridgeRuntime.Task bridgeTask() {
            final FakeBukkitTask self = this;
            return new BridgeRuntime.Task() {
                @Override public int id() { return self.id; }
                @Override public boolean sync() { return true; }
                @Override public boolean global() { return self.callback instanceof FoliaSchedulerCompatibility.GlobalCallback; }
                @Override public long period() { return self.period; }
                @Override public boolean cancelled() { return self.cancelled; }
                @Override public void markCancelled() {
                    self.cancelled = true;
                    if (self.callback instanceof FoliaSchedulerCompatibility.GlobalCallback callback) callback.bridgeCancelled();
                }
                @Override public Object handle() { return self; }
                @Override public void run() { self.callback.run(); }
            };
        }
    }

    static final class TestPlugin {
        final String name;
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final Plugin plugin;

        TestPlugin(final String name) {
            this.name = name;
            this.plugin = (Plugin)Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isEnabled" -> this.enabled.get();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "TestPlugin[" + name + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        }
    }

    private final BridgeRuntimeTest.FakeExecutor executor = new BridgeRuntimeTest.FakeExecutor();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final List<FakeBukkitTask> bukkitTasks = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private BridgeRuntime bridge = runtime(3, 0, null, SyncRoute.GLOBAL);

    private BridgeRuntime runtime(final int quarantineAfter, final int pendingLimit, final RegionAnchor caller,
                                  final SyncRoute route) {
        return new BridgeRuntime(BridgeMode.SAFE, () -> quarantineAfter, this.executor, new BridgeTelemetry(),
            (message, thrown) -> this.warnings.add(message), () -> caller, () -> route,
            () -> pendingLimit, () -> 0);
    }

    private TestPlugin admitted(final String name) {
        assertTrue(this.bridge.admit(name));
        return new TestPlugin(name);
    }

    private FakeBukkitTask submit(final TestPlugin plugin, final Runnable callback, final long period, final long delay) {
        final FakeBukkitTask task = new FakeBukkitTask(this.ids.incrementAndGet(), plugin.plugin, callback, period);
        this.bukkitTasks.add(task);
        this.bridge.submit(plugin.name, plugin.plugin, task.bridgeTask(), delay);
        return task;
    }

    private ScheduledTask oneShot(final TestPlugin plugin, final Consumer<ScheduledTask> body) {
        return FoliaSchedulerCompatibility.schedule(plugin.plugin, body, -1, callback -> submit(plugin, callback, 0, 1));
    }

    private ScheduledTask repeater(final TestPlugin plugin, final Consumer<ScheduledTask> body) {
        return FoliaSchedulerCompatibility.schedule(plugin.plugin, body, 1, callback -> submit(plugin, callback, 1, 1));
    }

    private int lastJob() {
        return this.executor.jobs.size() - 1;
    }

    private FakeBukkitTask lastBukkitTask() {
        return this.bukkitTasks.get(this.bukkitTasks.size() - 1);
    }

    // ---- entry-point validation (before Bukkit is touched) ----

    @Test
    void entryPointsValidateLikeFoliasGlobalScheduler() {
        final TestPlugin plugin = new TestPlugin("Legacy");
        final Consumer<ScheduledTask> body = task -> {};
        assertThrows(NullPointerException.class, () -> FoliaSchedulerCompatibility.runDelayedOnBukkit(null, body, 1));
        assertThrows(NullPointerException.class, () -> FoliaSchedulerCompatibility.runDelayedOnBukkit(plugin.plugin, null, 1));
        assertThrows(IllegalArgumentException.class, () -> FoliaSchedulerCompatibility.runDelayedOnBukkit(plugin.plugin, body, 0));
        assertThrows(IllegalArgumentException.class,
            () -> FoliaSchedulerCompatibility.runAtFixedRateOnBukkit(plugin.plugin, body, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> FoliaSchedulerCompatibility.runAtFixedRateOnBukkit(plugin.plugin, body, 1, 0));
        plugin.enabled.set(false);
        assertThrows(IllegalPluginAccessException.class,
            () -> FoliaSchedulerCompatibility.runDelayedOnBukkit(plugin.plugin, body, 1));
        assertThrows(IllegalPluginAccessException.class,
            () -> FoliaSchedulerCompatibility.runAtFixedRateOnBukkit(plugin.plugin, body, 1, 1));
        assertThrows(NullPointerException.class, () -> FoliaSchedulerCompatibility.shouldUseBukkitScheduler(null));
    }

    @Test
    void withoutABridgeRuntimeNoPluginIsRoutedAndNoRuntimeIsStarted() throws Exception {
        try (AutoCloseable ignored = AuroraBridge.resetRuntimeForTesting()) {
            assertFalse(FoliaSchedulerCompatibility.shouldUseBukkitScheduler(new TestPlugin("Native").plugin));
            assertNull(AuroraBridge.modeIfStarted(), "asking must not create the bridge runtime");
        }
    }

    // ---- routing ----

    @Test
    void globalRequestsUseTheGlobalRegionEvenWhenCalledFromARegion() {
        this.bridge = runtime(3, 0, new RegionAnchor(new Object(), 0, 0), SyncRoute.CALLER_REGION);
        final TestPlugin plugin = admitted("Legacy");
        oneShot(plugin, task -> {});
        assertEquals("global", this.executor.jobs.get(lastJob()).lane(), "an explicit global request never inherits caller-region");
        submit(plugin, () -> {}, 0, 1);
        assertEquals("region", this.executor.jobs.get(lastJob()).lane(), "a plain Bukkit sync task still follows sync-route");
    }

    // ---- one-shot ----

    @Test
    void oneShotRunsOnceAndFinishes() {
        final TestPlugin plugin = admitted("Legacy");
        final AtomicReference<ExecutionState> during = new AtomicReference<>();
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledTask task = oneShot(plugin, self -> { runs.incrementAndGet(); during.set(self.getExecutionState()); });
        assertEquals(ExecutionState.IDLE, task.getExecutionState());
        assertSame(plugin.plugin, task.getOwningPlugin());
        assertFalse(task.isRepeatingTask());
        assertEquals(1, this.bridge.pending("Legacy"));

        this.executor.fire(lastJob());
        assertEquals(1, runs.get());
        assertEquals(ExecutionState.RUNNING, during.get());
        assertEquals(ExecutionState.FINISHED, task.getExecutionState());
        assertEquals(CancelledState.ALREADY_EXECUTED, task.cancel());
        assertEquals(ExecutionState.FINISHED, task.getExecutionState());
        assertEquals(0, this.bridge.pending("Legacy"), "a finished one-shot leaves no index entry");
        assertFalse(this.bridge.knows(lastBukkitTask().id));
    }

    @Test
    void idleCancellationNeverRunsAndCancelsTheBukkitTaskOnce() {
        final TestPlugin plugin = admitted("Legacy");
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledTask task = oneShot(plugin, self -> runs.incrementAndGet());
        final FakeBukkitTask bukkit = lastBukkitTask();

        assertEquals(CancelledState.CANCELLED_BY_CALLER, task.cancel());
        assertEquals(CancelledState.CANCELLED_ALREADY, task.cancel());
        assertEquals(ExecutionState.CANCELLED, task.getExecutionState());
        assertTrue(bukkit.isCancelled());
        assertEquals(1, bukkit.cancelCalls.get(), "a repeated cancel does not touch the scheduler again");
        assertEquals(0, this.bridge.pending("Legacy"));

        this.executor.fire(lastJob());
        assertEquals(0, runs.get());
    }

    @Test
    void oneShotCancelledWhileRunningFinishesAndReportsRunning() {
        final TestPlugin plugin = admitted("Legacy");
        final AtomicReference<CancelledState> whileRunning = new AtomicReference<>();
        final AtomicReference<ExecutionState> after = new AtomicReference<>();
        final ScheduledTask task = oneShot(plugin, self -> {
            whileRunning.set(self.cancel());
            after.set(self.getExecutionState());
        });
        this.executor.fire(lastJob());
        assertEquals(CancelledState.RUNNING, whileRunning.get());
        assertEquals(ExecutionState.RUNNING, after.get(), "a running one-shot is never interrupted or relabelled");
        assertEquals(ExecutionState.FINISHED, task.getExecutionState());
        assertEquals(0, this.bridge.pending("Legacy"));
    }

    @Test
    void anExceptionInAOneShotFinishesItAndIsAFailureNotAViolation() {
        final TestPlugin plugin = admitted("Legacy");
        final ScheduledTask task = oneShot(plugin, self -> { throw new IllegalArgumentException("plugin bug"); });
        this.executor.fire(lastJob());
        assertEquals(ExecutionState.FINISHED, task.getExecutionState());
        final BridgeTelemetry.PluginStats stats = this.bridge.telemetry().stats("Legacy");
        assertEquals(0, stats.fatalViolations());
        assertFalse(stats.quarantined());
        assertTrue(stats.lastFailure().contains("plugin bug"), stats.lastFailure());
        assertTrue(this.warnings.stream().anyMatch(line -> line.contains("generated an exception")), this.warnings.toString());
        assertEquals(0, this.bridge.pending("Legacy"));
    }

    // ---- repeater ----

    @Test
    void repeaterReturnsToIdleBetweenRunsUntilCancelled() {
        final TestPlugin plugin = admitted("Legacy");
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledTask task = repeater(plugin, self -> runs.incrementAndGet());
        final int job = lastJob();
        assertTrue(task.isRepeatingTask());
        for (int i = 0; i < 3; i++) {
            this.executor.fire(job);
            assertEquals(ExecutionState.IDLE, task.getExecutionState());
        }
        assertEquals(3, runs.get());
        assertEquals(1, this.bridge.pending("Legacy"));

        assertEquals(CancelledState.CANCELLED_BY_CALLER, task.cancel());
        assertEquals(ExecutionState.CANCELLED, task.getExecutionState());
        this.executor.fire(job);
        assertEquals(3, runs.get());
        assertEquals(0, this.bridge.pending("Legacy"));
    }

    @Test
    void selfCancellingRepeaterReportsCancelledRunningThenCancelledAndStops() {
        final TestPlugin plugin = admitted("Legacy");
        final AtomicInteger runs = new AtomicInteger();
        final List<Object> seen = new ArrayList<>();
        final ScheduledTask task = repeater(plugin, self -> {
            if (runs.incrementAndGet() == 2) {
                seen.add(self.cancel());
                seen.add(self.getExecutionState());
                seen.add(self.cancel());
            }
        });
        final int job = lastJob();
        this.executor.fire(job);
        this.executor.fire(job);
        assertEquals(List.of(CancelledState.NEXT_RUNS_CANCELLED, ExecutionState.CANCELLED_RUNNING,
            CancelledState.NEXT_RUNS_CANCELLED_ALREADY), seen);
        assertEquals(ExecutionState.CANCELLED, task.getExecutionState());
        assertEquals(CancelledState.CANCELLED_ALREADY, task.cancel());
        assertTrue(lastBukkitTask().isCancelled());
        this.executor.fire(job);
        assertEquals(2, runs.get(), "no run after the self-cancel");
        assertEquals(0, this.bridge.pending("Legacy"));
    }

    @Test
    void anExceptionInARepeaterKeepsItScheduledLikeFolia() {
        final TestPlugin plugin = admitted("Legacy");
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledTask task = repeater(plugin, self -> {
            if (runs.incrementAndGet() == 1) throw new IllegalArgumentException("first run fails");
        });
        final int job = lastJob();
        this.executor.fire(job);
        assertEquals(ExecutionState.IDLE, task.getExecutionState());
        this.executor.fire(job);
        assertEquals(2, runs.get(), "Folia's global scheduler keeps a repeater whose body threw");
        assertEquals(0, this.bridge.telemetry().stats("Legacy").fatalViolations());
        assertEquals(1, this.bridge.pending("Legacy"));
    }

    @Test
    void cancellingARepeaterFromAnotherThreadWhileItsBodyRunsDrainsCleanly() throws Exception {
        final TestPlugin plugin = admitted("Legacy");
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledTask task = repeater(plugin, self -> {
            runs.incrementAndGet();
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        final int job = lastJob();
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final Future<?> body = pool.submit(() -> this.executor.fire(job));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals(ExecutionState.RUNNING, task.getExecutionState());
            assertTrue(this.bridge.running(lastBukkitTask().id));
            assertEquals(CancelledState.NEXT_RUNS_CANCELLED, task.cancel());
            assertEquals(ExecutionState.CANCELLED_RUNNING, task.getExecutionState());
            assertEquals(CancelledState.NEXT_RUNS_CANCELLED_ALREADY, task.cancel());
            assertEquals(0, this.bridge.pending("Legacy"), "the index entry goes at cancel; the body drains");
            assertTrue(this.bridge.running(lastBukkitTask().id), "isCurrentlyRunning stays true while it drains");
            release.countDown();
            body.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(ExecutionState.CANCELLED, task.getExecutionState());
        assertFalse(this.bridge.running(lastBukkitTask().id));
        this.executor.fire(job);
        assertEquals(1, runs.get());
    }

    // ---- bridge-originated cancellation: rejection, publication races, quarantine ----

    @Test
    void aRejectionInsideSubmitIsVisibleBeforeTheHandleIsPublished() {
        final TestPlugin plugin = new TestPlugin("NotAdmitted");
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledTask task = oneShot(plugin, self -> runs.incrementAndGet());
        final FakeBukkitTask bukkit = lastBukkitTask();
        assertEquals(ExecutionState.CANCELLED, task.getExecutionState(), "rejection must not leave the adapter IDLE");
        assertTrue(bukkit.isCancelled());
        assertTrue(bukkit.cancelCalls.get() >= 1, "the late handle is cancelled when it is published");
        assertEquals(CancelledState.CANCELLED_ALREADY, task.cancel());
        assertTrue(this.executor.jobs.isEmpty(), "nothing reached the executor");
        assertEquals(0, runs.get());
    }

    @Test
    void thePendingLimitRejectsAGlobalRequestAndTheAdapterSaysCancelled() {
        this.bridge = runtime(3, 1, null, SyncRoute.GLOBAL);
        final TestPlugin plugin = admitted("Legacy");
        final ScheduledTask first = repeater(plugin, self -> {});
        final ScheduledTask second = oneShot(plugin, self -> {});
        assertEquals(ExecutionState.IDLE, first.getExecutionState());
        assertEquals(ExecutionState.CANCELLED, second.getExecutionState());
        assertEquals(0, this.bridge.telemetry().stats("Legacy").fatalViolations(), "capacity is not a violation");
        assertEquals(CancelledState.CANCELLED_BY_CALLER, first.cancel());
        final ScheduledTask third = oneShot(plugin, self -> {});
        assertEquals(ExecutionState.IDLE, third.getExecutionState(), "cancellation freed the slot");
    }

    @Test
    void aCancelIssuedBeforeTheBukkitHandleExistsCancelsItOnPublication() {
        final TestPlugin plugin = admitted("Legacy");
        final AtomicReference<CancelledState> early = new AtomicReference<>();
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledTask task = FoliaSchedulerCompatibility.schedule(plugin.plugin, self -> runs.incrementAndGet(), -1,
            callback -> {
                final FakeBukkitTask bukkit = submit(plugin, callback, 0, 1);
                // Another thread cancels through the ScheduledTask before schedule() publishes the handle.
                early.set(((ScheduledTask)callback).cancel());
                assertFalse(bukkit.isCancelled(), "no handle yet, so nothing was cancelled through it");
                return bukkit;
            });
        assertEquals(CancelledState.CANCELLED_BY_CALLER, early.get());
        assertEquals(ExecutionState.CANCELLED, task.getExecutionState());
        assertTrue(lastBukkitTask().isCancelled(), "setBukkitTask cancels the handle it is given");
        assertEquals(0, this.bridge.pending("Legacy"), "no orphaned index entry");
        this.executor.fire(lastJob());
        assertEquals(0, runs.get());
    }

    @Test
    void aGuardHitInAGlobalBodyCountsTowardQuarantineAndQuarantineCancelsTheRunningRepeater() {
        this.bridge = runtime(2, 0, null, SyncRoute.GLOBAL);
        final TestPlugin plugin = admitted("Legacy");
        final AtomicInteger runs = new AtomicInteger();
        final List<ExecutionState> afterReport = new ArrayList<>();
        final List<Boolean> recorded = new ArrayList<>();
        final ScheduledTask task = repeater(plugin, self -> {
            runs.incrementAndGet();
            recorded.add(GuardViolations.report("Cannot read world data off owning region"));
            afterReport.add(self.getExecutionState());
        });
        final ScheduledTask idle = oneShot(plugin, self -> fail("quarantine must cancel idle work"));
        final int idleJob = lastJob();
        final int job = idleJob - 1;
        this.executor.fire(job);
        assertEquals(ExecutionState.IDLE, task.getExecutionState());
        this.executor.fire(job);

        assertEquals(List.of(true, true), recorded, "a global body is a bridged body: guard hits are recorded");
        assertEquals(List.of(ExecutionState.RUNNING, ExecutionState.CANCELLED_RUNNING), afterReport);
        assertTrue(this.bridge.quarantined("Legacy"));
        assertEquals(2, this.bridge.telemetry().stats("Legacy").fatalViolations());
        assertEquals(ExecutionState.CANCELLED, task.getExecutionState());
        assertEquals(ExecutionState.CANCELLED, idle.getExecutionState());
        this.executor.fire(job);
        this.executor.fire(idleJob);
        assertEquals(2, runs.get());
        assertEquals(0, this.bridge.pending("Legacy"));

        final ScheduledTask late = oneShot(plugin, self -> fail("a quarantined plugin's new task must not run"));
        assertEquals(ExecutionState.CANCELLED, late.getExecutionState());
    }

    @Test
    void aThrownRegionViolationInAGlobalBodyIsCountedAsFatal() {
        final TestPlugin plugin = admitted("Legacy");
        final ScheduledTask task = oneShot(plugin, self -> {
            throw new IllegalStateException("Cannot access block off owning region");
        });
        this.executor.fire(lastJob());
        assertEquals(ExecutionState.FINISHED, task.getExecutionState());
        assertEquals(1, this.bridge.telemetry().stats("Legacy").fatalViolations());
        assertFalse(this.bridge.quarantined("Legacy"));
    }

    // ---- plugin disable / re-enable ----

    @Test
    void aBodyDueAfterItsPluginWasDisabledCancelsInsteadOfRunning() {
        final TestPlugin plugin = admitted("Legacy");
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledTask oneShot = oneShot(plugin, self -> runs.incrementAndGet());
        final int oneShotJob = lastJob();
        final ScheduledTask repeater = repeater(plugin, self -> runs.incrementAndGet());
        final int repeaterJob = lastJob();
        plugin.enabled.set(false);
        this.executor.fire(oneShotJob);
        this.executor.fire(repeaterJob);
        assertEquals(0, runs.get());
        assertEquals(ExecutionState.CANCELLED, oneShot.getExecutionState());
        assertEquals(ExecutionState.CANCELLED, repeater.getExecutionState());
        assertEquals(0, this.bridge.pending("Legacy"));
        assertTrue(this.bukkitTasks.stream().allMatch(FakeBukkitTask::isCancelled));
    }

    @Test
    void aRepeaterWhosePluginIsDisabledDuringItsBodyEndsCancelled() {
        final TestPlugin plugin = admitted("Legacy");
        final ScheduledTask task = repeater(plugin, self -> plugin.enabled.set(false));
        final int job = lastJob();
        this.executor.fire(job);
        assertEquals(ExecutionState.CANCELLED, task.getExecutionState());
        assertTrue(lastBukkitTask().isCancelled());
        assertEquals(0, this.bridge.pending("Legacy"));
    }

    /**
     * The PaperPluginInstanceManager order from patch 0007: bridge admission closes before
     * onDisable; then Bukkit cancelTasks and GlobalRegionScheduler cancelTasks run. The same
     * instance enabled again (AuroraBridge.onPluginEnabled -> reopen) schedules afresh; nothing
     * from before the disable runs again.
     */
    @Test
    void disableCancelsIdleAndRunningWorkAndTheSameInstanceCanScheduleAfterReEnable() {
        final TestPlugin plugin = admitted("Legacy");
        final AtomicInteger staleRuns = new AtomicInteger();
        final ScheduledTask idleOneShot = oneShot(plugin, self -> staleRuns.incrementAndGet());
        final int idleOneShotJob = lastJob();
        final ScheduledTask idleRepeater = repeater(plugin, self -> staleRuns.incrementAndGet());
        final int idleRepeaterJob = lastJob();
        final AtomicReference<ExecutionState> runningDuringDisable = new AtomicReference<>();
        final AtomicReference<ScheduledTask> submittedDuringDisable = new AtomicReference<>();
        final AtomicInteger bodyRuns = new AtomicInteger();
        final ScheduledTask running = repeater(plugin, self -> {
            if (bodyRuns.incrementAndGet() > 1) staleRuns.incrementAndGet();
            plugin.enabled.set(false);
            this.bridge.disable("Legacy");
            runningDuringDisable.set(self.getExecutionState());
            // onDisable submitting again: Folia's entry point refuses; behind it the bridge refuses too.
            assertThrows(IllegalPluginAccessException.class,
                () -> FoliaSchedulerCompatibility.runDelayedOnBukkit(plugin.plugin, t -> staleRuns.incrementAndGet(), 1));
            submittedDuringDisable.set(oneShot(plugin, t -> staleRuns.incrementAndGet()));
            this.bridge.cancelAll("Legacy");
            this.bridge.cancelGlobal("Legacy");
        });
        final int runningJob = lastJob();
        this.executor.fire(runningJob);

        assertEquals(ExecutionState.CANCELLED_RUNNING, runningDuringDisable.get());
        assertEquals(ExecutionState.CANCELLED, running.getExecutionState());
        assertEquals(ExecutionState.CANCELLED, idleOneShot.getExecutionState());
        assertEquals(ExecutionState.CANCELLED, idleRepeater.getExecutionState());
        assertEquals(ExecutionState.CANCELLED, submittedDuringDisable.get().getExecutionState(),
            "a late submission behind a closed admission is cancelled, not orphaned");
        assertEquals(0, this.bridge.pending("Legacy"));
        assertTrue(this.bukkitTasks.stream().allMatch(FakeBukkitTask::isCancelled));

        // Same instance enabled again.
        plugin.enabled.set(true);
        assertTrue(this.bridge.reopen("Legacy"));
        final AtomicInteger freshRuns = new AtomicInteger();
        final ScheduledTask fresh = repeater(plugin, self -> freshRuns.incrementAndGet());
        final int freshJob = lastJob();
        for (final int stale : List.of(idleOneShotJob, idleRepeaterJob, runningJob, freshJob - 1)) {
            this.executor.fire(stale);
        }
        this.executor.fire(freshJob);
        assertEquals(0, staleRuns.get(), "nothing scheduled before the disable runs after the re-enable");
        assertEquals(1, freshRuns.get());
        assertEquals(ExecutionState.IDLE, fresh.getExecutionState());
        assertEquals(ExecutionState.CANCELLED, running.getExecutionState(), "old handles keep their final state");
        assertEquals(1, this.bridge.pending("Legacy"));
        assertFalse(this.bridge.quarantined("Legacy"));
    }

    @Test
    void aOneShotRunningDuringDisableFinishes() {
        final TestPlugin plugin = admitted("Legacy");
        final ScheduledTask task = oneShot(plugin, self -> {
            plugin.enabled.set(false);
            this.bridge.disable("Legacy");
        });
        this.executor.fire(lastJob());
        assertEquals(ExecutionState.FINISHED, task.getExecutionState());
        assertEquals(CancelledState.ALREADY_EXECUTED, task.cancel());
        assertEquals(0, this.bridge.pending("Legacy"));
    }

    /**
     * GlobalRegionScheduler#cancelTasks (patch 0007): only this plugin's global-backed entries go;
     * its async and region tasks and every other plugin's tasks stay; admission stays open.
     */
    @Test
    void globalCancellationRemovesOnlyThisPluginsGlobalBackedTasks() {
        this.bridge = runtime(3, 0, new RegionAnchor(new Object(), 1, 1), SyncRoute.CALLER_REGION);
        final TestPlugin a = admitted("A");
        final TestPlugin b = admitted("B");
        final ScheduledTask aGlobal = repeater(a, self -> {});
        final FakeBukkitTask aRegion = submit(a, () -> {}, 0, 1);
        final BridgeRuntimeTest.FakeTask aAsync = new BridgeRuntimeTest.FakeTask(this.ids.incrementAndGet(), false, 0, () -> {});
        assertTrue(this.bridge.submit("A", a.plugin, aAsync, 1));
        final ScheduledTask bGlobal = repeater(b, self -> {});
        assertEquals(3, this.bridge.pending("A"));

        assertEquals(1, this.bridge.cancelGlobal("A"));
        assertEquals(ExecutionState.CANCELLED, aGlobal.getExecutionState());
        assertFalse(aRegion.isCancelled());
        assertFalse(aAsync.cancelled());
        assertEquals(ExecutionState.IDLE, bGlobal.getExecutionState());
        assertEquals(2, this.bridge.pending("A"));
        assertEquals(1, this.bridge.pending("B"));

        final ScheduledTask again = oneShot(a, self -> {});
        assertEquals(ExecutionState.IDLE, again.getExecutionState(), "ordinary cancellation leaves admission open");

        assertEquals(3, this.bridge.cancelAll("A"), "Bukkit cancelTasks removes all of A's tasks");
        assertEquals(ExecutionState.CANCELLED, again.getExecutionState());
        assertTrue(aRegion.isCancelled());
        assertTrue(aAsync.cancelled());
        assertEquals(ExecutionState.IDLE, bGlobal.getExecutionState());
        assertEquals(1, this.bridge.pending("B"));
    }

    // ---- races ----

    /** run vs cancel on a one-shot: the cancel result and the final state always agree. */
    @Test
    void oneShotRunAndCancelRacesAgree() throws Exception {
        final TestPlugin plugin = admitted("Legacy");
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 500; i++) {
                final AtomicInteger runs = new AtomicInteger();
                final ScheduledTask task = oneShot(plugin, self -> runs.incrementAndGet());
                final int job = lastJob();
                final CyclicBarrier start = new CyclicBarrier(2);
                final Future<?> runner = pool.submit(() -> { await(start); this.executor.fire(job); });
                final Future<CancelledState> canceller = pool.submit(() -> { await(start); return task.cancel(); });
                runner.get(10, TimeUnit.SECONDS);
                final CancelledState result = canceller.get(10, TimeUnit.SECONDS);
                switch (result) {
                    case CANCELLED_BY_CALLER -> {
                        assertEquals(0, runs.get(), "iteration " + i);
                        assertEquals(ExecutionState.CANCELLED, task.getExecutionState(), "iteration " + i);
                    }
                    case RUNNING, ALREADY_EXECUTED -> {
                        assertEquals(1, runs.get(), "iteration " + i);
                        assertEquals(ExecutionState.FINISHED, task.getExecutionState(), "iteration " + i);
                    }
                    default -> fail("iteration " + i + ": unexpected " + result);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, this.bridge.pending("Legacy"), "no index entry leaks from any interleaving");
        assertEquals(0, this.bridge.telemetry().stats("Legacy").fatalViolations());
    }

    /** run vs cancel vs bridge cancellation on a repeater: always ends CANCELLED, never runs again. */
    @Test
    void repeaterRunAndCancelRacesAlwaysEndCancelled() throws Exception {
        final TestPlugin plugin = admitted("Legacy");
        final ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int i = 0; i < 300; i++) {
                final AtomicInteger runs = new AtomicInteger();
                final ScheduledTask task = repeater(plugin, self -> runs.incrementAndGet());
                final int job = lastJob();
                final int id = lastBukkitTask().id;
                final CyclicBarrier start = new CyclicBarrier(3);
                final Future<?> runner = pool.submit(() -> { await(start); this.executor.fire(job); this.executor.fire(job); });
                final Future<CancelledState> canceller = pool.submit(() -> { await(start); return task.cancel(); });
                final Future<Boolean> bridgeCancel = pool.submit(() -> { await(start); return this.bridge.cancel(id); });
                runner.get(10, TimeUnit.SECONDS);
                final CancelledState result = canceller.get(10, TimeUnit.SECONDS);
                bridgeCancel.get(10, TimeUnit.SECONDS);
                assertTrue(result == CancelledState.CANCELLED_BY_CALLER || result == CancelledState.NEXT_RUNS_CANCELLED
                    || result == CancelledState.CANCELLED_ALREADY || result == CancelledState.NEXT_RUNS_CANCELLED_ALREADY,
                    "iteration " + i + ": " + result);
                assertEquals(ExecutionState.CANCELLED, task.getExecutionState(), "iteration " + i);
                final int settled = runs.get();
                assertTrue(settled <= 2, "iteration " + i);
                this.executor.fire(job);
                assertEquals(settled, runs.get(), "iteration " + i + ": ran after cancellation");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, this.bridge.pending("Legacy"));
    }

    private static void await(final CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
