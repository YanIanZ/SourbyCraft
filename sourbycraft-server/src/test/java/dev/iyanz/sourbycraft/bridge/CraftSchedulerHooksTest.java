package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code CraftScheduler} hooks (Paper feature patches 0003, 0006, 0007, 0008, 0009): each patch site is one
 * call into {@link AuroraBridge} or {@link FoliaSchedulerCompatibility}, so the behaviour the sites used to carry
 * inline is pinned here.
 */
class CraftSchedulerHooksTest {

    private final BridgeRuntimeTest.FakeExecutor executor = new BridgeRuntimeTest.FakeExecutor();
    private final List<String> warnings = new ArrayList<>();
    private BridgeRuntime bridge;
    private AutoCloseable installed;

    @BeforeEach
    void install() {
        this.bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, this.executor, new BridgeTelemetry(),
            (message, thrown) -> this.warnings.add(message));
        this.installed = AuroraBridge.installRuntimeForTesting(this.bridge);
    }

    @AfterEach
    void restore() throws Exception {
        this.installed.close();
    }

    private static Plugin plugin(final String name) {
        return (Plugin)Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "isEnabled" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Plugin[" + name + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    /** A {@code CraftTask} stand-in: the scheduler's own task object, also a Runnable. */
    static final class SchedulerTask implements BukkitTask, Runnable {
        final int id;
        final AtomicInteger selfRuns = new AtomicInteger();

        SchedulerTask(final int id) { this.id = id; }

        @Override public int getTaskId() { return this.id; }
        @Override public Plugin getOwner() { return null; }
        @Override public boolean isSync() { return true; }
        @Override public boolean isCancelled() { return false; }
        @Override public void cancel() {}
        @Override public void run() { this.selfRuns.incrementAndGet(); }
    }

    @Test
    void submitIfBridgedTakesOnlyABridgedPluginsTask() {
        final Plugin legacy = plugin("Legacy");
        final Plugin nativePlugin = plugin("Native");
        assertTrue(this.bridge.admit("Legacy"));

        final BridgeRuntimeTest.FakeTask refused = new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {});
        assertFalse(AuroraBridge.submitIfBridged(nativePlugin, refused, 0), "native plugin: the caller refuses it");
        assertTrue(this.executor.jobs.isEmpty());
        assertFalse(refused.cancelled, "a refused task is not touched by the bridge");

        assertTrue(AuroraBridge.submitIfBridged(legacy, new BridgeRuntimeTest.FakeTask(2, true, -1, () -> {}), 4));
        assertEquals(1, this.executor.jobs.size());
        assertEquals(4, this.executor.jobs.get(0).delay());
        assertTrue(AuroraBridge.knows(2));
    }

    @Test
    void cancelTaskAlwaysStopsTheCallerAndCancelsABridgedTask() throws Exception {
        final Plugin legacy = plugin("Legacy");
        assertTrue(this.bridge.admit("Legacy"));
        final BridgeRuntimeTest.FakeTask task = new BridgeRuntimeTest.FakeTask(3, true, -1, () -> {});
        assertTrue(AuroraBridge.submitIfBridged(legacy, task, 1));

        assertTrue(AuroraBridge.cancelTask(3));
        assertTrue(task.cancelled);
        assertFalse(AuroraBridge.knows(3));
        assertTrue(this.executor.jobs.get(0).cancelled().get());
        assertTrue(AuroraBridge.cancelTask(3), "a finished/cancelled task: still a no-op, never the legacy path");
        assertTrue(AuroraBridge.cancelTask(999), "an unknown id: no-op");

        try (AutoCloseable none = AuroraBridge.resetRuntimeForTesting()) {
            assertTrue(AuroraBridge.cancelTask(3), "before the runtime exists: no-op, runtime not created");
            assertNull(AuroraBridge.modeIfStarted());
        }
    }

    @Test
    void cancelTasksAlwaysStopsTheCallerAndCancelsOnlyBridgedTasks() throws Exception {
        final Plugin legacy = plugin("Legacy");
        assertTrue(this.bridge.admit("Legacy"));
        final BridgeRuntimeTest.FakeTask a = new BridgeRuntimeTest.FakeTask(4, true, -1, () -> {});
        final BridgeRuntimeTest.FakeTask b = new BridgeRuntimeTest.FakeTask(5, false, 20, () -> {});
        AuroraBridge.submitIfBridged(legacy, a, 1);
        AuroraBridge.submitIfBridged(legacy, b, 1);

        assertTrue(AuroraBridge.cancelTasks(legacy));
        assertTrue(a.cancelled && b.cancelled);
        assertFalse(AuroraBridge.knows(4) || AuroraBridge.knows(5));
        assertTrue(AuroraBridge.cancelTasks(plugin("Native")), "a native plugin has nothing to cancel: no-op");

        try (AutoCloseable none = AuroraBridge.resetRuntimeForTesting()) {
            assertTrue(AuroraBridge.cancelTasks(legacy));
            assertNull(AuroraBridge.modeIfStarted());
        }
    }

    @Test
    void currentlyRunningAndIdInUseFollowTheTaskLifecycle() throws Exception {
        final Plugin legacy = plugin("Legacy");
        assertTrue(this.bridge.admit("Legacy"));
        final Boolean[] inside = new Boolean[1];
        final boolean[] idInsideInUse = new boolean[1];
        AuroraBridge.submitIfBridged(legacy, new BridgeRuntimeTest.FakeTask(6, true, -1, () -> {
            inside[0] = AuroraBridge.currentlyRunning(6);
            idInsideInUse[0] = AuroraBridge.idInUse(6);
        }), 1);

        assertSame(Boolean.FALSE, AuroraBridge.currentlyRunning(6), "queued, not running: answered by the bridge");
        assertTrue(AuroraBridge.idInUse(6));
        assertNull(AuroraBridge.currentlyRunning(7), "unknown id: Paper answers");
        assertFalse(AuroraBridge.idInUse(7));

        this.executor.fire(0);
        assertSame(Boolean.TRUE, inside[0], "running while its body executes");
        assertTrue(idInsideInUse[0]);
        assertNull(AuroraBridge.currentlyRunning(6), "a finished one-shot is unknown again");
        assertFalse(AuroraBridge.idInUse(6));

        try (AutoCloseable none = AuroraBridge.resetRuntimeForTesting()) {
            assertNull(AuroraBridge.currentlyRunning(6));
            assertFalse(AuroraBridge.idInUse(6));
            assertNull(AuroraBridge.modeIfStarted());
        }
    }

    @Test
    void aRepeaterCancelledInsideItsBodyStaysRunningAndItsIdStaysReserved() {
        final Plugin legacy = plugin("Legacy");
        assertTrue(this.bridge.admit("Legacy"));
        final Object[] seen = new Object[2];
        AuroraBridge.submitIfBridged(legacy, new BridgeRuntimeTest.FakeTask(8, false, 20, () -> {
            AuroraBridge.cancelTask(8);
            seen[0] = AuroraBridge.currentlyRunning(8);
            seen[1] = AuroraBridge.idInUse(8);
        }), 1);
        this.executor.fire(0);
        assertSame(Boolean.TRUE, seen[0], "a cancelled body is still running until it returns");
        assertEquals(Boolean.TRUE, seen[1], "and its id is not handed out meanwhile");
        assertNull(AuroraBridge.currentlyRunning(8));
    }

    @Test
    void pendingBukkitTasksAreTheSchedulersOwnTaskObjects() throws Exception {
        final Plugin legacy = plugin("Legacy");
        assertTrue(this.bridge.admit("Legacy"));
        final SchedulerTask handle = new SchedulerTask(9);
        final BridgeRuntime.Task task = new BridgeRuntimeTest.FakeTask(9, true, -1, () -> {}) {
            @Override public Object handle() { return handle; }
        };
        AuroraBridge.submitIfBridged(legacy, task, 1);
        assertEquals(List.of(handle), AuroraBridge.pendingBukkitTasks());

        try (AutoCloseable none = AuroraBridge.resetRuntimeForTesting()) {
            assertTrue(AuroraBridge.pendingBukkitTasks().isEmpty());
        }
    }

    @Test
    void withBridgedWorkersReturnsANewMutableListThatKeepsTheAsyncWorkers() {
        final BukkitWorker asyncWorker = new BukkitWorker() {
            @Override public int getTaskId() { return 1; }
            @Override public Plugin getOwner() { return null; }
            @Override public Thread getThread() { return Thread.currentThread(); }
        };
        final List<BukkitWorker> source = List.of(asyncWorker);
        final List<BukkitWorker> merged = AuroraBridge.withBridgedWorkers(source);
        assertEquals(source, merged, "no bridged worker running: just the async scheduler's");
        merged.add(asyncWorker);
        assertEquals(1, source.size(), "the caller gets its own list");
    }

    @Test
    void runBukkitTaskRunsTheBodyDirectlyUnlessTheTaskMustRunItself() {
        final SchedulerTask task = new SchedulerTask(10);
        final AtomicInteger runnable = new AtomicInteger();
        final List<BukkitTask> consumed = new ArrayList<>();
        final Consumer<BukkitTask> consumer = consumed::add;

        AuroraBridge.runBukkitTask(task, runnable::incrementAndGet, null, false);
        assertEquals(1, runnable.get());
        assertEquals(0, task.selfRuns.get(), "a Runnable body runs directly, not through the task");

        AuroraBridge.runBukkitTask(task, null, consumer, false);
        assertEquals(List.of(task), consumed, "a Consumer body receives the scheduler's task");

        AuroraBridge.runBukkitTask(task, runnable::incrementAndGet, null, true);
        assertEquals(1, task.selfRuns.get(), "CraftFuture runs itself: its run() completes the future");
        assertEquals(1, runnable.get());

        AuroraBridge.runBukkitTask(task, null, null, false);
        assertEquals(2, task.selfRuns.get(), "no body: the task runs itself");
    }

    @Test
    void globalCallbackHooksOnlyReactToTheAdaptersCallback() {
        final AtomicInteger cancelled = new AtomicInteger();
        final Plugin legacy = plugin("Legacy");
        assertTrue(this.bridge.admit("Legacy"));
        final Object[] callback = new Object[1];
        final io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduled =
            FoliaSchedulerCompatibility.schedule(legacy, t -> {}, -1, c -> {
            callback[0] = c;
            return new SchedulerTask(11);
        });
        assertTrue(FoliaSchedulerCompatibility.isGlobalCallback(callback[0]));
        assertFalse(FoliaSchedulerCompatibility.isGlobalCallback((Runnable)cancelled::incrementAndGet));
        assertFalse(FoliaSchedulerCompatibility.isGlobalCallback(null));

        FoliaSchedulerCompatibility.bridgeCancelled((Runnable)cancelled::incrementAndGet);
        FoliaSchedulerCompatibility.bridgeCancelled(null);
        assertEquals(0, cancelled.get(), "any other callback: no-op");

        FoliaSchedulerCompatibility.bridgeCancelled(callback[0]);
        assertEquals(io.papermc.paper.threadedregions.scheduler.ScheduledTask.ExecutionState.CANCELLED,
            scheduled.getExecutionState(), "the adapter's ScheduledTask learns the bridge cancelled it");
    }
}
