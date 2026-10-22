package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.GlobalTick;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.LateTicks;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.RunnerActivity;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.Runners;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.Scheduler;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.WorldRegions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Builds a {@link SchedulerQueueSnapshot} for {@code /perf scheduler} from any thread.
 *
 * <h2>Thread-safety contract</h2>
 * <p>The collector runs on a command thread (console or the global tick). It never takes a region
 * lock (the regioniser's {@code StampedLock}, {@code tryMarkTicking}), never takes a scheduler lock
 * (the affinity scheduler's {@code scheduleLock}, {@code StealingScheduledThreadPool}'s monitor),
 * never calls into region-owned state ({@code RegionizedWorldData}, entity/chunk lists) and never
 * writes anything. What it reads, and how:</p>
 * <ul>
 *   <li>The region table: {@code ThreadedRegionizer.computeForAllRegionsUnsynchronised}, which walks a
 *       {@code SWMRLong2ObjectHashTable} (single-writer, multi-reader: built for lock-free readers).
 *       Regions created, merged or split during the walk may be missed or seen twice: approximate.</li>
 *   <li>A region's state: {@code ThreadedRegion.getState()} reads a plain {@code int} once. A racy
 *       {@code int} read cannot tear; it may be stale: approximate.</li>
 *   <li>A region's next scheduled tick start: {@code SchedulableTick.getScheduledStart()}, a plain
 *       {@code long} written by the ticking thread. It may be stale, and the JLS allows a non-volatile
 *       {@code long} to tear (64-bit HotSpot does not): approximate.</li>
 *   <li>The current tick handle: {@code TickRegionData.tickHandle}, a plain field the regioniser
 *       replaces when a region goes inactive. A stale handle still answers the reads below.</li>
 *   <li>Pending tasks: {@code RegionScheduleHandle.hasTasks()}, the same peek every scheduler thread
 *       makes for regions it does not own. It holds each of the region's two task-queue monitors for
 *       one {@code isEmpty()} pass; these are the leaf locks every producer thread takes to queue a
 *       task, not region locks, and nothing is acquired while holding them: approximate.</li>
 *   <li>A tick in progress: {@code RegionTickMetrics.snapshot()}, a volatile publication plus an
 *       acquire read of the active-tick marker (lock-free, set only by {@code runTick}).</li>
 *   <li>Scheduler threads: {@code Scheduler.getCoreThreads()} (a cloned final array for EDF and
 *       AFFINITY, a copy-on-write array for WORK_STEALING) and each {@code TickThreadRunner}'s plain
 *       {@code currentTickingTask} reference: a racy reference read, stale at worst: approximate.</li>
 *   <li>Scheduler type and configured thread count: {@code SchedulerUtil.getSchedulerInfo()},
 *       written once during startup before any command thread exists. Pool and alive threads:
 *       {@code getCoreThreads()}/{@code getAliveThreads()}. Tick rate: a volatile field.</li>
 *   <li>Queued tasks per region: {@code RegionTaskQueueData.sourbyQueuedTickTasks()} /
 *       {@code sourbyQueuedChunkTasks()} (feature patch), a volatile {@code int} per queue that is
 *       only written while that queue's monitor is held, read here without the monitor. Each read is
 *       a value the queue really had; the two queues and the regions are read at different instants,
 *       and the figure counts deque entries, so a task cancelled or re-prioritised but not yet polled
 *       still counts (the same figure the region's own drain uses as its budget): approximate.</li>
 *   <li>Late tick starts since boot: {@code TickRegionScheduler.sourbyLateRegionTicks()} and its
 *       siblings (feature patch), {@code LongAdder}s incremented by the scheduler thread as a tick
 *       starts. Each is monotonic; {@code sum()} is not an atomic snapshot while ticks start. The late
 *       figure is read before the started figure, so late never exceeds started: approximate.</li>
 * </ul>
 *
 * <h2>Not readable, shown as n/a</h2>
 * <ul>
 *   <li>The scheduler's own run queue (EDF heap, affinity global/local queues, work-stealing
 *       per-thread queues) and the affinity idle-thread set: private, and only consistent under the
 *       scheduler's lock, which a diagnostic command must not take.</li>
 *   <li>The global tick's task-queue depth: a private {@code MultiThreadedQueue} with no size accessor.</li>
 *   <li>The world-wide global chunk-task queue ({@code WorldRegionTaskData}): no counter.</li>
 * </ul>
 */
public final class SchedulerQueueCollector {

    /**
     * A READY region whose start is at most this far in the past is not called overdue: a free
     * thread picks a due region up within microseconds, and the read itself is racy.
     */
    public static final long OVERDUE_THRESHOLD_NANOS = 1_000_000L;   // 1 ms; a compile-time constant the engine patch inlines

    /** Sentinel for "no deadline", equal to the engine's {@code TimeUtil.DEADLINE_NOT_SET}. */
    public static final long DEADLINE_NOT_SET = Long.MIN_VALUE;

    /** Sentinel for "no tick in progress", equal to {@link RegionTickMetrics#INACTIVE}. */
    public static final long NO_ACTIVE_TICK = RegionTickMetrics.INACTIVE;

    private SchedulerQueueCollector() {}

    /** The engine's region state, as read (racily) from one region. */
    public enum RegionState { TRANSIENT, READY, TICKING, DEAD }

    /** The scheduling fields of one tick handle. */
    public interface TickProbe {
        /** Next scheduled tick start, or {@link #DEADLINE_NOT_SET}. */
        long scheduledStartNanos();

        /** Start of the tick in progress, or {@link #NO_ACTIVE_TICK}. */
        long activeTickStartNanos();
    }

    /** One region as the collector sees it. */
    public interface RegionProbe extends TickProbe {
        RegionState state();

        boolean descheduled();

        boolean hasPendingTasks();

        /** Entries in the region's tick-task queue (lock-free, approximate). */
        int queuedTickTasks();

        /** Entries in the region's chunk-task queue (lock-free, approximate). */
        int queuedChunkTasks();
    }

    /** One world's regions. */
    public interface WorldProbe {
        String name();

        void forEachRegion(Consumer<? super RegionProbe> action);
    }

    /** Everything the collector reads, so the snapshot logic can be tested without a server. */
    public interface Source {
        /** Null when the scheduler can be read; otherwise why not. */
        String unavailableReason();

        Scheduler scheduler();

        List<RunnerActivity> runners();

        /** The global tick handle, or null when it cannot be read. */
        TickProbe globalTick();

        List<WorldProbe> worlds();

        /** Late tick starts since boot, or null when the counters cannot be read. */
        LateTicks lateTicks();
    }

    /** Reads the live engine. Never throws: an engine that is not running gives an unavailable snapshot. */
    public static SchedulerQueueSnapshot collect() {
        final Source source;
        try {
            source = LiveSchedulerSource.create();
        } catch (final RuntimeException | LinkageError failure) {
            return SchedulerQueueSnapshot.unavailable("region scheduler not readable (" + failure.getClass().getSimpleName() + ")");
        }
        if (source == null) {
            return SchedulerQueueSnapshot.unavailable("region scheduler not started");
        }
        try {
            return build(source, System.nanoTime());
        } catch (final RuntimeException | LinkageError failure) {
            return SchedulerQueueSnapshot.unavailable("region scheduler not readable (" + failure.getClass().getSimpleName() + ")");
        }
    }

    /** Builds a snapshot from {@code source} as of {@code nowNanos}. */
    public static SchedulerQueueSnapshot build(final Source source, final long nowNanos) {
        Objects.requireNonNull(source, "source");
        final String reason = source.unavailableReason();
        if (reason != null) {
            return SchedulerQueueSnapshot.unavailable(reason);
        }
        final Scheduler scheduler = Objects.requireNonNullElse(source.scheduler(), Scheduler.UNKNOWN);
        final Runners runners = countRunners(source.runners());
        final GlobalTick global = globalTick(source.globalTick(), nowNanos);
        final List<WorldRegions> worlds = new ArrayList<>();
        for (final WorldProbe world : source.worlds()) {
            worlds.add(world(world, nowNanos));
        }
        final LateTicks late = Objects.requireNonNullElse(source.lateTicks(), LateTicks.NONE);
        return new SchedulerQueueSnapshot(true, "", scheduler, runners, global, worlds, late);
    }

    static Runners countRunners(final List<RunnerActivity> activities) {
        int idle = 0, region = 0, global = 0, unknown = 0;
        for (final RunnerActivity activity : activities) {
            switch (activity == null ? RunnerActivity.UNKNOWN : activity) {
                case IDLE -> idle++;
                case REGION -> region++;
                case GLOBAL -> global++;
                case UNKNOWN -> unknown++;
            }
        }
        return new Runners(idle, region, global, unknown);
    }

    static GlobalTick globalTick(final TickProbe probe, final long nowNanos) {
        if (probe == null) {
            return GlobalTick.NONE;
        }
        final long active = probe.activeTickStartNanos();
        final long running = active == NO_ACTIVE_TICK ? SchedulerQueueSnapshot.NONE : Math.max(0L, nowNanos - active);
        final long start = probe.scheduledStartNanos();
        final long overdue;
        if (start == DEADLINE_NOT_SET) {
            overdue = SchedulerQueueSnapshot.NONE;
        } else if (active != NO_ACTIVE_TICK) {
            // While a tick runs, the scheduled start is still this tick's own start; the next one
            // is only written when the tick ends, so lateness is not defined until then.
            overdue = 0L;
        } else {
            overdue = lateness(start, nowNanos);
        }
        return new GlobalTick(overdue, running);
    }

    static WorldRegions world(final WorldProbe world, final long nowNanos) {
        final int[] counts = new int[9];
        final long[] worst = new long[2];
        final long[] queued = new long[3];   // tick entries, chunk entries, largest single region
        world.forEachRegion(region -> {
            counts[0]++;
            final RegionState state = region.state();
            switch (state == null ? RegionState.TRANSIENT : state) {
                case READY -> counts[1]++;
                case TICKING -> counts[2]++;
                case TRANSIENT -> counts[3]++;
                case DEAD -> counts[4]++;
            }
            if (state == RegionState.DEAD) {
                return;                       // Its handle no longer schedules; nothing else applies.
            }
            final boolean descheduled = region.descheduled();
            if (descheduled) {
                counts[5]++;
            }
            if (state == RegionState.READY && !descheduled) {
                final long start = region.scheduledStartNanos();
                if (start != DEADLINE_NOT_SET) {
                    final long late = lateness(start, nowNanos);
                    if (late > OVERDUE_THRESHOLD_NANOS) {
                        counts[6]++;
                        worst[0] = Math.max(worst[0], late);
                    }
                }
            }
            if (region.hasPendingTasks()) {
                counts[7]++;
            }
            final long tick = Math.max(0, region.queuedTickTasks());
            final long chunk = Math.max(0, region.queuedChunkTasks());
            queued[0] += tick;
            queued[1] += chunk;
            queued[2] = Math.max(queued[2], tick + chunk);
            final long active = region.activeTickStartNanos();
            if (active != NO_ACTIVE_TICK) {
                counts[8]++;
                worst[1] = Math.max(worst[1], Math.max(0L, nowNanos - active));
            }
        });
        return new WorldRegions(world.name(), counts[0], counts[1], counts[2], counts[3], counts[4],
            counts[5], counts[6], worst[0], counts[7], counts[8], worst[1], queued[0], queued[1], queued[2]);
    }

    private static long lateness(final long scheduledStart, final long nowNanos) {
        return Math.max(0L, nowNanos - scheduledStart);
    }
}
