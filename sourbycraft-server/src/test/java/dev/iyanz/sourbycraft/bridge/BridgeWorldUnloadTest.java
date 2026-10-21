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

    /**
     * PR #18 review: the unload's release is a one-time sweep. A task anchored in the world and submitted
     * after it (an async thread naming a location there) was indexed for good: queued, counted against
     * the pending limit, and holding the unloaded world. A world closed from the start of its unload
     * refuses such tasks instead.
     */
    @Test
    void aTaskAnchoredInAnUnloadingWorldIsRefusedNotIndexed() {
        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, this.executor, new BridgeTelemetry(),
            (message, thrown) -> this.warnings.add(message), () -> null, () -> SyncRoute.CALLER_REGION, () -> 1, () -> 0);
        bridge.worldClosedCheck(world -> world == this.unloading);
        assertTrue(bridge.admit("Legacy"));
        final var late = new TargetedTask(1, -1, new RegionAnchor(this.unloading, 0, 0), () -> {});
        final var lateTimer = new TargetedTask(2, 20, new RegionAnchor(this.unloading, 1, 1), () -> {});

        assertFalse(bridge.submit("Legacy", null, late, 5));
        assertFalse(bridge.submit("Legacy", null, lateTimer, 5));

        assertTrue(late.cancelled());
        assertTrue(lateTimer.cancelled());
        assertFalse(bridge.knows(1));
        assertFalse(bridge.knows(2));
        assertTrue(this.executor.jobs.isEmpty(), "never handed to the region scheduler of a closed world");
        assertEquals(0, bridge.pending("Legacy"), "no pending slot is taken (limit 1)");
        final BridgeTelemetry.PluginStats stats = bridge.telemetry().stats("Legacy");
        assertEquals(2, stats.rejectedOperations());
        assertEquals(0, stats.fatalViolations());
        assertTrue(stats.lastFailure().contains("unloading"), stats.lastFailure());
        assertTrue(this.warnings.isEmpty(), "expected during an unload, not logged per task: " + this.warnings);

        final var elsewhere = new TargetedTask(3, -1, new RegionAnchor(this.staying, 0, 0), () -> {});
        assertTrue(bridge.submit("Legacy", null, elsewhere, 5), "other worlds and the limit slot are unaffected");
    }

    /** The unload begins between the first check and indexing: the re-check after indexing refuses it. */
    @Test
    void anUnloadBeginningWhileTheTaskIsIndexedStillReleasesIt() {
        final BridgeRuntime bridge = runtime();
        final java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        bridge.worldClosedCheck(world -> world == this.unloading && checks.incrementAndGet() > 1);
        assertTrue(bridge.admit("Legacy"));
        final var racing = new TargetedTask(1, -1, new RegionAnchor(this.unloading, 0, 0), () -> {});

        assertFalse(bridge.submit("Legacy", null, racing, 5));

        assertEquals(2, checks.get(), "checked before and after indexing");
        assertTrue(racing.cancelled());
        assertFalse(bridge.knows(1));
        assertEquals(0, bridge.pending("Legacy"));
        assertTrue(this.executor.jobs.isEmpty());
        assertEquals(0, bridge.worldUnloaded(this.unloading), "nothing left for the sweep");
    }

    @Test
    void aThrowingWorldCheckDoesNotRefuseTheTask() {
        final BridgeRuntime bridge = runtime();
        bridge.worldClosedCheck(world -> { throw new IllegalStateException("no handle"); });
        assertTrue(bridge.admit("Legacy"));
        assertTrue(bridge.submit("Legacy", null, new TargetedTask(1, -1, new RegionAnchor(this.staying, 0, 0), () -> {}), 5));
        assertTrue(bridge.knows(1));
    }

    @Test
    void theProductionCheckIgnoresWhatIsNotACraftWorld() {
        assertFalse(AuroraBridge.worldUnloading(new Object()));
        assertFalse(AuroraBridge.worldUnloading(null));
    }
}
