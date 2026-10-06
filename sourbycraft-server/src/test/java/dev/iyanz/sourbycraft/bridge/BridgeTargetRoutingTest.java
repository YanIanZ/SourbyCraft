package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute;
import dev.iyanz.sourbycraft.execution.region.RegionAnchor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Source regression cases for V36 (explicit region target) and V39 (explicit entity target). The
 * entity is an opaque {@code Object}: {@link BridgeRuntime} never touches it, only the executor does,
 * so no Bukkit {@code Entity} is needed here.
 */
class BridgeTargetRoutingTest {

    @Test
    void v36TargetBeatsBothCallerAndGlobalFallback() {
        for (final SyncRoute setting : SyncRoute.values()) {
            final var executor = new BridgeRuntimeTest.FakeExecutor();
            final RegionAnchor target = new RegionAnchor(new Object(), -3, 9);
            final RegionAnchor unrelatedCaller = new RegionAnchor(new Object(), 80, 80);
            final AtomicInteger callerLookups = new AtomicInteger();
            final AtomicInteger calls = new AtomicInteger();
            final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, executor,
                new BridgeTelemetry(), (message, failure) -> {}, () -> {
                    callerLookups.incrementAndGet();
                    return unrelatedCaller;
                }, () -> setting);
            assertTrue(bridge.admit("Legacy"));
            final var task = new BridgeRuntimeTest.FakeTask(1, true, -1, calls::incrementAndGet) {
                @Override public RegionAnchor targetRegion() { return target; }
            };
            assertTrue(bridge.submit("Legacy", null, task, 7));
            assertEquals("region", executor.jobs.getFirst().lane());
            assertSame(target, executor.anchors.getFirst());
            assertEquals(0, callerLookups.get());
            assertEquals(0, calls.get());
            assertEquals(7, executor.jobs.getFirst().delay());
            executor.fire(0);
            assertEquals(1, calls.get());
            assertEquals(0, bridge.pending("Legacy"));
        }
    }

    @Test
    void v36GlobalAndAsyncNeverResolveTargetMetadata() {
        final var executor = new BridgeRuntimeTest.FakeExecutor();
        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, executor,
            new BridgeTelemetry(), (message, failure) -> {}, () -> {
                throw new AssertionError("global/async task must not inspect caller region");
            }, () -> SyncRoute.CALLER_REGION);
        assertTrue(bridge.admit("Legacy"));
        final var global = new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {}) {
            @Override public boolean global() { return true; }
            @Override public RegionAnchor targetRegion() { throw new AssertionError("global owner lookup"); }
        };
        final var async = new BridgeRuntimeTest.FakeTask(2, false, -1, () -> {}) {
            @Override public RegionAnchor targetRegion() { throw new AssertionError("async owner lookup"); }
        };
        assertTrue(bridge.submit("Legacy", null, global, 0));
        assertTrue(bridge.submit("Legacy", null, async, 0));
        assertEquals("global", executor.jobs.get(0).lane());
        assertEquals("async", executor.jobs.get(1).lane());
        assertTrue(executor.anchors.isEmpty());
    }

    @Test
    void v36ResolutionFailureRejectsBeforeAdmissionWithoutQuarantine() {
        final var executor = new BridgeRuntimeTest.FakeExecutor();
        final BridgeTelemetry telemetry = new BridgeTelemetry();
        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 1, executor,
            telemetry, (message, failure) -> {});
        assertTrue(bridge.admit("Legacy"));
        assertTrue(bridge.admit("Other"));
        final var task = new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {
            throw new AssertionError("refused callback ran");
        }) {
            @Override public RegionAnchor targetRegion() {
                throw new IllegalArgumentException("unsupported callback fingerprint");
            }
        };
        assertFalse(bridge.submit("Legacy", null, task, 0));
        assertTrue(task.cancelled());
        assertFalse(bridge.knows(task.id()));
        assertEquals(0, bridge.pending("Legacy"));
        assertFalse(bridge.quarantined("Legacy"));
        assertTrue(executor.jobs.isEmpty());
        assertEquals(0, telemetry.stats("Legacy").fatalViolations());
        assertEquals(1, telemetry.stats("Legacy").rejectedOperations());
        assertTrue(bridge.submit("Other", null, new BridgeRuntimeTest.FakeTask(2, true, -1, () -> {}), 0));
    }

    private static BridgeRuntime entityBridge(final BridgeRuntimeTest.FakeExecutor executor,
                                              final BridgeTelemetry telemetry) {
        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 1, executor, telemetry,
            (message, failure) -> {}, () -> {
                throw new AssertionError("entity-targeted task must not inspect caller region");
            }, () -> SyncRoute.CALLER_REGION);
        assertTrue(bridge.admit("Legacy"));
        return bridge;
    }

    private static BridgeRuntimeTest.FakeTask entityTask(final int id, final long period, final Object entity,
                                                         final Runnable body) {
        return new BridgeRuntimeTest.FakeTask(id, true, period, body) {
            @Override public Object targetEntity() { return entity; }
            @Override public RegionAnchor targetRegion() { throw new AssertionError("entity target wins over region"); }
        };
    }

    @Test
    void v39EntityTargetTakesEntityRouteAndRunsBody() {
        final var executor = new BridgeRuntimeTest.FakeExecutor();
        final BridgeTelemetry telemetry = new BridgeTelemetry();
        final BridgeRuntime bridge = entityBridge(executor, telemetry);
        final Object entity = new Object();
        final AtomicInteger calls = new AtomicInteger();
        final var task = entityTask(1, -1, entity, calls::incrementAndGet);
        assertTrue(bridge.submit("Legacy", null, task, 4));
        assertEquals("entity", executor.jobs.getFirst().lane());
        assertSame(entity, executor.entities.getFirst());
        assertEquals(4, executor.jobs.getFirst().delay());
        assertTrue(executor.anchors.isEmpty());
        assertEquals(1, bridge.pending("Legacy"));
        executor.fire(0);
        assertEquals(1, calls.get());
        assertEquals(0, bridge.pending("Legacy"));
        assertFalse(bridge.knows(1));
        final var stats = telemetry.stats("Legacy");
        assertEquals(1, stats.ownerHandoffs());
        assertEquals(1, stats.bodyTimes().region().count());
        assertEquals(0, stats.bodyTimes().global().count());
    }

    @Test
    void v39RetiredEntityCancelsTaskFreesIndexesAndIsRejectedNotViolation() {
        final var executor = new BridgeRuntimeTest.FakeExecutor();
        final BridgeTelemetry telemetry = new BridgeTelemetry();
        final BridgeRuntime bridge = entityBridge(executor, telemetry);
        final AtomicInteger calls = new AtomicInteger();
        final var task = entityTask(1, 5, new Object(), calls::incrementAndGet);
        assertTrue(bridge.submit("Legacy", null, task, 1));
        assertEquals(1, bridge.pending("Legacy"));
        executor.retire(0);
        assertTrue(task.cancelled());
        assertTrue(executor.jobs.getFirst().cancelled().get());
        assertFalse(bridge.knows(1));
        assertEquals(0, bridge.pending("Legacy"));
        executor.fire(0);
        assertEquals(0, calls.get());
        final var stats = telemetry.stats("Legacy");
        assertEquals(1, stats.rejectedOperations());
        assertEquals(0, stats.fatalViolations());
        assertFalse(stats.quarantined());
        assertTrue(stats.lastFailure().contains("retired"), stats.lastFailure());
        // A second retirement notice for the same task is a no-op.
        executor.retire(0);
        assertEquals(1, telemetry.stats("Legacy").rejectedOperations());
        // Quarantine-after is 1: had retirement counted as a violation, this would be refused.
        assertTrue(bridge.submit("Legacy", null, entityTask(2, -1, new Object(), () -> {}), 0));
    }

    @Test
    void v39EntityRemovedBeforeSchedulingIsRejectedWithoutRunning() {
        final var executor = new BridgeRuntimeTest.FakeExecutor();
        executor.entityAlreadyRemoved = true;
        final BridgeTelemetry telemetry = new BridgeTelemetry();
        final BridgeRuntime bridge = entityBridge(executor, telemetry);
        final var task = entityTask(1, -1, new Object(), () -> {
            throw new AssertionError("retired task ran");
        });
        assertFalse(bridge.submit("Legacy", null, task, 0));
        assertTrue(task.cancelled());
        assertFalse(bridge.knows(1));
        assertEquals(0, bridge.pending("Legacy"));
        assertEquals(1, telemetry.stats("Legacy").rejectedOperations());
        assertEquals(0, telemetry.stats("Legacy").fatalViolations());
    }

    @Test
    void v39GlobalAndAsyncNeverResolveEntityTarget() {
        final var executor = new BridgeRuntimeTest.FakeExecutor();
        final BridgeRuntime bridge = entityBridge(executor, new BridgeTelemetry());
        final var global = new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {}) {
            @Override public boolean global() { return true; }
            @Override public Object targetEntity() { throw new AssertionError("global entity lookup"); }
        };
        final var async = new BridgeRuntimeTest.FakeTask(2, false, -1, () -> {}) {
            @Override public Object targetEntity() { throw new AssertionError("async entity lookup"); }
        };
        assertTrue(bridge.submit("Legacy", null, global, 0));
        assertTrue(bridge.submit("Legacy", null, async, 0));
        assertEquals("global", executor.jobs.get(0).lane());
        assertEquals("async", executor.jobs.get(1).lane());
        assertTrue(executor.entities.isEmpty());
    }

    @Test
    void v39CancelBeforeExecutionNeverRunsAndLateRetirementIsIgnored() {
        final var executor = new BridgeRuntimeTest.FakeExecutor();
        final BridgeTelemetry telemetry = new BridgeTelemetry();
        final BridgeRuntime bridge = entityBridge(executor, telemetry);
        final AtomicInteger calls = new AtomicInteger();
        final var task = entityTask(1, -1, new Object(), calls::incrementAndGet);
        assertTrue(bridge.submit("Legacy", null, task, 10));
        assertTrue(bridge.cancel(1));
        assertTrue(task.cancelled());
        assertTrue(executor.jobs.getFirst().cancelled().get());
        assertEquals(0, bridge.pending("Legacy"));
        executor.fire(0);
        executor.retire(0);
        assertEquals(0, calls.get());
        assertEquals(0, telemetry.stats("Legacy").rejectedOperations());
    }

    @Test
    void v39GlobalCancelTasksLeavesEntityTasks() {
        final var executor = new BridgeRuntimeTest.FakeExecutor();
        final BridgeRuntime bridge = entityBridge(executor, new BridgeTelemetry());
        assertTrue(bridge.submit("Legacy", null, entityTask(1, -1, new Object(), () -> {}), 1));
        assertEquals(0, bridge.cancelGlobal("Legacy"));
        assertTrue(bridge.knows(1));
        assertEquals(1, bridge.disable("Legacy"));
        assertTrue(executor.jobs.getFirst().cancelled().get());
    }
}
