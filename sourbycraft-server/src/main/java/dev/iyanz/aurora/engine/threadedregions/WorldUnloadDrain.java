package dev.iyanz.aurora.engine.threadedregions;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import io.papermc.paper.threadedregions.EntityScheduler;
import io.papermc.paper.threadedregions.RegionShutdownThread;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import io.papermc.paper.threadedregions.scheduler.FoliaRegionScheduler;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerLevel;

/**
 * What a world unload owes the work still queued on it (audit ENG-3), run by the engine's
 * {@code WorldShutdownThread} (MC feature patch 0033) after the world's chunks are saved.
 *
 * <p>Once a world is marked for unloading its regions stop ticking, so nothing queued on them runs
 * again. Before this, that work was simply abandoned: an entity scheduler's tasks neither ran nor
 * had their retired callbacks invoked (Folia's contract: one of the two always runs), region
 * scheduler tasks stayed {@code IDLE} forever, and the region task queues kept their runnables
 * alive. Per region, with the shutdown thread owning it:</p>
 *
 * <ul>
 *   <li>every entity scheduler that holds tasks is retired: each pending task gets exactly its
 *       retired callback, or nothing when it has none, and never its body. The entities are saved,
 *       not removed, so this relies on Paper feature patch 0021: a Bukkit entity task
 *       ({@code FoliaEntityScheduler}) is its own run and retired callback and used to choose by
 *       {@code entity.isRemoved()} alone, which ran every body here, delayed ones included (found by
 *       the test plugin's unload fixture, 2026-10-21). Delayed tasks are not fast-forwarded: retiring
 *       never runs a body, whatever its due tick;</li>
 *   <li>every region scheduler task is cancelled ({@code CANCELLED}, its body never runs);</li>
 *   <li>every queued region task is cancelled without running (cancel-only); chunk tickets are
 *       not touched, the chunk system is already closed.</li>
 * </ul>
 *
 * <p>Afterwards the Aurora Bridge releases the bridged legacy tasks anchored in the world, whose
 * region scheduler tasks were just cancelled without its knowledge.</p>
 *
 * <p>The callbacks run on the world's shutdown thread, the thread that owns every region of the
 * world while it unloads, like the teleport completion and chunk saving before them. Work queued
 * on the world after this point is not drained; the unload refuses new players and teleports
 * into the world, so that is a narrow race, not a path.</p>
 */
public final class WorldUnloadDrain {
    // Engine code, beside WorldShutdownThread: the feature patch calls it unqualified from the same
    // package, so the patch adds no engine or scheduler symbol (scripts/independence_policy.py).

    private WorldUnloadDrain() {}

    /** The per-region steps, separated so the order and failure handling can be tested. */
    interface Steps<R> {
        void enter(R region);

        void exit();

        int retireEntitySchedulers(R region);

        int cancelRegionSchedulerTasks(R region);

        int cancelQueuedTasks(R region);
    }

    /** What a drain did. */
    record Result(int retired, int cancelledScheduled, int cancelledQueued, int failures) {
        boolean anything() {
            return this.retired + this.cancelledScheduled + this.cancelledQueued + this.failures > 0;
        }
    }

    /**
     * Runs every step for every region; a step that throws is reported and the rest still run.
     */
    static <R> Result drain(final List<R> regions, final Steps<R> steps, final BiConsumer<String, Throwable> error) {
        int retired = 0;
        int scheduled = 0;
        int queued = 0;
        int failures = 0;
        for (final R region : regions) {
            steps.enter(region);
            try {
                try {
                    retired += steps.retireEntitySchedulers(region);
                } catch (final Throwable failed) {
                    failures++;
                    error.accept("Failed to retire entity schedulers", failed);
                }
                try {
                    scheduled += steps.cancelRegionSchedulerTasks(region);
                } catch (final Throwable failed) {
                    failures++;
                    error.accept("Failed to cancel region scheduler tasks", failed);
                }
                try {
                    queued += steps.cancelQueuedTasks(region);
                } catch (final Throwable failed) {
                    failures++;
                    error.accept("Failed to cancel queued region tasks", failed);
                }
            } finally {
                steps.exit();
            }
        }
        return new Result(retired, scheduled, queued, failures);
    }

    /**
     * Retires every scheduler not retired yet, which invokes each pending task's retired callback
     * (never its body, see the class comment). A scheduler whose callback throws is reported and the
     * others still retire: one plugin's retired callback must not keep the others from running.
     *
     * @return how many schedulers were retired without an error
     */
    static int retireSchedulers(final EntityScheduler[] schedulers, final java.util.function.Consumer<Throwable> error) {
        int retired = 0;
        for (final EntityScheduler scheduler : schedulers) {
            if (scheduler.isRetired()) continue;
            try {
                scheduler.retire();
                retired++;
            } catch (final Throwable failed) {
                error.accept(failed);
            }
        }
        return retired;
    }

    /** Drains the regions of an unloading world on its shutdown thread. Never throws. */
    public static void drain(final RegionShutdownThread thread, final ServerLevel level,
                             final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions,
                             final org.slf4j.Logger logger) {
        final String world = level.getWorld().getName();
        final Result result;
        try {
            result = drain(regions, new Steps<>() {
                @Override
                public void enter(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
                    thread.shuttingDown = region;
                }

                @Override
                public void exit() {
                    thread.shuttingDown = null;
                }

                @Override
                public int retireEntitySchedulers(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
                    final RegionizedWorldData data = region.getData().getRegionizedData(level.worldRegionData);
                    if (data == null) return 0;
                    return retireSchedulers(data.entitySchedulerTickList.getAllSchedulers(),
                        failed -> logger.error("Failed to retire an entity scheduler while unloading {}", world, failed));
                }

                @Override
                public int cancelRegionSchedulerTasks(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
                    return FoliaRegionScheduler.sourbyCancelCurrentRegionTasks();
                }

                @Override
                public int cancelQueuedTasks(final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
                    return region.getData().getTaskQueueData().sourbyCancelQueuedTasks();
                }
            }, (message, failed) -> logger.error("{} while unloading {}", message, world, failed));
        } catch (final Throwable failed) {
            logger.error("Failed to drain the regions of {}", world, failed);
            releaseBridgedTasks(level, world, logger);
            return;
        }
        releaseBridgedTasks(level, world, logger);
        if (result.anything()) {
            logger.info("Drained {} for unload: retired {} entity scheduler(s), cancelled {} region scheduler task(s)"
                    + " and {} queued region task(s){}", world, result.retired(), result.cancelledScheduled(),
                result.cancelledQueued(), result.failures() > 0 ? ", " + result.failures() + " step(s) failed" : "");
        }
    }

    /**
     * The drain cancelled the world's region scheduler tasks without telling the Aurora Bridge;
     * release the bridged legacy tasks anchored there so they do not stay pending for good. Also run
     * by {@link WorldUnloadCompletion} when the unload threw before the drain (a chunk save failed),
     * so the release does not depend on reaching the drain; a second call releases nothing. New
     * bridged tasks anchored in the world are refused from the start of the unload (its ticket).
     */
    static void releaseBridgedTasks(final ServerLevel level, final String world, final org.slf4j.Logger logger) {
        try {
            final int released = dev.iyanz.sourbycraft.bridge.AuroraBridge.worldUnloaded(level.getWorld());
            if (released > 0) {
                logger.info("Released {} bridged legacy task(s) anchored in {} for unload", released, world);
            }
        } catch (final Throwable failed) {
            logger.error("Failed to release bridged tasks of {}", world, failed);
        }
    }

    /**
     * The unload finished, so the world is removed, but Aurora World Fabric did not confirm the
     * final commit of every one of its storages (one was left open, or its final commit failed or
     * could not be confirmed): what they held since their last confirmed commit may or may not be
     * on the backend. {@link WorldUnloadCompletion} answers
     * it with {@code FAIL_UNKNOWN} and an ERROR instead of rethrowing it: a rethrow reaches the
     * shutdown thread's handler, which stops the server, and with the world already gone there is
     * nothing left to save or retry.
     */
    public static final class AwfUnloadNotConfirmed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AwfUnloadNotConfirmed(final String world, final List<String> storages) {
            super("World " + world + " was unloaded, but Aurora World Fabric did not confirm the final commit of "
                + storages + " (left open, or the final commit failed or was not confirmed): the chunks written since"
                + " their last confirmed commit may or may not have been applied");
        }
    }

    /**
     * Runs the unload, then refuses success if the inherited close path logged and swallowed an AWF
     * commit failure.
     *
     * @throws AwfUnloadNotConfirmed when the unload returned but a storage of the world did not
     *     confirm its final commit
     */
    public static void runWithAwfConfirmation(final ServerLevel level, final Runnable unload) {
        final java.nio.file.Path folder = net.minecraft.server.MinecraftServer.getServer()
            .storageSource.getDimensionPath(level.dimension());
        try (var watch = dev.iyanz.sourbycraft.awf.AwfEngine.watchClose(folder)) {
            unload.run();
            final List<String> unconfirmed = watch.unconfirmed();
            if (!unconfirmed.isEmpty()) throw new AwfUnloadNotConfirmed(level.getWorld().getName(), unconfirmed);
        }
    }

    /**
     * Closes the world's region storage when it has no region to save (audit follow-up to AWF-8).
     * The storage is otherwise closed by the last region's chunk save; a world unloaded with no
     * chunk loaded -- no region at all -- kept its region files, and an Aurora World Fabric world
     * its stores (and Redis lease), open until the server stopped.
     *
     * @return whether it closed anything, which it does only when there is no region
     */
    public static boolean closeStorageWithoutRegions(final int regions, final Runnable close) {
        if (regions > 0) return false;
        close.run();
        return true;
    }

    /** As {@link #closeStorageWithoutRegions(int, Runnable)} for a level: flush and close, nothing to save. */
    public static void closeStorageWithoutRegions(final ServerLevel level, final int regions) {
        closeStorageWithoutRegions(regions, () -> ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler()
            .chunkHolderManager.close(false, true, false, true, false));
    }
}
