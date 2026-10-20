package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.concurrentutil.scheduler.SchedulableTick;
import dev.iyanz.aurora.engine.threadedregions.SchedulerUtil;
import dev.iyanz.sourbycraft.perf.SchedulerQueueCollector.RegionProbe;
import dev.iyanz.sourbycraft.perf.SchedulerQueueCollector.RegionState;
import dev.iyanz.sourbycraft.perf.SchedulerQueueCollector.TickProbe;
import dev.iyanz.sourbycraft.perf.SchedulerQueueCollector.WorldProbe;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.RunnerActivity;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.Scheduler;
import io.canvasmc.canvas.region.WorldRegionizer;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The live engine behind {@link SchedulerQueueCollector}. Kept apart from the collector so the
 * snapshot logic and its tests never link the engine classes. Every read here follows the
 * thread-safety contract documented on {@link SchedulerQueueCollector}: no region lock, no
 * scheduler lock, no region-owned state, no writes.
 */
final class LiveSchedulerSource implements SchedulerQueueCollector.Source {

    private final TickRegionScheduler tickScheduler;
    private final MinecraftServer server;

    private LiveSchedulerSource(final TickRegionScheduler tickScheduler, final MinecraftServer server) {
        this.tickScheduler = tickScheduler;
        this.server = server;
    }

    /**
     * Null until the region scheduler has started. {@code TickRegions} is checked first because
     * {@code TickRegionScheduler}'s static initialiser reads the engine configuration and must not be
     * triggered before the server has loaded it.
     */
    static LiveSchedulerSource create() {
        if (!TickRegions.hasStarted()) {
            return null;
        }
        final TickRegionScheduler scheduler = TickRegions.getScheduler();
        final MinecraftServer server = MinecraftServer.getServer();
        if (scheduler == null || server == null) {
            return null;
        }
        return new LiveSchedulerSource(scheduler, server);
    }

    @Override
    public String unavailableReason() {
        return null;
    }

    @Override
    public Scheduler scheduler() {
        // Written once during TickRegions.init, before the server starts any thread that runs commands.
        final SchedulerUtil.SchedulerInfo info = SchedulerUtil.getSchedulerInfo();
        return new Scheduler(
            info.schedulerType().name(),
            info.initialThreads(),
            this.tickScheduler.scheduler.getCoreThreads().length,  // cloned final array / COW array: no scheduler lock
            this.tickScheduler.getTotalThreadCount(),               // getAliveThreads(): no scheduler lock
            TickRegionScheduler.getTickRate(),                       // volatile
            TickRegions.getRegionChunkShift()                        // written once during init
        );
    }

    @Override
    public List<RunnerActivity> runners() {
        final SchedulableTick global = RegionizedServer.getGlobalTickData();
        final List<RunnerActivity> out = new ArrayList<>();
        // getCoreThreads(): a clone of a final array (EDF, AFFINITY) or a copy-on-write array
        // (WORK_STEALING). Neither takes the scheduler's lock.
        for (final Thread thread : this.tickScheduler.scheduler.getCoreThreads()) {
            if (!(thread instanceof TickRegionScheduler.TickThreadRunner runner)) {
                out.add(RunnerActivity.UNKNOWN);
                continue;
            }
            // Plain reference written by the runner itself around each tick/task run; a racy read
            // is stale at worst, never torn.
            final SchedulableTick task = runner.currentTickingTask;
            out.add(task == null ? RunnerActivity.IDLE : task == global ? RunnerActivity.GLOBAL : RunnerActivity.REGION);
        }
        return out;
    }

    @Override
    public TickProbe globalTick() {
        return probe(RegionizedServer.getGlobalTickData());
    }

    @Override
    public List<WorldProbe> worlds() {
        final List<WorldProbe> out = new ArrayList<>();
        for (final ServerLevel level : this.server.getAllLevels()) {
            out.add(new LiveWorld(level));
        }
        return out;
    }

    private static TickProbe probe(final TickRegionScheduler.RegionScheduleHandle handle) {
        return new TickProbe() {
            @Override
            public long scheduledStartNanos() {
                return handle.getScheduledStart();
            }

            @Override
            public long activeTickStartNanos() {
                return activeTickStart(handle);
            }
        };
    }

    /** Lock-free: a volatile snapshot plus an acquire read of the active-tick marker. */
    private static long activeTickStart(final TickRegionScheduler.RegionScheduleHandle handle) {
        return handle.sourbyTickMetrics.current().snapshot(0L).activeTickStartNanos();
    }

    private record LiveWorld(ServerLevel level) implements WorldProbe {
        @Override
        public String name() {
            return this.level.getWorld().getName();
        }

        @Override
        public void forEachRegion(final Consumer<? super RegionProbe> action) {
            // SWMR table walk: lock-free for readers (never computeForAllRegions, which takes the
            // regioniser's read lock).
            this.level.regioniser.computeForAllRegionsUnsynchronised(region -> action.accept(new LiveRegion(region)));
        }
    }

    private static final class LiveRegion implements RegionProbe {
        private final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region;
        // Read once so every answer comes from the same handle, even if the regioniser swaps it.
        private final TickRegionScheduler.RegionScheduleHandle handle;

        LiveRegion(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
            this.region = region;
            this.handle = region.getData().tickHandle;
        }

        @Override
        public RegionState state() {
            final WorldRegionizer.ChunkRegion.State state = this.region.getState();   // one plain int read, racy
            return switch (state) {
                case TRANSIENT -> RegionState.TRANSIENT;
                case READY -> RegionState.READY;
                case TICKING -> RegionState.TICKING;
                case DEAD -> RegionState.DEAD;
            };
        }

        @Override
        public boolean descheduled() {
            return this.handle.isMarkedAsNonSchedulable();   // AtomicBoolean
        }

        @Override
        public boolean hasPendingTasks() {
            // Same peek the scheduler threads make for regions they do not own: two leaf queue
            // monitors (one isEmpty pass each) and an AtomicBoolean. Not a region lock.
            return this.handle.hasTasks();
        }

        @Override
        public long scheduledStartNanos() {
            return this.handle.getScheduledStart();   // plain long, racy
        }

        @Override
        public long activeTickStartNanos() {
            return activeTickStart(this.handle);
        }
    }
}
