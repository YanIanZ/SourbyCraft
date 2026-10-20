package dev.iyanz.sourbycraft.perf;

import java.util.List;
import java.util.Objects;

/**
 * One reading of the region scheduler, taken from a command thread without any lock.
 *
 * <p>Every field is either a value the engine publishes for cross-thread reads, or a plain field
 * read racily and therefore labelled approximate by {@link SchedulerQueueView}. What the engine
 * keeps private to a scheduler lock or a region is not in here at all; the view prints those as
 * "n/a" with the reason, see {@link SchedulerQueueCollector}.</p>
 *
 * @param available        false when the region scheduler has not started (or the server is gone)
 * @param unavailableReason why, when {@code available} is false; empty otherwise
 * @param scheduler        type and thread figures of the scheduler pool
 * @param runners          what each scheduler thread was doing at the moment it was looked at
 * @param global           the global tick's lateness and in-progress tick
 * @param worlds           region counts per world, in server world order
 */
public record SchedulerQueueSnapshot(
    boolean available,
    String unavailableReason,
    Scheduler scheduler,
    Runners runners,
    GlobalTick global,
    List<WorldRegions> worlds
) {

    /** Marker for a nanosecond figure that could not be read or does not apply. */
    public static final long NONE = -1L;

    public SchedulerQueueSnapshot {
        Objects.requireNonNull(unavailableReason, "unavailableReason");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(runners, "runners");
        Objects.requireNonNull(global, "global");
        worlds = List.copyOf(worlds);
    }

    public static SchedulerQueueSnapshot unavailable(final String reason) {
        return new SchedulerQueueSnapshot(false, reason, Scheduler.UNKNOWN, Runners.NONE, GlobalTick.NONE, List.of());
    }

    /**
     * @param type              configured {@code threaded-regions.scheduler} the running pool was built from, or "unknown"
     * @param configuredThreads threads the scheduler was created with; -1 when unknown
     * @param coreThreads       threads in the pool now ({@code getCoreThreads()}); differs from the
     *                          configured figure for WORK_STEALING, whose allocation is per NUMA node; -1 when unknown
     * @param aliveThreads      scheduler threads alive at read time; -1 when unknown
     * @param tickRate          current region tick rate (ticks per second); NaN when unknown
     * @param regionShift       {@code grid-exponent}: a region section is 2^shift chunks wide; -1 when unknown
     */
    public record Scheduler(String type, int configuredThreads, int coreThreads, int aliveThreads, double tickRate, int regionShift) {
        public static final Scheduler UNKNOWN = new Scheduler("unknown", -1, -1, -1, Double.NaN, -1);

        public Scheduler {
            Objects.requireNonNull(type, "type");
        }
    }

    /** What one scheduler thread was running when looked at. */
    public enum RunnerActivity {
        /** No tick or task in progress. */
        IDLE,
        /** A region tick or that region's intermediate tasks. */
        REGION,
        /** The global tick or its tasks. */
        GLOBAL,
        /** Not a recognised tick runner thread, or not started yet. */
        UNKNOWN
    }

    /** Counts of scheduler threads by {@link RunnerActivity}. */
    public record Runners(int idle, int region, int global, int unknown) {
        public static final Runners NONE = new Runners(0, 0, 0, 0);

        public int total() {
            return this.idle + this.region + this.global + this.unknown;
        }
    }

    /**
     * @param overdueNanos  how far past its scheduled start the next global tick is; 0 when not late,
     *                      {@link #NONE} when the start is not set or not readable
     * @param runningNanos  how long the global tick in progress has been running; {@link #NONE} when none
     */
    public record GlobalTick(long overdueNanos, long runningNanos) {
        public static final GlobalTick NONE = new GlobalTick(SchedulerQueueSnapshot.NONE, SchedulerQueueSnapshot.NONE);
    }

    /**
     * Region counts of one world.
     *
     * @param world             world name
     * @param total             regions in the regioniser's table
     * @param ready             regions in READY: scheduled and waiting for their next tick or tasks
     * @param ticking           regions in TICKING: a thread is running their tick or their tasks
     * @param transitioning     regions in TRANSIENT: being merged or split, not schedulable meanwhile
     * @param dead              regions in DEAD: merged away or destroyed, not yet removed from the table
     * @param descheduled       regions whose current tick handle is marked non-schedulable
     * @param overdue           READY regions whose scheduled tick start has already passed (waiting for a thread)
     * @param worstOverdueNanos the largest such delay; 0 when none
     * @param withPendingTasks  regions reporting queued intermediate tasks or packets
     * @param tickInProgress    regions with a tick in progress (task runs do not count)
     * @param longestTickNanos  the longest such in-progress tick so far; 0 when none
     */
    public record WorldRegions(
        String world,
        int total,
        int ready,
        int ticking,
        int transitioning,
        int dead,
        int descheduled,
        int overdue,
        long worstOverdueNanos,
        int withPendingTasks,
        int tickInProgress,
        long longestTickNanos
    ) {
        public WorldRegions {
            Objects.requireNonNull(world, "world");
        }
    }
}
