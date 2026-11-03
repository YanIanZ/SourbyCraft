package dev.iyanz.sourbycraft.legacytest;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Exercises the Bukkit scheduler the way a pre-Folia plugin does, and logs one marker per outcome
 * so CI can assert what the Aurora Bridge did with each call.
 */
public final class LegacyBridgePlugin extends JavaPlugin {

    /** Incremented by every onEnable of this instance; tasks remember the generation they belong to. */
    private final AtomicInteger generation = new AtomicInteger();
    /** Bodies that ran after their generation ended (a task that survived disable). */
    private final AtomicInteger staleRuns = new AtomicInteger();
    /** Console-driven probes for the bridge's LIVE limits and reload (manual/agent runs only). */
    private final LimitProbes limitProbes = new LimitProbes(this);

    @Override
    public void onEnable() {
        final int gen = this.generation.incrementAndGet();
        this.getLogger().info("LEGACY_BRIDGE_ENABLED gen=" + gen);
        if (gen > 1) reenableChecks(gen);
        final BukkitScheduler scheduler = this.getServer().getScheduler();

        scheduler.runTask(this, () -> this.getLogger().info(
            "LEGACY_BRIDGE_SYNC_OK thread=" + Thread.currentThread().getName()));

        scheduler.runTaskAsynchronously(this, () -> this.getLogger().info(
            "LEGACY_BRIDGE_ASYNC_OK thread=" + Thread.currentThread().getName()));

        // A repeating task that cancels itself through BukkitTask#cancel, which must reach the
        // bridge rather than Folia's unsupported CraftScheduler path.
        final AtomicInteger runs = new AtomicInteger();
        final BukkitTask[] timer = new BukkitTask[1];
        timer[0] = scheduler.runTaskTimer(this, () -> {
            if (runs.incrementAndGet() == 3) {
                this.getLogger().info("LEGACY_BRIDGE_TIMER_OK runs=3");
                timer[0].cancel();
            } else if (runs.get() > 3) {
                this.getLogger().severe("LEGACY_BRIDGE_TIMER_RAN_AFTER_CANCEL runs=" + runs.get());
            }
        }, 1L, 1L);

        // isQueued must see a bridged task (plugins use it to avoid double-scheduling).
        final BukkitTask later = scheduler.runTaskLater(this, () -> { }, 40L);
        this.getLogger().info(scheduler.isQueued(later.getTaskId())
            ? "LEGACY_BRIDGE_QUEUED_OK" : "LEGACY_BRIDGE_QUEUED_MISSING");
        this.getLogger().info(scheduler.getPendingTasks().stream().anyMatch(t -> t.getTaskId() == later.getTaskId())
            ? "LEGACY_BRIDGE_PENDING_OK" : "LEGACY_BRIDGE_PENDING_MISSING");

        // A legacy runTask called from region context, the way an event handler or a player
        // command calls it. The chunk-load callback runs on the region owning the chunk; with
        // aurora.bridge.sync-route = "caller-region" the task runs on that region and may read
        // the block there.
        final org.bukkit.World world = this.getServer().getWorlds().get(0);
        final Completion regionSync = new Completion(1);
        world.getChunkAtAsync(0, 0).whenComplete((chunk, failure) -> {
            if (failure != null) {
                regionSync.fail(failure);
                return;
            }
            regionSync.step(() -> {
                world.addPluginChunkTicket(0, 0, this);
                AsyncChunkProbe.run(this, world);
                scheduler.runTask(this, () -> regionSync.last(() -> {
                    try {
                        final String type = world.getBlockAt(8, 0, 8).getType().name();
                        this.getLogger().info("LEGACY_BRIDGE_REGION_SYNC_OK thread=" + Thread.currentThread().getName()
                            + " block=" + type);
                    } catch (final RuntimeException refused) {
                        this.getLogger().info("LEGACY_BRIDGE_REGION_SYNC_REFUSED " + refused.getClass().getSimpleName()
                            + " thread=" + Thread.currentThread().getName());
                        throw refused;
                    }
                }));
            });
        });

        // Every chunk-gated probe that hops through the global region (a GlobalRegionScheduler task, or a
        // Bukkit sync task the bridge backs on it) reports when its last such hop has run, so the
        // GlobalRegionScheduler#cancelTasks probe cannot cancel it however slowly its chunk loads.
        final Map<String, CompletableFuture<Void>> globalHops = new LinkedHashMap<>();
        globalHops.put("region-sync", regionSync.future());
        regionTask();
        globalHops.put("entity", entityTask());
        globalHops.put("context", new ContextProbes(this).run());
        globalHops.put("relaxed", new RelaxedProbes(this).run());
        globalScheduler(globalHops);

        // World access from a legacy "sync" task scheduled outside any region (onEnable). The
        // bridge runs it on the global region, which owns no chunks; whatever the base does is
        // logged, and a thrown ownership violation is what the bridge counts toward quarantine.
        scheduler.runTaskLater(this, () -> {
            try {
                final String type = this.getServer().getWorlds().get(0).getBlockAt(0, 64, 0).getType().name();
                this.getLogger().info("LEGACY_BRIDGE_WORLD_ACCESS_ALLOWED block=" + type);
            } catch (final RuntimeException refused) {
                this.getLogger().info("LEGACY_BRIDGE_WORLD_ACCESS_REFUSED " + refused.getClass().getSimpleName());
                throw refused;
            }
        }, 5L);
    }

    /**
     * {@code RegionTask.at} scheduled from onEnable, which ticks no region: the explicit target must
     * win over the global fallback, so the body runs on the region owning chunk (-2, -3) and may read
     * a block there. Moving the Location afterwards must not move the task.
     */
    private void regionTask() {
        final org.bukkit.World world = this.getServer().getWorlds().get(0);
        final int chunkX = -2;
        final int chunkZ = -3;
        final org.bukkit.Location target = new org.bukkit.Location(world, -24.5, 64, -40.5);
        final dev.iyanz.sourbycraft.api.scheduler.RegionTask task = dev.iyanz.sourbycraft.api.scheduler.RegionTask.at(target, () -> {
            final boolean owned = Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
            try {
                final String type = world.getBlockAt(-25, 64, -41).getType().name();
                check(owned && !Bukkit.isGlobalTickThread(), "LEGACY_REGION_TASK",
                    "thread=" + Thread.currentThread().getName() + " owned=" + owned + " block=" + type);
            } catch (final RuntimeException refused) {
                this.getLogger().severe("LEGACY_REGION_TASK_WRONG " + refused.getClass().getSimpleName()
                    + " owned=" + owned + " thread=" + Thread.currentThread().getName());
                throw refused;
            }
        });
        target.setX(4000);
        target.setZ(4000);
        if (task.chunkX() != chunkX || task.chunkZ() != chunkZ) {
            this.getLogger().severe("LEGACY_REGION_TASK_WRONG snapshot=" + task.chunkX() + "," + task.chunkZ());
        }
        this.getServer().getScheduler().runTaskLater(this, task, 2L);
    }

    /**
     * {@code EntityTask}: an entity spawned from a region callback, then a Bukkit sync task naming it,
     * scheduled from the global region. The body must run on the region owning the entity and may
     * read it. A second, delayed {@code EntityTask} is scheduled from the global region and the
     * entity is removed before it is due: Folia's entity scheduler retires it, the bridge cancels it,
     * and its body never runs. The plugin cannot read the bridge's own counters, so
     * {@code LEGACY_ENTITY_RETIRED_OK} means "the body did not run within 3 s, the task reports
     * cancelled and is no longer queued"; the rejected count is visible in {@code /plugins}.
     *
     * <p>The returned future completes once the retired probe's global hop has run: after it, the chain lives
     * on the entity and async schedulers only, which {@code GlobalRegionScheduler#cancelTasks} does not touch.
     */
    private CompletableFuture<Void> entityTask() {
        final Completion done = new Completion(1);
        final org.bukkit.World world = this.getServer().getWorlds().get(0);
        final BukkitScheduler scheduler = this.getServer().getScheduler();
        final int chunkX = 5;
        final int chunkZ = 5;
        world.getChunkAtAsync(chunkX, chunkZ).whenComplete((chunk, failure) -> {
            if (failure != null) {
                done.fail(failure);
                return;
            }
            done.step(() -> {
                world.addPluginChunkTicket(chunkX, chunkZ, this);
                final org.bukkit.Location spot = new org.bukkit.Location(world, 88.5, 100, 88.5);
                final org.bukkit.entity.ArmorStand stand = world.spawn(spot, org.bukkit.entity.ArmorStand.class, created -> {
                    created.setGravity(false);
                    created.setPersistent(false);
                });
                Bukkit.getGlobalRegionScheduler().run(this, global -> done.step(() -> scheduler.runTask(this,
                    dev.iyanz.sourbycraft.api.scheduler.EntityTask.of(stand, () -> done.step(() -> {
                        final boolean owned = Bukkit.isOwnedByCurrentRegion(stand);
                        try {
                            final String read = stand.getType().name() + "@" + stand.getLocation().getBlockX()
                                + "," + stand.getLocation().getBlockZ();
                            check(owned && !Bukkit.isGlobalTickThread() && stand.isValid(), "LEGACY_ENTITY_TASK",
                                "thread=" + Thread.currentThread().getName() + " owned=" + owned + " entity=" + read);
                        } catch (final RuntimeException refused) {
                            this.getLogger().severe("LEGACY_ENTITY_TASK_WRONG " + refused.getClass().getSimpleName()
                                + " owned=" + owned + " thread=" + Thread.currentThread().getName());
                            throw refused;
                        }
                        retiredEntityTask(stand, done);
                    })))));
            });
        });
        return done.future();
    }

    private void retiredEntityTask(final org.bukkit.entity.Entity entity, final Completion done) {
        final BukkitScheduler scheduler = this.getServer().getScheduler();
        final java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        Bukkit.getGlobalRegionScheduler().run(this, global -> done.last(() -> {
            final BukkitTask doomed = scheduler.runTaskLater(this,
                dev.iyanz.sourbycraft.api.scheduler.EntityTask.of(entity, () -> {
                    ran.set(true);
                    this.getLogger().severe("LEGACY_ENTITY_RETIRED_WRONG body ran after its entity was removed");
                }), 40L);
            // Removed on the entity's own region, well before the 40-tick delay.
            entity.getScheduler().run(this, removal -> entity.remove(), null);
            scheduler.runTaskLaterAsynchronously(this, () -> check(!ran.get() && doomed.isCancelled()
                    && !scheduler.isQueued(doomed.getTaskId()), "LEGACY_ENTITY_RETIRED",
                "ran=" + ran.get() + " cancelled=" + doomed.isCancelled()
                    + " queued=" + scheduler.isQueued(doomed.getTaskId())), 60L);
        }));
    }

    void check(final boolean ok, final String marker, final String detail) {
        if (ok) this.getLogger().info(marker + "_OK " + detail);
        else this.getLogger().severe(marker + "_WRONG " + detail);
    }

    /**
     * Completion of a probe whose bodies are chained through schedulers: done once each of its {@code parts}
     * terminal bodies has run, on every path, or failed as soon as a step that schedules the next body throws.
     */
    static final class Completion {
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final AtomicInteger pending;

        Completion(final int parts) {
            this.pending = new AtomicInteger(parts);
        }

        CompletableFuture<Void> future() {
            return this.future;
        }

        /** One terminal body ended. */
        void arrive() {
            if (this.pending.decrementAndGet() == 0) this.future.complete(null);
        }

        /** The chain stopped before its terminal bodies were scheduled. */
        void fail(final Throwable failure) {
            this.future.completeExceptionally(failure);
        }

        /** Runs a terminal body and arrives however it ends; what it throws propagates unchanged. */
        void last(final Runnable body) {
            try {
                body.run();
            } finally {
                arrive();
            }
        }

        /** Runs a body that schedules the next one; a throw fails the probe and propagates unchanged. */
        void step(final Runnable body) {
            try {
                body.run();
            } catch (final Throwable failure) {
                fail(failure);
                throw failure;
            }
        }
    }

    /**
     * Folia's GlobalRegionScheduler from a legacy plugin: the LightingLuminol port routes it
     * through the bridge, and it must still run on the global tick, end in the states Folia
     * documents, and cancel only what it should.
     *
     * @param globalHops chunk-gated probes started from onEnable, each completing once its last global hop ran
     */
    private void globalScheduler(final Map<String, CompletableFuture<Void>> globalHops) {
        final GlobalRegionScheduler global = Bukkit.getGlobalRegionScheduler();

        global.execute(this, () -> check(Bukkit.isGlobalTickThread(), "LEGACY_GLOBAL_EXECUTE",
            "thread=" + Thread.currentThread().getName()));
        global.run(this, task -> check(Bukkit.isGlobalTickThread(), "LEGACY_GLOBAL_RUN",
            "thread=" + Thread.currentThread().getName()));

        // getCurrentTick is region-bound on this server; ten ticks are about 500 ms of wall time.
        final long scheduledAt = System.nanoTime();
        global.runDelayed(this, task -> {
            final long millis = (System.nanoTime() - scheduledAt) / 1_000_000;
            check(Bukkit.isGlobalTickThread() && millis >= 400, "LEGACY_GLOBAL_DELAYED", "ms=" + millis);
        }, 10L);

        // A repeater that cancels itself while running: NEXT_RUNS_CANCELLED, CANCELLED_RUNNING during
        // the body, and no fourth run.
        final AtomicInteger runs = new AtomicInteger();
        global.runAtFixedRate(this, task -> {
            final int run = runs.incrementAndGet();
            if (run == 3) {
                final ScheduledTask.CancelledState cancelled = task.cancel();
                check(cancelled == ScheduledTask.CancelledState.NEXT_RUNS_CANCELLED
                        && task.getExecutionState() == ScheduledTask.ExecutionState.CANCELLED_RUNNING
                        && task.cancel() == ScheduledTask.CancelledState.NEXT_RUNS_CANCELLED_ALREADY,
                    "LEGACY_GLOBAL_REPEATER_SELF_CANCEL", "cancel=" + cancelled + " state=" + task.getExecutionState());
                global.runDelayed(this, after -> check(task.getExecutionState() == ScheduledTask.ExecutionState.CANCELLED
                    && runs.get() == 3, "LEGACY_GLOBAL_REPEATER_STOPPED", "runs=" + runs.get() + " state="
                    + task.getExecutionState()), 10L);
            } else if (run > 3) {
                this.getLogger().severe("LEGACY_GLOBAL_REPEATER_RAN_AFTER_CANCEL_WRONG runs=" + run);
            }
        }, 1L, 1L);

        // Idle cancellation: CANCELLED_BY_CALLER, then CANCELLED_ALREADY, and the body never runs.
        final AtomicInteger idleRuns = new AtomicInteger();
        final ScheduledTask idle = global.runDelayed(this, task -> idleRuns.incrementAndGet(), 20L);
        final ScheduledTask.CancelledState first = idle.cancel();
        final ScheduledTask.CancelledState second = idle.cancel();
        global.runDelayed(this, task -> check(first == ScheduledTask.CancelledState.CANCELLED_BY_CALLER
                && second == ScheduledTask.CancelledState.CANCELLED_ALREADY && idleRuns.get() == 0
                && idle.getExecutionState() == ScheduledTask.ExecutionState.CANCELLED,
            "LEGACY_GLOBAL_IDLE_CANCEL", "first=" + first + " second=" + second + " runs=" + idleRuns.get()), 40L);

        // A one-shot cancelled while running reports RUNNING; once finished, ALREADY_EXECUTED.
        final AtomicReference<ScheduledTask.CancelledState> whileRunning = new AtomicReference<>();
        final ScheduledTask oneShot = global.runDelayed(this, task -> whileRunning.set(task.cancel()), 2L);
        global.runDelayed(this, task -> check(whileRunning.get() == ScheduledTask.CancelledState.RUNNING
                && oneShot.cancel() == ScheduledTask.CancelledState.ALREADY_EXECUTED
                && oneShot.getExecutionState() == ScheduledTask.ExecutionState.FINISHED,
            "LEGACY_GLOBAL_ONESHOT_STATES", "whileRunning=" + whileRunning.get() + " state=" + oneShot.getExecutionState()), 30L);

        // Called from a region, a global request still runs on the global tick (sync-route does
        // not apply to explicit global requests).
        final org.bukkit.World world = this.getServer().getWorlds().get(0);
        final Completion fromRegion = new Completion(1);
        Bukkit.getRegionScheduler().execute(this, world, 0, 0, () -> fromRegion.step(() -> global.execute(this, () -> fromRegion.last(() ->
            check(Bukkit.isGlobalTickThread(), "LEGACY_GLOBAL_FROM_REGION", "thread=" + Thread.currentThread().getName())))));
        final Map<String, CompletableFuture<Void>> hops = new LinkedHashMap<>(globalHops);
        hops.put("from-region", fromRegion.future());

        // An exception in a global repeater's body is the plugin's failure, not a violation: Folia
        // keeps the repeater, so it runs again and can still cancel itself. The bridge logs the
        // deliberate exception ("generated an exception while executing task").
        final AtomicInteger throwingRuns = new AtomicInteger();
        global.runAtFixedRate(this, task -> {
            final int run = throwingRuns.incrementAndGet();
            if (run == 1) throw new IllegalArgumentException("LEGACY_DELIBERATE exception in a global repeater body");
            if (run == 2) {
                task.cancel();
                global.runDelayed(this, after -> check(throwingRuns.get() == 2
                        && task.getExecutionState() == ScheduledTask.ExecutionState.CANCELLED,
                    "LEGACY_GLOBAL_EXCEPTION", "runs=" + throwingRuns.get() + " state=" + task.getExecutionState()), 5L);
            }
        }, 1L, 1L);

        // cancelTasks cancels every pending global task of this plugin (and the Bukkit sync tasks the bridge
        // backs on the global region), so it starts only once every chunk-gated probe's last global hop has run;
        // the probes above are not chunk-gated and end within 40 ticks, before its cancel at +50.
        final CompletableFuture<?>[] waits = hops.values().toArray(new CompletableFuture<?>[0]);
        CompletableFuture.allOf(waits).orTimeout(20, TimeUnit.SECONDS).whenComplete((ignored, failure) ->
            global.run(this, task -> cancelTasksProbe(failure, hops)));
    }

    /**
     * GlobalRegionScheduler#cancelTasks removes this plugin's global tasks only; its async Bukkit task survives.
     * Every offset is relative to this call. When the probes it waited for did not finish, that is logged as
     * {@code _WRONG} by name and the probe still runs.
     */
    private void cancelTasksProbe(final Throwable waitFailure, final Map<String, CompletableFuture<Void>> waitedFor) {
        if (waitFailure != null) {
            final Throwable cause = waitFailure instanceof CompletionException && waitFailure.getCause() != null
                ? waitFailure.getCause() : waitFailure;
            final List<String> unfinished = new ArrayList<>();
            waitedFor.forEach((name, probe) -> {
                if (!probe.isDone() || probe.isCompletedExceptionally()) unfinished.add(name);
            });
            this.getLogger().severe("LEGACY_GLOBAL_CANCEL_TASKS_WRONG relaxed/context probes did not finish: " + cause
                + " unfinished=" + unfinished);
        }
        final GlobalRegionScheduler global = Bukkit.getGlobalRegionScheduler();
        final AtomicInteger cancelledGlobalRuns = new AtomicInteger();
        global.runDelayed(this, task -> cancelledGlobalRuns.incrementAndGet(), 60L);
        global.runAtFixedRate(this, task -> cancelledGlobalRuns.incrementAndGet(), 60L, 5L);
        final AtomicInteger asyncRuns = new AtomicInteger();
        this.getServer().getScheduler().runTaskLaterAsynchronously(this, asyncRuns::incrementAndGet, 70L);
        global.runDelayed(this, task -> {
            global.cancelTasks(this);
            // This check is scheduled through the async scheduler, which cancelTasks does not touch.
            this.getServer().getScheduler().runTaskLaterAsynchronously(this, () -> check(cancelledGlobalRuns.get() == 0
                    && asyncRuns.get() == 1, "LEGACY_GLOBAL_CANCEL_TASKS",
                "globalRuns=" + cancelledGlobalRuns.get() + " asyncRuns=" + asyncRuns.get()), 60L);
        }, 50L);
    }

    /**
     * {@code legacybridge reenable} (console): disables this plugin and enables the same instance
     * again, the way a plugin manager does. Long-lived global, Bukkit sync and Bukkit async timers of
     * the current generation are left running into the disable; none of them may run afterwards.
     * The second onEnable then repeats every marker above.
     */
    @Override
    public boolean onCommand(final org.bukkit.command.CommandSender sender, final org.bukkit.command.Command command,
                             final String label, final String[] args) {
        if (args.length == 1 && "unload".equals(args[0])) {
            unloadProbe();
            return true;
        }
        if (args.length >= 1 && LimitProbes.handles(args[0])) {
            return this.limitProbes.command(args);
        }
        if (args.length != 1 || !"reenable".equals(args[0])) return false;
        final int gen = this.generation.get();
        final Runnable stale = () -> {
            if (this.generation.get() != gen || !this.isEnabled()) {
                this.staleRuns.incrementAndGet();
                this.getLogger().severe("LEGACY_REENABLE_STALE_RAN_WRONG gen=" + gen + " now=" + this.generation.get()
                    + " thread=" + Thread.currentThread().getName());
            }
        };
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> stale.run(), 5L, 5L);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> stale.run(), 40L);
        this.getServer().getScheduler().runTaskTimer(this, stale, 5L, 5L);
        this.getServer().getScheduler().runTaskTimerAsynchronously(this, stale, 5L, 5L);
        this.getServer().getScheduler().runTaskLater(this, stale, 40L);
        this.getLogger().info("LEGACY_REENABLE_START gen=" + gen + " thread=" + Thread.currentThread().getName());
        this.getServer().getPluginManager().disablePlugin(this);
        this.getLogger().info("LEGACY_REENABLE_DISABLED enabled=" + this.isEnabled());
        this.getServer().getPluginManager().enablePlugin(this);
        return true;
    }

    /**
     * {@code legacybridge unload} (console): legacy sync tasks anchored (RegionTask) in a world that
     * is then unloaded. The engine cancels that world's region scheduler tasks without running them;
     * the bridge must release its entries too, so the plugin sees them cancelled and not queued.
     */
    private void unloadProbe() {
        final dev.iyanz.sourbycraft.api.world.AuroraWorlds worlds =
            Bukkit.getServicesManager().load(dev.iyanz.sourbycraft.api.world.AuroraWorlds.class);
        if (worlds == null) {
            this.getLogger().severe("LEGACY_UNLOAD_WRONG no AuroraWorlds service");
            return;
        }
        final String name = "legacy_unload_" + Long.toHexString(System.nanoTime());
        final BukkitScheduler scheduler = this.getServer().getScheduler();
        final AtomicInteger bodyRuns = new AtomicInteger();
        worlds.create(new org.bukkit.WorldCreator(name), "void", false).whenComplete((world, failure) -> {
            if (failure != null) {
                this.getLogger().severe("LEGACY_UNLOAD_WRONG create failed: " + failure);
                return;
            }
            Bukkit.getGlobalRegionScheduler().run(this, t -> {
                world.addPluginChunkTicket(0, 0, this);
                world.getChunkAtAsync(0, 0, true).whenComplete((chunk, loadFailure) -> Bukkit.getGlobalRegionScheduler().run(this, t2 -> {
                    final org.bukkit.Location spot = new org.bukkit.Location(world, 8, 64, 8);
                    final BukkitTask oneShot = scheduler.runTaskLater(this,
                        dev.iyanz.sourbycraft.api.scheduler.RegionTask.at(spot, bodyRuns::incrementAndGet), 6000L);
                    final BukkitTask timer = scheduler.runTaskTimer(this,
                        dev.iyanz.sourbycraft.api.scheduler.RegionTask.at(spot, bodyRuns::incrementAndGet), 6000L, 20L);
                    this.getLogger().info("LEGACY_UNLOAD_QUEUED world=" + name + " queued=" + scheduler.isQueued(oneShot.getTaskId())
                        + "," + scheduler.isQueued(timer.getTaskId()));
                    // PR #18 review: tasks anchored in the world submitted while it unloads (an async thread naming
                    // a location there) used to miss the unload's one-time release and stay queued for good.
                    final java.util.concurrent.atomic.AtomicBoolean unloadDone = new java.util.concurrent.atomic.AtomicBoolean();
                    final java.util.List<BukkitTask> racing = new java.util.concurrent.CopyOnWriteArrayList<>();
                    final Thread racer = new Thread(() -> {
                        while (!unloadDone.get() && racing.size() < 2000) {
                            racing.add(scheduler.runTaskLater(this,
                                dev.iyanz.sourbycraft.api.scheduler.RegionTask.at(spot, bodyRuns::incrementAndGet), 6000L));
                            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
                        }
                    }, "LegacyBridgeTest unload racer");
                    racer.setDaemon(true);
                    racer.start();
                    Bukkit.getServer().unloadWorldAsync(world, false, result -> Bukkit.getGlobalRegionScheduler().runDelayed(this, t3 -> {
                        unloadDone.set(true);
                        try {
                            racer.join(1000L);
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        final BukkitTask late = scheduler.runTaskLater(this,
                            dev.iyanz.sourbycraft.api.scheduler.RegionTask.at(spot, bodyRuns::incrementAndGet), 1L);
                        final long racingQueued = racing.stream().filter(r -> scheduler.isQueued(r.getTaskId()) || !r.isCancelled()).count();
                        check(late.isCancelled() && !scheduler.isQueued(late.getTaskId()) && racingQueued == 0 && !racing.isEmpty(),
                            "LEGACY_UNLOAD_LATE_REFUSED", "late cancelled=" + late.isCancelled() + " queued="
                                + scheduler.isQueued(late.getTaskId()) + " racing=" + racing.size() + " racingStillQueued=" + racingQueued);
                        final boolean released = !scheduler.isQueued(oneShot.getTaskId()) && !scheduler.isQueued(timer.getTaskId())
                            && oneShot.isCancelled() && timer.isCancelled()
                            && scheduler.getPendingTasks().stream().noneMatch(p -> p.getTaskId() == oneShot.getTaskId()
                                || p.getTaskId() == timer.getTaskId());
                        check(result == dev.iyanz.sourbycraft.api.world.WorldUnloadResult.SUCCESS && released && bodyRuns.get() == 0,
                            "LEGACY_UNLOAD_RELEASED", "world=" + name + " result=" + result + " queued="
                                + scheduler.isQueued(oneShot.getTaskId()) + "," + scheduler.isQueued(timer.getTaskId())
                                + " cancelled=" + oneShot.isCancelled() + "," + timer.isCancelled() + " bodyRuns=" + bodyRuns.get());
                    }, 20L));
                }));
            });
        });
    }

    /** After the same instance is enabled again: it can schedule, and nothing from before ran. */
    private void reenableChecks(final int gen) {
        final GlobalRegionScheduler global = Bukkit.getGlobalRegionScheduler();
        final AtomicInteger freshGlobal = new AtomicInteger();
        final AtomicInteger freshAsync = new AtomicInteger();
        global.runAtFixedRate(this, task -> {
            if (freshGlobal.incrementAndGet() == 3) task.cancel();
        }, 1L, 1L);
        this.getServer().getScheduler().runTaskAsynchronously(this, freshAsync::incrementAndGet);
        // 100 ticks: well after every stale timer (period 5, delay 40) would have run.
        this.getServer().getScheduler().runTaskLaterAsynchronously(this, () -> check(this.staleRuns.get() == 0
                && freshGlobal.get() == 3 && freshAsync.get() == 1 && this.generation.get() == gen, "LEGACY_REENABLE",
            "gen=" + gen + " staleRuns=" + this.staleRuns.get() + " freshGlobal=" + freshGlobal.get()
                + " freshAsync=" + freshAsync.get()), 100L);
    }

    @Override
    public void onDisable() {
        // Admission is closed before onDisable: submissions must be refused, not left orphaned.
        try {
            Bukkit.getGlobalRegionScheduler().run(this, task -> this.getLogger().severe("LEGACY_DISABLE_TASK_RAN_WRONG"));
            this.getLogger().severe("LEGACY_DISABLE_GLOBAL_SUBMIT_WRONG accepted");
        } catch (final RuntimeException refused) {
            this.getLogger().info("LEGACY_DISABLE_GLOBAL_SUBMIT_OK refused=" + refused.getClass().getSimpleName());
        }
        try {
            this.getServer().getScheduler().runTask(this, () -> this.getLogger().severe("LEGACY_DISABLE_TASK_RAN_WRONG"));
            this.getLogger().severe("LEGACY_DISABLE_BUKKIT_SUBMIT_WRONG accepted");
        } catch (final RuntimeException refused) {
            this.getLogger().info("LEGACY_DISABLE_BUKKIT_SUBMIT_OK refused=" + refused.getClass().getSimpleName());
        }
        // r48.3: a plugin's onDisable runs on the region shutdown thread after the scheduler halted. Writing to
        // an unloaded chunk must be refused with the owned message and must neither hang nor surface
        // "Polling tasks from non-owned region" (feature patch 0087); the write itself cannot be applied.
        try {
            final org.bukkit.World world = this.getServer().getWorlds().get(0);
            world.getBlockAt(512, 64, 512).setType(org.bukkit.Material.STONE);
            this.getLogger().severe("LEGACY_DISABLE_UNLOADED_BLOCK_WRITE_ACCEPTED");
        } catch (final RuntimeException refused) {
            this.getLogger().info("LEGACY_DISABLE_UNLOADED_BLOCK_WRITE_REFUSED " + refused.getClass().getSimpleName()
                + ": " + refused.getMessage());
        }
    }
}
