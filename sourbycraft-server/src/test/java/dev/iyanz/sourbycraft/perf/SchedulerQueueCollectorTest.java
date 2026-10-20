package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.perf.SchedulerQueueCollector.RegionProbe;
import dev.iyanz.sourbycraft.perf.SchedulerQueueCollector.RegionState;
import dev.iyanz.sourbycraft.perf.SchedulerQueueCollector.TickProbe;
import dev.iyanz.sourbycraft.perf.SchedulerQueueCollector.WorldProbe;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.GlobalTick;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.LateTicks;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.RunnerActivity;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.Scheduler;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.WorldRegions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SchedulerQueueCollectorTest {

    private static final long NOW = 10_000_000_000L;
    private static final long MS = 1_000_000L;
    private static final long NOT_SET = SchedulerQueueCollector.DEADLINE_NOT_SET;
    private static final long NO_TICK = SchedulerQueueCollector.NO_ACTIVE_TICK;

    @Test
    void anUnavailableSourceGivesAnUnavailableSnapshotWithItsReason() {
        final FakeSource source = new FakeSource();
        source.reason = "region scheduler not started";

        final SchedulerQueueSnapshot snapshot = SchedulerQueueCollector.build(source, NOW);

        assertFalse(snapshot.available());
        assertEquals("region scheduler not started", snapshot.unavailableReason());
        assertEquals(List.of(), snapshot.worlds());
        assertEquals(0, source.worldsRead.get(), "nothing else is read once the source says it cannot be");
    }

    @Test
    void regionsAreCountedByState() {
        final WorldRegions world = SchedulerQueueCollector.world(world("w",
            region(RegionState.READY, NOW + 50 * MS, NO_TICK),
            region(RegionState.READY, NOW + 50 * MS, NO_TICK),
            region(RegionState.TICKING, NOW - 10 * MS, NOW - 3 * MS),
            region(RegionState.TRANSIENT, NOT_SET, NO_TICK),
            region(RegionState.DEAD, NOT_SET, NO_TICK)), NOW);

        assertEquals(5, world.total());
        assertEquals(2, world.ready());
        assertEquals(1, world.ticking());
        assertEquals(1, world.transitioning());
        assertEquals(1, world.dead());
    }

    @Test
    void onlyAReadyScheduledRegionPastItsStartBeyondTheThresholdIsOverdue() {
        final WorldRegions world = SchedulerQueueCollector.world(world("w",
            region(RegionState.READY, NOW - 7 * MS, NO_TICK),                         // overdue 7 ms
            region(RegionState.READY, NOW - 2 * MS, NO_TICK),                         // overdue 2 ms
            region(RegionState.READY, NOW - SchedulerQueueCollector.OVERDUE_THRESHOLD_NANOS, NO_TICK), // jitter
            region(RegionState.READY, NOW + 5 * MS, NO_TICK),                         // not due yet
            region(RegionState.READY, NOT_SET, NO_TICK),                              // no deadline
            region(RegionState.TICKING, NOW - 90 * MS, NOW - 90 * MS),             // running, not waiting
            region(RegionState.TRANSIENT, NOW - 90 * MS, NO_TICK),                 // not schedulable
            descheduled(region(RegionState.READY, NOW - 90 * MS, NO_TICK))), NOW);    // cancelled handle

        assertEquals(2, world.overdue());
        assertEquals(7 * MS, world.worstOverdueNanos());
        assertEquals(1, world.descheduled());
    }

    @Test
    void pendingTasksAndTicksInProgressAreCountedWithTheLongestTick() {
        final WorldRegions world = SchedulerQueueCollector.world(world("w",
            withTasks(region(RegionState.READY, NOW + MS, NO_TICK)),
            withTasks(region(RegionState.TICKING, NOW, NOW - 4 * MS)),
            region(RegionState.TICKING, NOW, NOW - 40 * MS),
            region(RegionState.READY, NOW + MS, NO_TICK)), NOW);

        assertEquals(2, world.withPendingTasks());
        assertEquals(2, world.tickInProgress());
        assertEquals(40 * MS, world.longestTickNanos());
    }

    @Test
    void aDeadRegionIsCountedButNotAskedAnythingElse() {
        final FakeRegion dead = withTasks(region(RegionState.DEAD, NOW - 90 * MS, NOW - 90 * MS));
        final WorldRegions world = SchedulerQueueCollector.world(world("w", dead), NOW);

        assertEquals(1, world.dead());
        assertEquals(0, world.overdue());
        assertEquals(0, world.withPendingTasks());
        assertEquals(0, world.tickInProgress());
        assertEquals(0, dead.taskPeeks, "a dead region's queues are never peeked");
        assertEquals(0L, world.queuedTickTasks() + world.queuedChunkTasks());
        assertEquals(0L, world.largestRegionQueue());
    }

    @Test
    void queuedTasksAreSummedPerQueueAndTheLargestRegionIsTickPlusChunk() {
        final WorldRegions world = SchedulerQueueCollector.world(world("w",
            queued(region(RegionState.READY, NOW + MS, NO_TICK), 3, 10),
            queued(region(RegionState.TICKING, NOW, NOW - MS), 9, 2),
            queued(region(RegionState.TRANSIENT, NOT_SET, NO_TICK), 1, 1),
            queued(region(RegionState.DEAD, NOT_SET, NO_TICK), 500, 500),   // never asked
            region(RegionState.READY, NOW + MS, NO_TICK)), NOW);

        assertEquals(13L, world.queuedTickTasks());
        assertEquals(13L, world.queuedChunkTasks());
        assertEquals(13L, world.largestRegionQueue(), "3 + 10 beats 9 + 2");
    }

    @Test
    void aNegativeQueueReadIsNotSubtracted() {
        // The engine counter is never negative; a broken probe must not shrink the world's total.
        final WorldRegions world = SchedulerQueueCollector.world(world("w",
            queued(region(RegionState.READY, NOW + MS, NO_TICK), -4, 2),
            queued(region(RegionState.READY, NOW + MS, NO_TICK), 5, 0)), NOW);

        assertEquals(5L, world.queuedTickTasks());
        assertEquals(2L, world.queuedChunkTasks());
        assertEquals(5L, world.largestRegionQueue());
    }

    @Test
    void lateTickCountsArePassedThroughAndAMissingReadIsUnknownNotZero() {
        final FakeSource source = new FakeSource();
        source.late = new LateTicks(1000L, 12L, 50L, 1L);
        assertEquals(new LateTicks(1000L, 12L, 50L, 1L), SchedulerQueueCollector.build(source, NOW).lateTicks());

        source.late = null;
        final LateTicks missing = SchedulerQueueCollector.build(source, NOW).lateTicks();
        assertEquals(LateTicks.NONE, missing);
        assertFalse(missing.known());
        assertEquals(LateTicks.NONE, SchedulerQueueSnapshot.unavailable("x").lateTicks());
    }

    @Test
    void theEngineAndTheViewShareOneLatenessThreshold() {
        // Patch 0045 inlines this constant; the view's "past their tick start" uses the same one.
        assertEquals(1_000_000L, SchedulerQueueCollector.OVERDUE_THRESHOLD_NANOS);
    }

    @Test
    void aTickStartInTheFutureIsNotNegativeTime() {
        // A racy read can see an active marker slightly after the collector's own clock.
        final WorldRegions world = SchedulerQueueCollector.world(world("w",
            region(RegionState.TICKING, NOW, NOW + 3 * MS)), NOW);

        assertEquals(1, world.tickInProgress());
        assertEquals(0L, world.longestTickNanos());
    }

    @Test
    void runnersAreCountedByActivityAndAMissingOneIsUnknown() {
        final var runners = SchedulerQueueCollector.countRunners(Arrays.asList(
            RunnerActivity.IDLE, RunnerActivity.REGION, RunnerActivity.REGION, RunnerActivity.GLOBAL,
            RunnerActivity.UNKNOWN, null));

        assertEquals(1, runners.idle());
        assertEquals(2, runners.region());
        assertEquals(1, runners.global());
        assertEquals(2, runners.unknown());
        assertEquals(6, runners.total());
    }

    @Test
    void aRunningGlobalTickIsNotCalledLateAndReportsHowLongItHasRun() {
        final GlobalTick global = SchedulerQueueCollector.globalTick(tick(NOW - 30 * MS, NOW - 30 * MS), NOW);

        assertEquals(0L, global.overdueNanos());
        assertEquals(30 * MS, global.runningNanos());
    }

    @Test
    void anIdleGlobalTickPastItsStartIsLate() {
        final GlobalTick global = SchedulerQueueCollector.globalTick(tick(NOW - 12 * MS, NO_TICK), NOW);

        assertEquals(12 * MS, global.overdueNanos());
        assertEquals(SchedulerQueueSnapshot.NONE, global.runningNanos());
    }

    @Test
    void anUnsetOrUnreadableGlobalTickIsNotInvented() {
        assertEquals(SchedulerQueueSnapshot.NONE,
            SchedulerQueueCollector.globalTick(tick(NOT_SET, NO_TICK), NOW).overdueNanos());
        assertEquals(GlobalTick.NONE, SchedulerQueueCollector.globalTick(null, NOW));
    }

    @Test
    void aFullBuildKeepsWorldOrderAndTheSchedulerFigures() {
        final FakeSource source = new FakeSource();
        source.scheduler = new Scheduler("AFFINITY", 4, 4, 4, 20.0, 4);
        source.runners = List.of(RunnerActivity.REGION, RunnerActivity.IDLE);
        source.global = tick(NOW + MS, NO_TICK);
        source.worlds = List.of(
            world("world", region(RegionState.READY, NOW - 5 * MS, NO_TICK)),
            world("world_nether"),
            world("world_the_end", region(RegionState.TICKING, NOW, NOW - MS)));

        final SchedulerQueueSnapshot snapshot = SchedulerQueueCollector.build(source, NOW);

        assertTrue(snapshot.available());
        assertEquals(source.scheduler, snapshot.scheduler());
        assertEquals(1, snapshot.runners().region());
        assertEquals(0L, snapshot.global().overdueNanos());
        assertEquals(List.of("world", "world_nether", "world_the_end"),
            snapshot.worlds().stream().map(WorldRegions::world).toList());
        assertEquals(1, snapshot.worlds().get(0).overdue());
        assertEquals(0, snapshot.worlds().get(1).total());
        assertEquals(1, snapshot.worlds().get(2).ticking());
    }

    @Test
    void aMissingSchedulerDescriptionBecomesUnknownRatherThanNull() {
        final FakeSource source = new FakeSource();
        source.scheduler = null;

        assertEquals(Scheduler.UNKNOWN, SchedulerQueueCollector.build(source, NOW).scheduler());
    }

    @Test
    void theLiveCollectorWithoutARunningServerSaysSoInsteadOfThrowing() {
        // In the unit-test JVM the region scheduler never started. The live path must notice that
        // before touching TickRegionScheduler (whose static initialiser reads the engine config).
        final SchedulerQueueSnapshot snapshot = SchedulerQueueCollector.collect();

        assertFalse(snapshot.available());
        assertFalse(snapshot.unavailableReason().isBlank());
    }

    // ---- fakes ----

    private static FakeRegion region(final RegionState state, final long start, final long active) {
        return new FakeRegion(state, start, active);
    }

    private static FakeRegion descheduled(final FakeRegion region) {
        region.descheduled = true;
        return region;
    }

    private static FakeRegion withTasks(final FakeRegion region) {
        region.tasks = true;
        return region;
    }

    private static FakeRegion queued(final FakeRegion region, final int tick, final int chunk) {
        region.queuedTick = tick;
        region.queuedChunk = chunk;
        return region;
    }

    private static TickProbe tick(final long start, final long active) {
        return new FakeRegion(RegionState.READY, start, active);
    }

    private static WorldProbe world(final String name, final RegionProbe... regions) {
        final List<RegionProbe> list = List.of(regions);
        return new WorldProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void forEachRegion(final Consumer<? super RegionProbe> action) {
                list.forEach(action);
            }
        };
    }

    private static final class FakeRegion implements RegionProbe {
        final RegionState state;
        final long start;
        final long active;
        boolean descheduled;
        boolean tasks;
        int taskPeeks;
        int queuedTick;
        int queuedChunk;

        FakeRegion(final RegionState state, final long start, final long active) {
            this.state = state;
            this.start = start;
            this.active = active;
        }

        @Override public RegionState state() { return this.state; }
        @Override public boolean descheduled() { return this.descheduled; }
        @Override public boolean hasPendingTasks() { this.taskPeeks++; return this.tasks; }
        @Override public long scheduledStartNanos() { return this.start; }
        @Override public long activeTickStartNanos() { return this.active; }
        @Override public int queuedTickTasks() { return this.queuedTick; }
        @Override public int queuedChunkTasks() { return this.queuedChunk; }
    }

    private static final class FakeSource implements SchedulerQueueCollector.Source {
        String reason;
        Scheduler scheduler = Scheduler.UNKNOWN;
        List<RunnerActivity> runners = List.of();
        TickProbe global;
        List<WorldProbe> worlds = new ArrayList<>();
        final AtomicInteger worldsRead = new AtomicInteger();
        LateTicks late = new LateTicks(0L, 0L, 0L, 0L);

        @Override public String unavailableReason() { return this.reason; }
        @Override public Scheduler scheduler() { return this.scheduler; }
        @Override public List<RunnerActivity> runners() { return this.runners; }
        @Override public TickProbe globalTick() { return this.global; }
        @Override public List<WorldProbe> worlds() { this.worldsRead.incrementAndGet(); return this.worlds; }
        @Override public LateTicks lateTicks() { return this.late; }
    }
}
