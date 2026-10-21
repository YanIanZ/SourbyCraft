package dev.iyanz.sourbycraft.testplugin;

import dev.iyanz.sourbycraft.api.region.WorldRegionizer;
import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import dev.iyanz.sourbycraft.api.world.WorldUnloadResult;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * The unload fixture (Build 49 gate "Region ownership"): creates a temporary world, queues region
 * scheduler tasks across several regions, entity scheduler tasks on summoned entities and tasks that
 * are only due far in the future, unloads the world while all of that is pending, and records what
 * happened to every task ({@link UnloadLedger}). The unload is requested while each region holds work
 * it queued for its next tick (a bounded hold of the region thread), so that work is pending when the
 * engine drains the world.
 *
 * <p>Registered only with {@code -Dsourby.testplugin.probes=true}; it runs once after the server
 * finishes loading ({@code ServerLoadEvent} STARTUP) and again on {@code /unloadprobe}. Markers go
 * to the server log in the format the CI boot step greps ({@code SOURBY_UNLOAD_FIXTURE_*}); a
 * marker ending in {@code _WRONG} or {@code _FAILED} fails the step.</p>
 *
 * <p>What it proves: nothing queued on the unloading world runs after the unload callback; every
 * far-future region task is cancelled and every far-future entity task has its retired callback
 * invoked exactly once; tasks queued in the same global tick as the unload either ran before it or
 * are drained (they race the regions' last tick, so the split between the two is reported, not
 * asserted); no exception reaches a task; the world is gone and its folder has no open file left.
 * What it does not prove: behaviour with players in the world, a cross-region teleport in flight, a
 * region held in a long tick, or the Redis backend. The far-future tasks are the deterministic part.</p>
 */
final class UnloadFixtureProbe implements BasicCommand, Listener {

    static final String PREFIX = "SOURBY_UNLOAD_FIXTURE_";
    private static final String WORLD = "unloadfixture";
    /** Chunk positions far enough apart to be separate regions (a region spans a few 16-chunk sections). */
    private static final int[][] CHUNKS = {{0, 0}, {256, 0}, {0, 256}, {256, 256}};
    private static final int STANDS_PER_REGION = 2;
    /** An hour of ticks: never due while the fixture runs. */
    private static final long FAR = 72_000L;
    private static final long QUIET_TICKS = 60L;
    private static final long OVERALL_TIMEOUT_TICKS = 20L * 150;
    /** How long a region thread may wait for the unload request, and how long it holds on after it. */
    private static final long HOLD_LIMIT_NANOS = 2_000_000_000L;
    private static final long HOLD_AFTER_REQUEST_MILLIS = 150L;

    private final Plugin plugin;
    private final AtomicInteger runs = new AtomicInteger();
    private final AtomicBoolean automatic = new AtomicBoolean();

    UnloadFixtureProbe(final Plugin plugin) {
        this.plugin = plugin;
    }

    /** Runs the fixture once, after the server finished loading. */
    @EventHandler
    public void onServerLoad(final ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP || !this.automatic.compareAndSet(false, true)) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, task -> this.start(), 40L);
    }

    @Override
    public void execute(final CommandSourceStack source, final String[] args) {
        this.start();
    }

    private void start() {
        new Run(this.plugin, WORLD + (this.runs.incrementAndGet() > 1 ? Integer.toString(this.runs.get()) : "")).begin();
    }

    static void marker(final String name, final String detail) {
        Bukkit.getLogger().info(PREFIX + name + (detail.isEmpty() ? "" : " " + detail));
    }

    /** One execution of the fixture: its own world name, ledger and handles. */
    private static final class Run {
        private final Plugin plugin;
        private final String name;
        private final UnloadLedger ledger = new UnloadLedger();
        private final List<ScheduledTask> regionTasks = new CopyOnWriteArrayList<>();
        private final List<Integer> regionTaskIds = new CopyOnWriteArrayList<>();
        private final List<ArmorStand> stands = new CopyOnWriteArrayList<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean unloadIssued = new AtomicBoolean();
        private final List<String> bodyFailures = new CopyOnWriteArrayList<>();
        private final List<String> notOwnedBefore = new CopyOnWriteArrayList<>();
        private final List<String> notOwnedDuring = new CopyOnWriteArrayList<>();
        private final List<String> notOwnedAfter = new CopyOnWriteArrayList<>();
        private volatile World world;
        private volatile String stage = "create";

        Run(final Plugin plugin, final String name) {
            this.plugin = plugin;
            this.name = name;
        }

        void begin() {
            final AuroraWorlds worlds = Bukkit.getServicesManager().load(AuroraWorlds.class);
            if (worlds == null) {
                this.fail("the AuroraWorlds service is not available");
                return;
            }
            Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, t -> this.fail("did not finish in time"), OVERALL_TIMEOUT_TICKS);
            marker("START", "world=" + this.name);
            worlds.create(new WorldCreator(this.name), "void", false).whenComplete((created, failure) -> {
                if (failure != null) {
                    this.fail("world creation failed: " + failure);
                    return;
                }
                this.world = created;
                this.onGlobal(this::loadRegions);
            });
        }

        private void onGlobal(final Runnable step) {
            Bukkit.getGlobalRegionScheduler().run(this.plugin, t -> {
                try {
                    step.run();
                } catch (final Throwable failed) {
                    this.fail(this.stage + " threw " + failed);
                }
            });
        }

        /** Keeps one chunk per region loaded with a plugin ticket, so each becomes (and stays) a region. */
        private void loadRegions() {
            this.stage = "regions";
            final List<CompletableFuture<?>> loads = new ArrayList<>();
            for (final int[] chunk : CHUNKS) {
                this.world.addPluginChunkTicket(chunk[0], chunk[1], this.plugin);
                loads.add(this.world.getChunkAtAsync(chunk[0], chunk[1], true));
            }
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((done, failure) -> {
                if (failure != null) {
                    this.fail("chunk load failed: " + failure);
                    return;
                }
                this.onGlobal(this::populate);
            });
        }

        /** Counts the world's regions, then queues the far-future work on each one's own thread. */
        private void populate() {
            final Set<Long> ids = new HashSet<>();
            final WorldRegionizer regionizer = this.world.getRegionizer();
            regionizer.computeForAllChunkRegionsUnsynchronized(region -> ids.add(region.getId()));
            if (ids.size() < 2) {
                marker("REGIONS_WRONG", "world=" + this.name + " regions=" + ids.size() + " (need at least 2)");
                this.fail("the world has fewer than 2 regions");
                return;
            }
            marker("WORLD_OK", "world=" + this.name + " regions=" + ids.size());
            this.stage = "populate";
            final AtomicInteger remaining = new AtomicInteger(CHUNKS.length);
            for (final int[] chunk : CHUNKS) {
                Bukkit.getRegionScheduler().run(this.plugin, this.world, chunk[0], chunk[1], task -> {
                    try {
                        this.populateRegion(chunk[0], chunk[1]);
                    } catch (final Throwable failed) {
                        this.bodyFailures.add("populate " + chunk[0] + "," + chunk[1] + ": " + failed);
                    } finally {
                        if (remaining.decrementAndGet() == 0) this.onGlobal(this::settleBeforeUnload);
                    }
                });
            }
        }

        /** Runs on the region's thread: summons entities and schedules the far-future work. */
        private void populateRegion(final int chunkX, final int chunkZ) {
            final double x = (chunkX << 4) + 8.5;
            final double z = (chunkZ << 4) + 8.5;
            for (int i = 0; i < STANDS_PER_REGION; i++) {
                final ArmorStand stand = this.world.spawn(new org.bukkit.Location(this.world, x + i, 64.0, z), ArmorStand.class,
                    spawned -> {
                        spawned.setGravity(false);
                        spawned.setInvisible(true);
                        spawned.setMarker(true);
                    });
                this.stands.add(stand);
                final var scheduler = stand.getScheduler();
                final int delayed = this.ledger.register(UnloadLedger.Kind.ENTITY_DELAYED);
                scheduler.runDelayed(this.plugin, t -> this.body(delayed, stand, 0, 0), () -> this.ledger.retired(delayed), FAR);
                final int repeating = this.ledger.register(UnloadLedger.Kind.ENTITY_REPEATING);
                scheduler.runAtFixedRate(this.plugin, t -> this.body(repeating, stand, 0, 0), () -> this.ledger.retired(repeating), FAR, 20L);
            }
            final var regions = Bukkit.getRegionScheduler();
            for (int i = 0; i < 2; i++) {
                final int delayed = this.ledger.register(UnloadLedger.Kind.REGION_DELAYED);
                this.track(delayed, regions.runDelayed(this.plugin, this.world, chunkX, chunkZ, t -> this.body(delayed, null, chunkX, chunkZ), FAR));
            }
            final int repeating = this.ledger.register(UnloadLedger.Kind.REGION_REPEATING);
            this.track(repeating, regions.runAtFixedRate(this.plugin, this.world, chunkX, chunkZ, t -> this.body(repeating, null, chunkX, chunkZ), FAR, 20L));
        }

        private void track(final int id, final ScheduledTask task) {
            this.regionTaskIds.add(id);
            this.regionTasks.add(task);
        }

        /**
         * Task bodies: count themselves, then do one ownership-checked access (an entity write or a
         * block read) so a body running where it does not own its target throws or is logged by the
         * region guard instead of passing silently. Any exception is recorded, not swallowed.
         */
        private void body(final int id, final org.bukkit.entity.Entity entity, final int chunkX, final int chunkZ) {
            try {
                this.ledger.ran(id);
                final boolean owned;
                if (entity != null) {
                    owned = Bukkit.isOwnedByCurrentRegion(entity);
                    if (owned) entity.setCustomName(PREFIX + id);
                } else {
                    owned = Bukkit.isOwnedByCurrentRegion(this.world, chunkX, chunkZ);
                    if (owned) this.world.getBlockAt(chunkX << 4, 64, chunkZ << 4).getType();
                }
                if (!owned) {
                    (this.ledger.unloaded() ? this.notOwnedAfter : this.ledger.started() ? this.notOwnedDuring : this.notOwnedBefore)
                        .add(id + "@" + Thread.currentThread().getName());
                }
            } catch (final Throwable failed) {
                this.bodyFailures.add("body " + id + ": " + failed);
            }
        }

        private void settleBeforeUnload() {
            // A few ticks so the far-future registrations are in the schedulers before the unload.
            Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, t -> this.unloadWhilePending(), 10L);
        }

        /**
         * On the global tick: every region gets one task that, on its own thread, queues more work for
         * its next tick and then holds the thread until the unload was requested. The first of them
         * asks the global tick to unload. The region's next tick would run what it queued, but the
         * world is marked unloading first, so that work is still queued when the engine drains it:
         * the cancel-only path. The hold is bounded (2 s plus 150 ms) and is the only place the
         * fixture blocks a region thread, on purpose.
         */
        private void unloadWhilePending() {
            this.stage = "unload";
            boolean first = true;
            for (final int[] chunk : CHUNKS) {
                final boolean requestsUnload = first;
                first = false;
                Bukkit.getRegionScheduler().execute(this.plugin, this.world, chunk[0], chunk[1],
                    () -> this.queueThenHold(chunk[0], chunk[1], requestsUnload));
            }
        }

        /** On a region's thread: queue work for the next tick, then block that thread until the unload was requested. */
        private void queueThenHold(final int chunkX, final int chunkZ, final boolean requestsUnload) {
            try {
                for (int i = 0; i < 2; i++) {
                    final int id = this.ledger.register(UnloadLedger.Kind.REGION_EXECUTE);
                    Bukkit.getRegionScheduler().execute(this.plugin, this.world, chunkX, chunkZ, () -> this.body(id, null, chunkX, chunkZ));
                }
                final int queued = this.ledger.register(UnloadLedger.Kind.REGION_QUEUED);
                this.track(queued, Bukkit.getRegionScheduler().run(this.plugin, this.world, chunkX, chunkZ,
                    t -> this.body(queued, null, chunkX, chunkZ)));
                for (final ArmorStand stand : this.stands) {
                    if (!Bukkit.isOwnedByCurrentRegion(stand)) continue;
                    final int id = this.ledger.register(UnloadLedger.Kind.ENTITY_QUEUED);
                    stand.getScheduler().run(this.plugin, t -> this.body(id, stand, 0, 0), () -> this.ledger.retired(id));
                }
                if (requestsUnload) Bukkit.getGlobalRegionScheduler().run(this.plugin, t -> this.issueUnload());
                // Bounded: the unload is requested within a global tick; never hold a region for long.
                final long limit = System.nanoTime() + HOLD_LIMIT_NANOS;
                while (!this.ledger.started() && System.nanoTime() < limit) Thread.sleep(5L);
                Thread.sleep(HOLD_AFTER_REQUEST_MILLIS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (final Throwable failed) {
                this.bodyFailures.add("queueThenHold " + chunkX + "," + chunkZ + ": " + failed);
            }
        }

        /** On the global tick: ask the engine to unload the world while the regions hold their queued work. */
        private void issueUnload() {
            try {
                if (!this.unloadIssued.compareAndSet(false, true)) return;
                marker("QUEUED_OK", "world=" + this.name + " tasks=" + this.ledger.size() + " regionDelayed="
                    + this.ledger.count(UnloadLedger.Kind.REGION_DELAYED) + " regionRepeating="
                    + this.ledger.count(UnloadLedger.Kind.REGION_REPEATING) + " regionQueued="
                    + this.ledger.count(UnloadLedger.Kind.REGION_QUEUED) + " regionExecute="
                    + this.ledger.count(UnloadLedger.Kind.REGION_EXECUTE) + " entityDelayed="
                    + this.ledger.count(UnloadLedger.Kind.ENTITY_DELAYED) + " entityRepeating="
                    + this.ledger.count(UnloadLedger.Kind.ENTITY_REPEATING) + " entityQueued="
                    + this.ledger.count(UnloadLedger.Kind.ENTITY_QUEUED));
                this.ledger.unloadStarted();
                Bukkit.getServer().unloadWorldAsync(this.world, true, result -> {
                    this.ledger.unloadCompleted();
                    if (result != WorldUnloadResult.SUCCESS) {
                        marker("UNLOAD_WRONG", "world=" + this.name + " result=" + result);
                        this.fail("the unload ended with " + result);
                        return;
                    }
                    marker("UNLOAD_OK", "world=" + this.name + " result=" + result + " thread=" + Thread.currentThread().getName());
                    this.onGlobal(this::afterUnload);
                });
            } catch (final Throwable failed) {
                this.fail("requesting the unload threw " + failed);
            }
        }

        /** The unload completed: read what the region scheduler did, then watch for stragglers. */
        private void afterUnload() {
            this.stage = "after-unload";
            for (int i = 0; i < this.regionTasks.size(); i++) {
                final ScheduledTask.ExecutionState state = this.regionTasks.get(i).getExecutionState();
                if (state == ScheduledTask.ExecutionState.CANCELLED) this.ledger.cancelled(this.regionTaskIds.get(i));
            }
            final UnloadLedger.Report first = this.ledger.report();
            if (!first.ok()) {
                marker("ACCOUNTING_WRONG", "world=" + this.name + " " + first.summary() + " first=" + first.wrong().getFirst());
                first.wrong().forEach(w -> Bukkit.getLogger().warning(PREFIX + "ACCOUNTING_WRONG detail: " + w));
                this.fail("accounting failed");
                return;
            }
            marker("ACCOUNTED_OK", "world=" + this.name + " " + first.summary());
            if (first.farFutureRanAtUnload() > 0) {
                // Not a failure: FoliaEntityScheduler runs the task body (not the retired callback)
                // for an entity that is not removed, and the unload retires schedulers without
                // removing entities. Reported so the behaviour is visible, not hidden.
                marker("NOTE_DRAIN_RAN_BODIES", "world=" + this.name + " bodiesRunByTheUnload=" + first.farFutureRanAtUnload()
                    + " retiredCallbacks=" + first.retired() + " notOwnedDuringUnload=" + this.notOwnedDuring);
            }
            // Nothing may run now. Wait on the global tick, then judge again.
            Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, t -> this.afterQuiet(first), QUIET_TICKS);
        }

        private void afterQuiet(final UnloadLedger.Report first) {
            this.stage = "quiet";
            final UnloadLedger.Report second = this.ledger.report();
            if (!second.ok() || second.ranAfterUnload() != 0 || second.ran() != first.ran() || second.retired() != first.retired()) {
                marker("QUIET_WRONG", "world=" + this.name + " before{" + first.summary() + "} after{" + second.summary() + "}");
                second.wrong().forEach(w -> Bukkit.getLogger().warning(PREFIX + "QUIET_WRONG detail: " + w));
                this.fail("work ran after the unload completed");
                return;
            }
            if (!this.notOwnedBefore.isEmpty() || !this.notOwnedAfter.isEmpty()) {
                marker("OWNERSHIP_WRONG", "world=" + this.name + " before=" + this.notOwnedBefore + " after=" + this.notOwnedAfter);
                this.fail("a task body ran where it did not own its target");
                return;
            }
            if (!this.bodyFailures.isEmpty()) {
                marker("EXCEPTION_WRONG", "world=" + this.name + " " + this.bodyFailures);
                this.fail("a task body threw");
                return;
            }
            if (Bukkit.getWorld(this.name) != null || Bukkit.getWorlds().stream().anyMatch(w -> w.getName().equals(this.name))) {
                marker("RELEASED_WRONG", "world=" + this.name + " is still listed by Bukkit");
                this.fail("the world is still loaded");
                return;
            }
            marker("QUIET_OK", "world=" + this.name + " ticks=" + QUIET_TICKS + " executeNotRun=" + second.executeNotRun());
            Bukkit.getAsyncScheduler().runNow(this.plugin, t -> this.checkFolder(second));
        }

        /** Off the region threads: open files under the world folder, then delete it. */
        private void checkFolder(final UnloadLedger.Report report) {
            final Path folder = Bukkit.getWorldContainer().toPath().resolve(this.name);
            final OptionalInt open = OpenFiles.under(folder);
            if (open.isPresent() && open.getAsInt() > 0) {
                marker("RELEASED_WRONG", "world=" + this.name + " open files under " + folder + ": " + open.getAsInt());
                this.fail("the unloaded world's folder still has open files");
                return;
            }
            final AuroraWorlds worlds = Bukkit.getServicesManager().load(AuroraWorlds.class);
            worlds.delete(this.name).whenComplete((done, failure) -> {
                if (failure != null || Files.exists(folder)) {
                    marker("RELEASED_WRONG", "world=" + this.name + " delete " + (failure != null ? "failed: " + failure : "left " + folder));
                    this.fail("the unloaded world could not be deleted");
                    return;
                }
                final String fds = open.isPresent() ? "0" : "unchecked";
                marker("RELEASED_OK", "world=" + this.name + " openFiles=" + fds + " folderDeleted=true");
                if (this.finished.compareAndSet(false, true)) {
                    marker("OK", "world=" + this.name + " " + report.summary() + " openFiles=" + fds);
                }
            });
        }

        private void fail(final String why) {
            if (!this.finished.compareAndSet(false, true)) return;
            Bukkit.getLogger().log(Level.SEVERE, PREFIX + "FAILED world=" + this.name + " stage=" + this.stage + " " + why
                + " ledger{" + this.ledger.report().summary().toLowerCase(Locale.ROOT) + "}");
        }
    }
}
