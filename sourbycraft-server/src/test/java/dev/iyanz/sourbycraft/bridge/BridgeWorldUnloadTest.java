package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute;
import dev.iyanz.sourbycraft.execution.region.RegionAnchor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * World unload (WorldUnloadDrain) cancels a world's region scheduler tasks without running them
 * and without telling the bridge. Before {@link BridgeRuntime#worldUnloaded} a bridged task
 * anchored there stayed indexed for good: pending, queued and never cancelled in the plugin's view.
 */
class BridgeWorldUnloadTest {

    private final BridgeRuntimeTest.FakeExecutor executor = new BridgeRuntimeTest.FakeExecutor();
    private final List<String> warnings = new ArrayList<>();
    private final Object unloading = new Object();
    private final Object staying = new Object();

    private static final class TargetedTask extends BridgeRuntimeTest.FakeTask {
        private final RegionAnchor target;

        TargetedTask(final int id, final long period, final RegionAnchor target, final Runnable body) {
            super(id, true, period, body);
            this.target = target;
        }

        @Override public RegionAnchor targetRegion() { return this.target; }
    }

    private BridgeRuntime runtime() {
        return new BridgeRuntime(BridgeMode.SAFE, () -> 3, this.executor, new BridgeTelemetry(),
            (message, thrown) -> this.warnings.add(message), () -> new RegionAnchor(this.unloading, 0, 0),
            () -> SyncRoute.CALLER_REGION);
    }

    @Test
    void unloadReleasesOnlyTheRegionTasksAnchoredInThatWorld() {
        final BridgeRuntime bridge = runtime();
        assertTrue(bridge.admit("Legacy"));
        assertTrue(bridge.admit("Other"));
        final var callerOneShot = new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {});
        final var callerRepeater = new BridgeRuntimeTest.FakeTask(2, true, 20, () -> {});
        final var otherWorld = new TargetedTask(3, -1, new RegionAnchor(this.staying, 4, 4), () -> {});
        final var async = new BridgeRuntimeTest.FakeTask(4, false, 20, () -> {});
        final var otherPlugin = new TargetedTask(5, 20, new RegionAnchor(this.unloading, 9, 9), () -> {});
        assertTrue(bridge.submit("Legacy", null, callerOneShot, 5));
        assertTrue(bridge.submit("Legacy", null, callerRepeater, 5));
        assertTrue(bridge.submit("Legacy", null, otherWorld, 5));
        assertTrue(bridge.submit("Legacy", null, async, 5));
        assertTrue(bridge.submit("Other", null, otherPlugin, 5));
        assertEquals(List.of("region", "region", "region", "async", "region"),
            this.executor.jobs.stream().map(BridgeRuntimeTest.FakeExecutor.Job::lane).toList());

        assertEquals(3, bridge.worldUnloaded(this.unloading));

        assertTrue(callerOneShot.cancelled());
        assertTrue(callerRepeater.cancelled());
        assertTrue(otherPlugin.cancelled());
        assertFalse(otherWorld.cancelled());
        assertFalse(async.cancelled());
        assertFalse(bridge.knows(1));
        assertFalse(bridge.knows(2));
        assertFalse(bridge.knows(5));
        assertEquals(2, bridge.pending("Legacy"));
        assertEquals(0, bridge.pending("Other"));
        assertTrue(this.executor.jobs.get(0).cancelled().get(), "the native handle is cancelled too");
        assertTrue(this.executor.jobs.get(4).cancelled().get());

        final BridgeTelemetry.PluginStats stats = bridge.telemetry().stats("Legacy");
        assertEquals(0, stats.fatalViolations(), "an unload is not the plugin's violation");
        assertFalse(stats.quarantined());
        assertEquals(2, stats.rejectedOperations());
        assertTrue(stats.lastFailure().contains("unloaded"), stats.lastFailure());
        assertTrue(this.warnings.isEmpty(), "released quietly, like a retired entity task: " + this.warnings);

        assertEquals(0, bridge.worldUnloaded(this.unloading), "idempotent");
        assertEquals(0, bridge.worldUnloaded(null));
        assertTrue(bridge.submit("Legacy", null, new BridgeRuntimeTest.FakeTask(6, true, -1, () -> {}), 1),
            "admission stays open");
    }

    @Test
    void withoutABridgeRuntimeUnloadDoesNothingAndStartsNothing() throws Exception {
        try (AutoCloseable ignored = AuroraBridge.resetRuntimeForTesting()) {
            assertEquals(0, AuroraBridge.worldUnloaded(new Object()));
            assertEquals(null, AuroraBridge.modeIfStarted());
        }
    }
}
