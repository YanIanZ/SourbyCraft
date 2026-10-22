package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.scheduler.RegionTask;
import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute;
import dev.iyanz.sourbycraft.execution.region.RegionAnchor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** Regressions and boundary checks from the 2026-10-07 review of the uncommitted bridge work. */
class BridgeReviewTest {

    private final BridgeRuntimeTest.FakeExecutor executor = new BridgeRuntimeTest.FakeExecutor();
    private final List<String> warnings = new ArrayList<>();

    private BridgeRuntime runtime(final int pendingLimit, final int asyncLimit) {
        return new BridgeRuntime(BridgeMode.SAFE, () -> 3, this.executor, new BridgeTelemetry(),
            (message, thrown) -> this.warnings.add(message), () -> null, () -> SyncRoute.GLOBAL,
            () -> pendingLimit, () -> asyncLimit);
    }

    /** Disable closed admission for good: a plugin reloaded under the same name could never schedule. */
    @Test
    void aPluginLoadedAgainAfterDisableCanScheduleAgain() {
        final BridgeRuntime bridge = runtime(0, 0);
        assertTrue(bridge.admit("Legacy"));
        assertTrue(bridge.submit("Legacy", null, new BridgeRuntimeTest.FakeTask(1, true, 20, () -> {}), 0));
        assertEquals(1, bridge.disable("Legacy"));
        final var refused = new BridgeRuntimeTest.FakeTask(2, true, -1, () -> {});
        assertFalse(bridge.submit("Legacy", null, refused, 0), "admission stays closed until the plugin loads again");
        assertTrue(refused.cancelled());

        assertTrue(bridge.admit("Legacy"), "the plugin loader admits the reloaded instance");
        final var task = new BridgeRuntimeTest.FakeTask(3, true, -1, () -> {});
        assertTrue(bridge.submit("Legacy", null, task, 0));
        this.executor.fire(this.executor.jobs.size() - 1);
        assertEquals(1, task.runs.get());
        assertEquals(0, bridge.pending("Legacy"));
        assertFalse(bridge.quarantined("Legacy"));
    }

    /** The same instance enabled again after disable: the loader never asks admit() again. */
    @Test
    void theSameInstanceEnabledAgainAfterDisableCanScheduleAgain() {
        final BridgeRuntime bridge = runtime(0, 0);
        assertTrue(bridge.admit("Legacy"));
        assertEquals(0, bridge.disable("Legacy"));
        final var refused = new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {});
        assertFalse(bridge.submit("Legacy", null, refused, 0), "disable closes admission");

        assertTrue(bridge.reopen("Legacy"), "enable re-opens admission for a bridged plugin");
        final var task = new BridgeRuntimeTest.FakeTask(2, true, -1, () -> {});
        assertTrue(bridge.submit("Legacy", null, task, 0));
        this.executor.fire(this.executor.jobs.size() - 1);
        assertEquals(1, task.runs.get());
        assertEquals(0, bridge.pending("Legacy"));
    }

    @Test
    void reopenNeverAdmitsAPluginTheBridgeDidNotAdmit() {
        final BridgeRuntime bridge = runtime(0, 0);
        assertFalse(bridge.reopen("Native"));
        assertFalse(bridge.reopen(null));
        assertFalse(bridge.isBridged("Native"), "reopen must not create an admission");
        final var task = new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {});
        assertFalse(bridge.submit("Native", null, task, 0));
        assertTrue(task.cancelled());
        // OFF mode admits nothing, so nothing can be reopened either.
        final BridgeRuntime off = new BridgeRuntime(BridgeMode.OFF, () -> 3, this.executor, new BridgeTelemetry(),
            (message, thrown) -> {});
        assertFalse(off.admit("Legacy"));
        assertFalse(off.reopen("Legacy"));
    }

    @Test
    void reopenDoesNotLiftQuarantine() {
        final BridgeRuntime bridge = runtime(0, 0);
        assertTrue(bridge.admit("Legacy"));
        assertTrue(bridge.telemetry().quarantine("Legacy"));
        assertTrue(bridge.quarantined("Legacy"));
        bridge.disable("Legacy");
        assertTrue(bridge.reopen("Legacy"));
        final var task = new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {});
        assertFalse(bridge.submit("Legacy", null, task, 0), "a quarantined plugin stays rejected after re-enable");
    }

    @Test
    void theEnableHookIsANoOpWithoutABridgeOrPlugin() {
        AuroraBridge.onPluginEnabled(null);
    }

    @Test
    void pendingLimitAdmitsExactlyTheLimitAndCompletionFreesASlot() {
        final BridgeRuntime bridge = runtime(2, 0);
        bridge.admit("A");
        bridge.admit("B");
        assertTrue(bridge.submit("A", null, new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {}), 0));
        assertTrue(bridge.submit("A", null, new BridgeRuntimeTest.FakeTask(2, false, 20, () -> {}), 0));
        final var third = new BridgeRuntimeTest.FakeTask(3, true, -1, () -> {});
        assertFalse(bridge.submit("A", null, third, 0));
        assertTrue(third.cancelled());
        assertFalse(bridge.knows(3));
        assertEquals(2, this.executor.jobs.size(), "a rejected task never reaches the executor");
        assertTrue(bridge.submit("B", null, new BridgeRuntimeTest.FakeTask(4, true, -1, () -> {}), 0),
            "limits are per plugin");
        this.executor.fire(0);
        assertTrue(bridge.submit("A", null, new BridgeRuntimeTest.FakeTask(5, true, -1, () -> {}), 0));
        final var stats = bridge.telemetry().stats("A");
        assertEquals(1, stats.rejectedOperations());
        assertEquals(0, stats.fatalViolations());
        assertFalse(bridge.quarantined("A"));
    }

    @Test
    void zeroLimitsAreUnlimited() {
        final BridgeRuntime bridge = runtime(0, 0);
        bridge.admit("A");
        for (int id = 1; id <= 500; id++) {
            assertTrue(bridge.submit("A", null, new BridgeRuntimeTest.FakeTask(id, false, -1, () -> {}), 0));
        }
        assertEquals(500, bridge.pending("A"));
    }

    @Test
    void runningAsyncLimitRejectsTheOverlapOnlyAndReleasesTheSlot() {
        final BridgeRuntime bridge = runtime(0, 1);
        bridge.admit("A");
        final AtomicInteger bodies = new AtomicInteger();
        final AtomicInteger runningInside = new AtomicInteger(-1);
        final var timer = new BridgeRuntimeTest.FakeTask(7, false, 1, () -> {
            if (bodies.incrementAndGet() == 1) {
                this.executor.fire(0);
                runningInside.set(bridge.runningAsync("A"));
            }
        });
        assertTrue(bridge.submit("A", null, timer, 0));
        this.executor.fire(0);
        assertEquals(1, bodies.get(), "the overlapping invocation is refused, not run");
        assertEquals(1, runningInside.get());
        assertEquals(0, bridge.runningAsync("A"), "the running slot is released in finally");
        assertTrue(bridge.activeWorkers().isEmpty());
        assertTrue(timer.cancelled(), "a rejected repeater loses its future invocations");
        assertFalse(bridge.knows(7));
        assertEquals(0, bridge.telemetry().stats("A").fatalViolations());
        assertEquals(1, bridge.telemetry().stats("A").rejectedOperations());
        // Sync work never consumes an async slot.
        assertTrue(bridge.submit("A", null, new BridgeRuntimeTest.FakeTask(8, true, -1, () -> {}), 0));
    }

    @Test
    void aCancelledAsyncBodyStaysVisibleUntilItFinishes() {
        final BridgeRuntime bridge = runtime(0, 0);
        bridge.admit("A");
        final List<Boolean> observed = new ArrayList<>();
        final var task = new BridgeRuntimeTest.FakeTask(9, false, -1, () -> {
            assertTrue(bridge.cancel(9));
            observed.add(bridge.knows(9));
            observed.add(bridge.running(9));
            observed.add(bridge.activeWorkers().size() == 1
                && bridge.activeWorkers().getFirst().thread() == Thread.currentThread());
            observed.add(bridge.runningAsync("A") == 1);
        });
        bridge.submit("A", null, task, 0);
        this.executor.fire(0);
        assertEquals(List.of(false, true, true, true), observed);
        assertFalse(bridge.running(9));
        assertTrue(bridge.activeWorkers().isEmpty());
        assertEquals(0, bridge.runningAsync("A"));
    }

    @Test
    void regionTaskSnapshotsFlooredChunkCoordinates() {
        final World world = world();
        final Location location = new Location(world, -0.5, 64, -17.0);
        final RegionTask task = RegionTask.at(location, () -> {});
        location.setX(1000);
        location.setZ(1000);
        final RegionAnchor anchor = AuroraBridge.targetRegion(plugin("Legacy", null), task);
        assertSame(world, anchor.world());
        assertEquals(-1, anchor.chunkX());
        assertEquals(-2, anchor.chunkZ());
        assertEquals(new RegionAnchor(world, 0, -1),
            AuroraBridge.targetRegion(plugin("Legacy", null), RegionTask.at(new Location(world, 15.99, 0, -16.0), () -> {})));
        assertThrows(NullPointerException.class, () -> RegionTask.at(new Location(null, 0, 0, 0), () -> {}));
    }

    /** A plugin whose meta is missing must be refused like any unaudited callback, not by an NPE. */
    @Test
    void superiorCallbackWithoutMetaIsRefusedBeforeAdmissionWithoutViolation() {
        final Runnable callback = new com.bgsoftware.superiorskyblock.island.SpawnIsland().callback();
        final Plugin noMeta = plugin("SuperiorSkyblock2", null);
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> SuperiorSpawnTaskOwner.resolve(noMeta, callback));
        assertTrue(refused.getMessage().contains("version"), refused.getMessage());
        // The stand-in's bytes are not the audited class: the right version is still refused.
        assertThrows(IllegalArgumentException.class,
            () -> SuperiorSpawnTaskOwner.resolve(plugin("SuperiorSkyblock2", "2026.3"), callback));
        assertNull(SuperiorSpawnTaskOwner.resolve(plugin("Other", null), callback), "other plugins are not inspected");

        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 1, this.executor, new BridgeTelemetry(),
            (message, thrown) -> this.warnings.add(message));
        bridge.admit("SuperiorSkyblock2");
        final var task = new BridgeRuntimeTest.FakeTask(11, true, -1, callback) {
            @Override public RegionAnchor targetRegion() { return AuroraBridge.targetRegion(noMeta, callback); }
        };
        assertFalse(bridge.submit("SuperiorSkyblock2", noMeta, task, 0));
        assertTrue(task.cancelled());
        assertFalse(bridge.knows(11));
        assertTrue(this.executor.jobs.isEmpty());
        assertEquals(0, bridge.telemetry().stats("SuperiorSkyblock2").fatalViolations());
        assertFalse(bridge.quarantined("SuperiorSkyblock2"));
    }

    private static World world() {
        return (World)Proxy.newProxyInstance(BridgeReviewTest.class.getClassLoader(), new Class<?>[] {World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "TestWorld";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static Plugin plugin(final String name, final String version) {
        final Object meta = version == null ? null : Proxy.newProxyInstance(BridgeReviewTest.class.getClassLoader(),
            new Class<?>[] {io.papermc.paper.plugin.configuration.PluginMeta.class},
            (proxy, method, args) -> method.getName().equals("getVersion") ? version : null);
        return (Plugin)Proxy.newProxyInstance(BridgeReviewTest.class.getClassLoader(), new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "getPluginMeta" -> meta;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> name;
                default -> null;
            });
    }
}
