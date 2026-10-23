package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute;
import dev.iyanz.sourbycraft.execution.region.RegionAnchor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The inferred entity anchor ({@link SchedulingContext}) and its place in the owner precedence:
 * explicit entity > explicit region > context player > caller region > global. Players are opaque
 * objects here; only the executor sees them.
 */
class BridgeContextRoutingTest {

    private final BridgeRuntimeTest.FakeExecutor executor = new BridgeRuntimeTest.FakeExecutor();
    private final BridgeTelemetry telemetry = new BridgeTelemetry();
    private final List<String> warnings = new ArrayList<>();
    private final RegionAnchor caller = new RegionAnchor(new Object(), 4, 4);
    private boolean callerTicking = true;

    @AfterEach
    void contextIsCleanAfterEveryTest() {
        assertNull(SchedulingContext.current(), "a test leaked its scheduling context");
    }

    private BridgeRuntime bridge(final SyncRoute route) {
        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, this.executor, this.telemetry,
            (message, failure) -> this.warnings.add(message), () -> this.callerTicking ? this.caller : null, () -> route);
        assertTrue(bridge.admit("Legacy"));
        return bridge;
    }

    private static BridgeRuntimeTest.FakeTask sync(final int id, final Runnable body) {
        return new BridgeRuntimeTest.FakeTask(id, true, -1, body);
    }

    private static BridgeRuntimeTest.FakeTask async(final int id, final Runnable body) {
        return new BridgeRuntimeTest.FakeTask(id, false, -1, body);
    }

    // ----------------------------------------------------------------------------------- precedence

    @Test
    void contextPlayerAnchorsAnUntargetedSyncTaskToTheEntityOwner() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        final Object player = new Object();
        final AtomicInteger runs = new AtomicInteger();
        try (var scope = SchedulingContext.enter(player)) {
            assertTrue(bridge.submit("Legacy", null, sync(1, runs::incrementAndGet), 1));
        }
        assertEquals("entity", this.executor.jobs.getFirst().lane());
        assertSame(player, this.executor.entities.getFirst());
        assertTrue(this.executor.anchors.isEmpty(), "the caller region is not consulted when a player is known");
        this.executor.fire(0);
        assertEquals(1, runs.get());
        assertEquals(1, this.telemetry.stats("Legacy").ownerHandoffs());
        assertEquals(0, this.telemetry.stats("Legacy").rejectedOperations());
    }

    @Test
    void contextPlayerBeatsCallerRegionAndGlobalFallbackUnderEverySyncRoute() {
        for (final SyncRoute setting : SyncRoute.values()) {
            final var executor = new BridgeRuntimeTest.FakeExecutor();
            final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, executor, new BridgeTelemetry(),
                (message, failure) -> {}, () -> this.caller, () -> setting);
            assertTrue(bridge.admit("Legacy"));
            final Object player = new Object();
            try (var scope = SchedulingContext.enter(player)) {
                assertTrue(bridge.submit("Legacy", null, sync(1, () -> {}), 0));
            }
            assertEquals("entity", executor.jobs.getFirst().lane(), setting.toString());
            assertSame(player, executor.entities.getFirst());
        }
    }

    @Test
    void explicitEntityAndRegionTargetsBeatTheContextPlayer() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        final Object contextPlayer = new Object();
        final Object named = new Object();
        final RegionAnchor target = new RegionAnchor(new Object(), -1, 7);
        try (var scope = SchedulingContext.enter(contextPlayer)) {
            assertTrue(bridge.submit("Legacy", null, new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {}) {
                @Override public Object targetEntity() { return named; }
            }, 0));
            assertTrue(bridge.submit("Legacy", null, new BridgeRuntimeTest.FakeTask(2, true, -1, () -> {}) {
                @Override public RegionAnchor targetRegion() { return target; }
            }, 0));
        }
        assertEquals("entity", this.executor.jobs.get(0).lane());
        assertSame(named, this.executor.entities.getFirst());
        assertEquals("region", this.executor.jobs.get(1).lane());
        assertSame(target, this.executor.anchors.getFirst());
        assertEquals(1, this.executor.entities.size(), "the RegionTask was not re-anchored to the context player");
    }

    @Test
    void withoutContextTheCallerRegionAndGlobalFallbackAreUnchanged() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        assertTrue(bridge.submit("Legacy", null, sync(1, () -> {}), 0));
        assertEquals("region", this.executor.jobs.get(0).lane());
        assertSame(this.caller, this.executor.anchors.getFirst());
        this.callerTicking = false;
        assertTrue(bridge.submit("Legacy", null, sync(2, () -> {}), 0));
        assertEquals("global", this.executor.jobs.get(1).lane());
        assertTrue(this.executor.entities.isEmpty());
    }

    @Test
    void explicitGlobalRequestsIgnoreTheContext() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        try (var scope = SchedulingContext.enter(new Object())) {
            assertTrue(bridge.submit("Legacy", null, new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {}) {
                @Override public boolean global() { return true; }
                @Override public Object targetEntity() { throw new AssertionError("global owner lookup"); }
            }, 0));
        }
        assertEquals("global", this.executor.jobs.getFirst().lane());
        assertTrue(this.executor.entities.isEmpty());
    }

    // ----------------------------------------------------------------------------- continuations

    /** DeluxeMenus: player command -> runTaskAsynchronously (build items) -> runTask (openInventory). */
    @Test
    void asyncTaskCarriesTheContextIntoTheSyncTaskItsBodySchedules() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        final Object player = new Object();
        final AtomicReference<Object> seenInsideAsync = new AtomicReference<>();
        final AtomicInteger opened = new AtomicInteger();
        try (var scope = SchedulingContext.enter(player)) {
            assertTrue(bridge.submit("Legacy", null, async(1, () -> {
                seenInsideAsync.set(SchedulingContext.current());
                // The async thread ticks no region: without the carried context this would be global.
                this.callerTicking = false;
                assertTrue(bridge.submit("Legacy", null, sync(2, opened::incrementAndGet), 0));
            }), 0));
        }
        assertEquals("async", this.executor.jobs.get(0).lane());
        this.executor.fire(0);
        assertSame(player, seenInsideAsync.get());
        assertEquals("entity", this.executor.jobs.get(1).lane());
        assertSame(player, this.executor.entities.getFirst());
        this.executor.fire(1);
        assertEquals(1, opened.get());
        assertNull(SchedulingContext.current(), "the body scope restored the thread");
    }

    /**
     * EssentialsX + EssentialsXSpawn: PlayerJoinEvent (player context) -> runTaskAsynchronously ->
     * callEvent(AsyncUserDataLoadEvent) (names no player: the frame's player is kept) -> listener
     * schedules runTaskLater(NewPlayerTeleport) under the Essentials plugin.
     */
    @Test
    void anEventWithoutAPlayerInsideACarriedFrameKeepsThatFramesPlayer() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        final Object joining = new Object();
        try (var scope = SchedulingContext.enter(joining)) {
            assertTrue(bridge.submit("Legacy", null, async(1, () -> {
                try (var nested = SchedulingContext.enter(null)) {
                    this.callerTicking = false;
                    assertTrue(bridge.submit("Legacy", null, sync(2, () -> {}), 1));
                }
            }), 0));
        }
        this.executor.fire(0);
        assertEquals("entity", this.executor.jobs.get(1).lane());
        assertSame(joining, this.executor.entities.getFirst());
        assertEquals(1, this.executor.jobs.get(1).delay());
    }

    @Test
    void anEntityAnchoredSyncBodyPublishesItsPlayerSoTheChainFollowsThePlayer() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        final Object player = new Object();
        try (var scope = SchedulingContext.enter(player)) {
            assertTrue(bridge.submit("Legacy", null, sync(1, () -> {
                // teleport, then a tick later send the "welcome" menu: the second hop names nobody.
                assertTrue(bridge.submit("Legacy", null, sync(2, () -> {}), 1));
            }), 0));
        }
        this.executor.fire(0);
        assertEquals(List.of(player, player), this.executor.entities);
        assertEquals("entity", this.executor.jobs.get(1).lane());
    }

    @Test
    void regionAndGlobalBodiesPublishNoContextAndDoNotInheritTheTickThreadsFrame() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        final AtomicReference<Object> seenInGlobal = new AtomicReference<>(new Object());
        final AtomicReference<Object> seenInRegion = new AtomicReference<>(new Object());
        this.callerTicking = false;
        assertTrue(bridge.submit("Legacy", null, sync(1, () -> seenInGlobal.set(SchedulingContext.current())), 0));
        this.callerTicking = true;
        assertTrue(bridge.submit("Legacy", null, sync(2, () -> seenInRegion.set(SchedulingContext.current())), 0));
        assertEquals("global", this.executor.jobs.get(0).lane());
        assertEquals("region", this.executor.jobs.get(1).lane());
        // A stray frame on the tick thread (an event being dispatched when the scheduler ticks)
        // must not leak into a body that was not scheduled from it.
        try (var stray = SchedulingContext.enter(new Object())) {
            this.executor.fire(0);
            this.executor.fire(1);
            assertNull(seenInGlobal.get());
            assertNull(seenInRegion.get());
        }
    }

    @Test
    void explicitGlobalBodyPublishesNothingSoNestedSyncTasksStayGlobal() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        this.callerTicking = false;
        try (var scope = SchedulingContext.enter(new Object())) {
            assertTrue(bridge.submit("Legacy", null, new BridgeRuntimeTest.FakeTask(1, true, -1, () ->
                assertTrue(bridge.submit("Legacy", null, sync(2, () -> {}), 0))) {
                @Override public boolean global() { return true; }
            }, 0));
        }
        this.executor.fire(0);
        assertEquals("global", this.executor.jobs.get(1).lane());
        assertTrue(this.executor.entities.isEmpty());
    }

    // ------------------------------------------------------------------------------- player gone

    @Test
    void contextPlayerAlreadyGoneAtScheduleTimeFallsBackToTheCallerRegionNotRejected() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        this.executor.entityAlreadyRemoved = true;
        final AtomicInteger runs = new AtomicInteger();
        final var task = sync(1, runs::incrementAndGet);
        try (var scope = SchedulingContext.enter(new Object())) {
            assertTrue(bridge.submit("Legacy", null, task, 3));
        }
        assertEquals(1, this.executor.entities.size(), "the entity scheduler was asked once");
        final var fallback = this.executor.jobs.getLast();
        assertEquals("region", fallback.lane());
        assertSame(this.caller, this.executor.anchors.getFirst());
        assertEquals(3, fallback.delay());
        assertFalse(task.cancelled());
        assertEquals(1, bridge.pending("Legacy"));
        this.executor.fire(this.executor.jobs.size() - 1);
        assertEquals(1, runs.get());
        assertEquals(0, bridge.pending("Legacy"));
        final var stats = this.telemetry.stats("Legacy");
        assertEquals(0, stats.rejectedOperations());
        assertEquals(1, stats.schedulerRedirects(), "counted once, for the route actually taken");
        assertEquals(1, stats.ownerHandoffs());
        assertTrue(stats.lastFailure().contains("already gone when scheduled; routed to the caller region"), stats.lastFailure());
    }

    @Test
    void contextPlayerAlreadyGoneWithNoCallerRegionFallsBackToGlobal() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        this.executor.entityAlreadyRemoved = true;
        this.callerTicking = false;
        try (var scope = SchedulingContext.enter(new Object())) {
            assertTrue(bridge.submit("Legacy", null, sync(1, () -> {}), 0));
        }
        assertEquals("global", this.executor.jobs.getLast().lane());
        assertEquals(0, this.telemetry.stats("Legacy").rejectedOperations());
        assertEquals(0, this.telemetry.stats("Legacy").ownerHandoffs());
    }

    @Test
    void explicitEntityTaskWhoseEntityIsAlreadyGoneIsStillRejectedAsBefore() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        this.executor.entityAlreadyRemoved = true;
        final var task = new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {}) {
            @Override public Object targetEntity() { return new Object(); }
        };
        assertFalse(bridge.submit("Legacy", null, task, 0));
        assertTrue(task.cancelled());
        assertEquals(1, this.executor.entities.size());
        assertTrue(this.executor.jobs.isEmpty(), "no fallback job for an explicit target");
        assertEquals(1, this.telemetry.stats("Legacy").rejectedOperations());
    }

    @Test
    void contextPlayerLeavingAfterSchedulingRetiresTheTaskAsRejectedNotViolation() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        final AtomicInteger runs = new AtomicInteger();
        final var task = sync(1, runs::incrementAndGet);
        try (var scope = SchedulingContext.enter(new Object())) {
            assertTrue(bridge.submit("Legacy", null, task, 20));
        }
        this.executor.retire(0);
        assertTrue(task.cancelled());
        assertFalse(bridge.knows(1));
        assertEquals(0, runs.get());
        assertEquals(1, this.executor.jobs.size(), "no fallback run after the player left");
        final var stats = this.telemetry.stats("Legacy");
        assertEquals(1, stats.rejectedOperations());
        assertEquals(0, stats.fatalViolations());
        assertFalse(bridge.quarantined("Legacy"));
        assertTrue(stats.lastFailure().contains("left before it ran"), stats.lastFailure());
        assertTrue(this.warnings.isEmpty(), "retirement is not logged per task");
    }

    // ------------------------------------------------------------------------------ diagnostics

    @Test
    void anUntargetedGlobalTasksViolationCarriesTheSweepHintAndAnAnchoredOneDoesNot() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        this.callerTicking = false;
        final RuntimeException violation = new IllegalStateException(
            "Thread failed main thread check: Accessing entity state off owning region's thread");
        assertTrue(bridge.submit("Legacy", null, sync(1, () -> { throw violation; }), 0));
        this.executor.fire(0);
        assertEquals(1, this.warnings.size());
        assertTrue(this.warnings.getFirst().contains("sweeps entities or blocks across the world"), this.warnings.getFirst());
        this.warnings.clear();
        try (var scope = SchedulingContext.enter(new Object())) {
            assertTrue(bridge.submit("Legacy", null, sync(2, () -> { throw violation; }), 0));
        }
        this.executor.fire(1);
        assertEquals(1, this.warnings.size());
        assertFalse(this.warnings.getFirst().contains("sweeps"), this.warnings.getFirst());
        assertEquals(2, this.telemetry.stats("Legacy").fatalViolations());
    }

    @Test
    void sweepHintAlsoFollowsAGuardHitInAnUntargetedGlobalBody() {
        final BridgeRuntime bridge = bridge(SyncRoute.CALLER_REGION);
        this.callerTicking = false;
        assertTrue(bridge.submit("Legacy", null, sync(1, () ->
            assertTrue(BridgeRuntime.reportGuardViolation("entity state off owning region"))), 0));
        this.executor.fire(0);
        assertEquals(1, this.warnings.size());
        assertTrue(this.warnings.getFirst().contains("cannot be routed to one owner"), this.warnings.getFirst());
    }

    // --------------------------------------------------------------------------------- the scope

    @Test
    void scopesNestAndRestoreAndNullKeepsTheOuterValue() {
        final Object outer = new Object();
        final Object inner = new Object();
        assertNull(SchedulingContext.current());
        try (var a = SchedulingContext.enter(outer)) {
            assertSame(outer, SchedulingContext.current());
            try (var b = SchedulingContext.enter(null)) {
                assertSame(outer, SchedulingContext.current());
            }
            try (var c = SchedulingContext.enter(inner)) {
                assertSame(inner, SchedulingContext.current());
                try (var d = SchedulingContext.body(null)) {
                    assertNull(SchedulingContext.current());
                }
                assertSame(inner, SchedulingContext.current());
            }
            assertSame(outer, SchedulingContext.current());
        }
        assertNull(SchedulingContext.current());
    }
}
