package dev.iyanz.sourbycraft.bridge;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/**
 * The Aurora Bridge's state machine, independent of the server it runs in.
 *
 * <p>Admission: in {@link BridgeMode#SAFE} a plugin that does not declare region-threading support
 * is admitted and becomes bridged; in {@link BridgeMode#OFF} nothing is admitted and the base keeps
 * refusing those plugins.</p>
 *
 * <p>Scheduling: a bridged plugin's Bukkit scheduler tasks are routed by {@link BridgeRouter}. A
 * sync task naming an entity runs on the region owning that entity when due, and is cancelled and
 * counted as rejected (not as a violation) if the entity is removed first. A sync task with an
 * explicit target region runs on that region. A sync task naming nothing takes the player of the
 * {@link SchedulingContext} (the player whose event or command is being handled, carried through
 * bridged async bodies) as its entity anchor, with the same contract as a named entity, except that a
 * player already gone at scheduling time simply yields no anchor. Otherwise a sync task
 * scheduled while a region is ticking runs on that region (anchored on a chunk it owned;
 * {@code aurora.bridge.sync-route = "caller-region"}, the default); from anywhere else, or with
 * {@code "global"}, it runs on the global region. A caller anchor does not prove that every
 * location accessed by an opaque callback belongs to that region. An async task is timed by the async scheduler
 * and executed by the bounded bridge I/O lane. Every task body is guarded: an ownership violation it raises counts toward
 * quarantine, any other exception is recorded as the plugin's last failure, and neither escapes
 * into the scheduler thread.</p>
 *
 * <p>Quarantine: after {@code quarantineAfter} fatal violations the plugin's bridged tasks are
 * cancelled and new ones rejected. The plugin is not disabled — disabling runs plugin code from
 * whatever thread noticed the violation, which is the thing being contained.</p>
 *
 * <p>Guard hits: with the engine's default {@code guard-severity: LOG}, a cross-region access the
 * engine's tick guard notices is logged and allowed to proceed rather than thrown, so it never
 * escapes the task body. While a bridged body runs, its plugin is published to the running thread;
 * such a guard hit is then reported here ({@link GuardViolations#report}) and counted as one
 * fatal violation of that plugin per body invocation, through the same quarantine path. Its log
 * line is rate-limited per (plugin, reason). A guard hit outside any bridged body (native plugins,
 * the server itself) is not the bridge's business and keeps the engine's own log line.</p>
 *
 * <p>BRIDGE_IO saturation: a one-shot async task the lane refuses is cancelled; a repeating one
 * skips that run (counted as a skipped run) and stays scheduled for its next period.</p>
 */
public final class BridgeRuntime {

    /** Where routed work is actually run. Ticks are server ticks; a period of 0 or less runs once. */
    public interface Executor {
        Handle global(Object owner, Runnable body, long delayTicks, long periodTicks);
        Handle async(Object owner, Runnable body, long delayTicks, long periodTicks);
        /** Reports a timed invocation rejected by the bounded I/O lane. */
        default Handle async(Object owner, Runnable body, long delayTicks, long periodTicks, Runnable rejected) {
            return async(owner, body, delayTicks, periodTicks);
        }
        /** On whichever region owns the anchor's chunk when the work comes due. */
        Handle region(Object owner, dev.iyanz.sourbycraft.execution.region.RegionAnchor anchor, Runnable body,
                      long delayTicks, long periodTicks);
        /**
         * On whichever region owns the entity when the work comes due. {@code retired} runs instead
         * of any further body when the entity is removed first (Folia's entity scheduler contract),
         * including when it was already removed at scheduling time.
         */
        Handle entity(Object owner, Object entity, Runnable body, long delayTicks, long periodTicks, Runnable retired);
    }

    /** A scheduled piece of routed work. */
    public interface Handle {
        void cancel();
    }

    /** A legacy scheduler task as the bridge needs to see it. */
    public interface Task {
        int id();
        boolean sync();
        /** An explicit global scheduler request must never inherit the caller's region. */
        default boolean global() { return false; }
        /** Explicit owner metadata, resolved before dispatch; never queried for async/global tasks. */
        default dev.iyanz.sourbycraft.execution.region.RegionAnchor targetRegion() { return null; }
        /**
         * The entity the task works on, or {@code null}. Takes precedence over {@link #targetRegion()};
         * like it, never queried for async/global tasks.
         */
        default Object targetEntity() { return null; }
        /** Bukkit period in ticks; 0 or less means run once. */
        long period();
        boolean cancelled();
        void markCancelled();
        void run();

        /** The server's own task object (a {@code CraftTask}), for {@code getPendingTasks}. */
        default Object handle() {
            return null;
        }
    }

    /**
     * {@code world}: the anchor's world for {@link BridgeRouter.Route#REGION_OWNER}, else {@code null}.
     * {@code context}: what the body publishes as {@link SchedulingContext} (the entity it is anchored to,
     * or the frame an async task was scheduled from), else {@code null}. {@code inferred}: the entity
     * anchor came from the scheduling context, not from the plugin. {@code untargeted}: nothing named an
     * owner and the task fell through to the global region.
     */
    private record Scheduled(String plugin, Object owner, Handle handle, Task task, BridgeRouter.Route route,
                             Object world, Object context, boolean inferred, boolean untargeted) {}

    /** The bridged body running on this thread, if any; see {@link GuardViolations}. */
    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();

    /** One run of a bridged body. Only its own thread touches it. */
    private static final class Invocation {
        final BridgeRuntime runtime;
        final String plugin;
        final int taskId;
        /** Whether the task named no owner and ran on the global region (see {@link #sweepHint}). */
        final boolean untargeted;
        /** One body run counts at most one violation, however many guard hits it makes. */
        boolean violationCounted;

        Invocation(final BridgeRuntime runtime, final String plugin, final int taskId, final boolean untargeted) {
            this.runtime = runtime;
            this.plugin = plugin;
            this.taskId = taskId;
            this.untargeted = untargeted;
        }
    }

    /** How long one (plugin, reason) log line suppresses the next. */
    static final long LOG_WINDOW_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60L);

    /** A running async callback, including bodies draining after task cancellation. */
    public record AsyncWorker(int taskId, Object owner, Thread thread) {
        // Ownership is instance identity; never invoke plugin equals/hashCode under admission locks.
        @Override
        public boolean equals(final Object other) {
            return other instanceof AsyncWorker worker && this.taskId == worker.taskId
                && this.owner == worker.owner && this.thread == worker.thread;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * this.taskId + System.identityHashCode(this.owner))
                + System.identityHashCode(this.thread);
        }
    }

    /** Admission/index monitor per plugin; executor calls and task bodies never hold it. */
    private static final class PluginTasks {
        final Map<Integer, Scheduled> tasks = new ConcurrentHashMap<>();
        boolean disabled;
        int runningAsync;
        /** Whether admitting this plugin while quarantined has been reported since its last disable. */
        boolean quarantineAdmitReported;
    }

    /** Rate-limited log state per (plugin, reason): last logged time and lines suppressed since. */
    private static final class LogWindow {
        long lastNanos;
        long suppressed;
        boolean logged;
    }

    private final BridgeMode mode;
    private final IntSupplier quarantineAfter;
    private final Executor executor;
    private final BridgeTelemetry telemetry;
    private final BiConsumer<String, Throwable> warn;
    private final Map<String, PluginTasks> admitted = new ConcurrentHashMap<>();
    private final Map<Integer, Scheduled> scheduled = new ConcurrentHashMap<>();
    /** Active invocations per task; repeating async tasks may have overlapping bodies. */
    private final Map<Integer, Integer> running = new ConcurrentHashMap<>();
    private final Map<AsyncWorker, Integer> workers = new ConcurrentHashMap<>();
    private final java.util.function.Supplier<dev.iyanz.sourbycraft.execution.region.RegionAnchor> callerRegion;
    private final java.util.function.Supplier<dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute> syncRoute;
    private final IntSupplier maxPendingTasks;
    private final IntSupplier maxRunningAsyncTasks;
    /** Bounded by plugins times the engine's fixed set of guard reasons (plus one lane reason). */
    private final Map<String, LogWindow> logWindows = new ConcurrentHashMap<>();
    /** Test seam for the log rate limit and the body-time measurement. */
    java.util.function.LongSupplier nanoClock = System::nanoTime;

    /** Unanchored sync tasks use the global region: no caller-region lookup. */
    public BridgeRuntime(final BridgeMode mode, final IntSupplier quarantineAfter, final Executor executor,
                         final BridgeTelemetry telemetry, final BiConsumer<String, Throwable> warn) {
        this(mode, quarantineAfter, executor, telemetry, warn, () -> null,
            () -> dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.GLOBAL);
    }

    /**
     * @param callerRegion the region the calling thread is ticking, or {@code null}
     * @param syncRoute {@code aurora.bridge.sync-route}, read per task (LIVE)
     */
    public BridgeRuntime(final BridgeMode mode, final IntSupplier quarantineAfter, final Executor executor,
                         final BridgeTelemetry telemetry, final BiConsumer<String, Throwable> warn,
                         final java.util.function.Supplier<dev.iyanz.sourbycraft.execution.region.RegionAnchor> callerRegion,
                         final java.util.function.Supplier<dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute> syncRoute) {
        this(mode, quarantineAfter, executor, telemetry, warn, callerRegion, syncRoute, () -> 0, () -> 0);
    }

    /** Per-plugin limits are read at admission/start; 0 retains the existing unlimited behavior. */
    public BridgeRuntime(final BridgeMode mode, final IntSupplier quarantineAfter, final Executor executor,
                         final BridgeTelemetry telemetry, final BiConsumer<String, Throwable> warn,
                         final java.util.function.Supplier<dev.iyanz.sourbycraft.execution.region.RegionAnchor> callerRegion,
                         final java.util.function.Supplier<dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute> syncRoute,
                         final IntSupplier maxPendingTasks, final IntSupplier maxRunningAsyncTasks) {
        this.callerRegion = Objects.requireNonNull(callerRegion, "callerRegion");
        this.syncRoute = Objects.requireNonNull(syncRoute, "syncRoute");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.quarantineAfter = Objects.requireNonNull(quarantineAfter, "quarantineAfter");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.warn = Objects.requireNonNull(warn, "warn");
        this.maxPendingTasks = Objects.requireNonNull(maxPendingTasks, "maxPendingTasks");
        this.maxRunningAsyncTasks = Objects.requireNonNull(maxRunningAsyncTasks, "maxRunningAsyncTasks");
    }

    /**
     * Whether a world is unloading or unloaded (its unload ticket is set at the START of the unload and
     * stays set). A region-owner task anchored there is refused: the unload's one-time release
     * ({@link #worldUnloaded}) would never see it, so it would stay indexed, count against the pending
     * limit and keep the unloaded world reachable. Default: no world is ever closed (unit tests).
     */
    private volatile java.util.function.Predicate<Object> worldClosed = world -> false;

    /** Installs the production "world is unloading" check; see {@link #worldClosed}. */
    public void worldClosedCheck(final java.util.function.Predicate<Object> check) {
        this.worldClosed = Objects.requireNonNull(check, "check");
    }

    private boolean anchoredInClosedWorld(final BridgeRouter.Route route, final Object world) {
        if (route != BridgeRouter.Route.REGION_OWNER || world == null) return false;
        try {
            return this.worldClosed.test(world);
        } catch (final RuntimeException unknown) {
            return false;
        }
    }

    private static String closedWorldFailure(final int taskId) {
        return "task " + taskId + " refused: the world it is anchored in is unloading";
    }

    public BridgeMode mode() {
        return this.mode;
    }

    public BridgeTelemetry telemetry() {
        return this.telemetry;
    }

    /**
     * Called by the plugin loader for a plugin that does not declare region-threading support.
     *
     * @return whether it may load
     */
    public boolean admit(final String plugin) {
        if (this.mode != BridgeMode.SAFE) {
            return false;
        }
        final PluginTasks tasks = this.admitted.computeIfAbsent(Objects.requireNonNull(plugin, "plugin"),
            ignored -> new PluginTasks());
        // Admission is asked again only when a plugin of this name is loaded again (a reload after
        // disable). Disable drained the earlier instance's index; reopen it for the new instance.
        final boolean report;
        synchronized (tasks) {
            report = quarantined(plugin) && (tasks.disabled || !tasks.quarantineAdmitReported);
            if (report) tasks.quarantineAdmitReported = true;
            tasks.disabled = false;
        }
        this.telemetry.register(plugin);
        if (report) {
            // Quarantine outlives a reload by name; without this the new instance would load and
            // every task it schedules would be rejected without a word.
            final BridgeTelemetry.PluginStats stats = this.telemetry.stats(plugin);
            this.warn.accept("Aurora Bridge admitted " + plugin + " again, but it is quarantined for region-ownership"
                + " violations earlier in this run; every task it schedules is rejected until the server restarts."
                + (stats != null && stats.lastFailure() != null ? " Last: " + stats.lastFailure() : ""), null);
        }
        return true;
    }

    /**
     * Re-opens admission for a plugin the bridge already admitted, when the same instance is
     * enabled again after a disable (the loader is not asked again in that case, so
     * {@link #admit} never runs). Never admits: a plugin the bridge did not admit stays outside it.
     * Quarantine is untouched -- it is cleared by nothing but a restart.
     *
     * @return whether the plugin is bridged and admission is now open
     */
    public boolean reopen(final String plugin) {
        if (plugin == null) return false;
        final PluginTasks tasks = this.admitted.get(plugin);
        if (tasks == null) return false;
        synchronized (tasks) {
            tasks.disabled = false;
        }
        return true;
    }

    public boolean isBridged(final String plugin) {
        return plugin != null && this.admitted.containsKey(plugin);
    }

    public boolean quarantined(final String plugin) {
        return this.telemetry.quarantined(plugin);
    }

    /**
     * Schedules a bridged plugin's task.
     *
     * <p>Owner precedence for a sync task: explicit {@code EntityTask} entity, explicit
     * {@code RegionTask} (or adapter) region, the player of the current {@link SchedulingContext}
     * (inferred entity anchor), the caller's region ({@code sync-route = caller-region}), the global
     * region. An explicit global request takes none of them; an async task takes the I/O lane and
     * only carries the context for the tasks its body schedules.</p>
     *
     * @return {@code false} when the task was rejected; it has then been marked cancelled
     */
    public boolean submit(final String plugin, final Object owner, final Task task, final long delayTicks) {
        if (!isBridged(plugin) || quarantined(plugin) || task.cancelled()) {
            this.telemetry.rejected(plugin);
            task.markCancelled();
            return false;
        }
        final Resolution resolved;
        try {
            resolved = resolve(task);
        } catch (final RuntimeException failed) {
            this.telemetry.rejected(plugin);
            this.telemetry.failure(plugin, failed.toString());
            task.markCancelled();
            this.warn.accept("Aurora Bridge could not resolve owner of task " + task.id() + " for " + plugin, failed);
            return false;
        }
        return schedule(plugin, owner, task, delayTicks, resolved);
    }

    /**
     * How a task's owner was decided: the entity or region anchor (at most one), whether the entity
     * was inferred from the scheduling context rather than named by the plugin, the context the body
     * publishes to the tasks it schedules, and whether nothing named an owner at all.
     */
    private record Resolution(Object entity, dev.iyanz.sourbycraft.execution.region.RegionAnchor anchor,
                              boolean inferred, Object context, boolean untargeted) {
        static Resolution none() { return new Resolution(null, null, false, null, false); }
    }

    private Resolution resolve(final Task task) {
        if (!task.sync()) {
            // The I/O lane owns nothing; the body publishes the frame it came from (DeluxeMenus: a
            // player's command -> async build -> sync open; EssentialsX: join -> async -> callEvent).
            return new Resolution(null, null, false, SchedulingContext.current(), false);
        }
        if (task.global()) return Resolution.none();
        Object entity = task.targetEntity();
        dev.iyanz.sourbycraft.execution.region.RegionAnchor anchor = null;
        boolean inferred = false;
        if (entity == null) {
            anchor = task.targetRegion();
            if (anchor == null) {
                entity = SchedulingContext.current();
                inferred = entity != null;
                if (entity == null
                    && this.syncRoute.get() == dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.CALLER_REGION) {
                    anchor = this.callerRegion.get();
                }
            }
        }
        if (anchor != null && anchor.world() == null) {
            throw new IllegalArgumentException("task region has no world");
        }
        return new Resolution(entity, anchor, inferred, entity, entity == null && anchor == null);
    }

    /** The caller-region/global fallback for an inferred anchor whose player was already gone. */
    private Resolution fallback() {
        final dev.iyanz.sourbycraft.execution.region.RegionAnchor anchor =
            this.syncRoute.get() == dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.CALLER_REGION
                ? this.callerRegion.get() : null;
        return new Resolution(null, anchor != null && anchor.world() != null ? anchor : null, false, null,
            anchor == null || anchor.world() == null);
    }

    private boolean schedule(final String plugin, final Object owner, final Task task, final long delayTicks,
                             final Resolution resolved) {
        final Object entity = resolved.entity();
        final dev.iyanz.sourbycraft.execution.region.RegionAnchor anchor = resolved.anchor();
        final BridgeRouter.Route route = !isBridged(plugin) || quarantined(plugin)
            ? BridgeRouter.Route.REJECT
            : BridgeRouter.route(this.mode, task.sync()
                ? new BridgeRouter.Operation(BridgeRouter.Kind.SYNC_TASK, entity != null, anchor != null)
                : BridgeRouter.Operation.asyncTask());
        if (route == BridgeRouter.Route.REJECT) {
            this.telemetry.rejected(plugin);
            task.markCancelled();
            return false;
        }
        if (anchoredInClosedWorld(route, anchor == null ? null : anchor.world())) {
            // Expected while a world unloads (an async thread naming a location there): rejected,
            // never a violation, not logged per task.
            this.telemetry.rejected(plugin);
            this.telemetry.failure(plugin, closedWorldFailure(task.id()));
            task.markCancelled();
            return false;
        }
        final long delay = Math.max(0L, delayTicks);
        final long period = task.period() > 0 ? task.period() : 0L;
        final LateHandle handle = new LateHandle();
        final Scheduled entry = new Scheduled(plugin, owner, handle, task, route,
            route == BridgeRouter.Route.REGION_OWNER ? anchor.world() : null, resolved.context(),
            resolved.inferred(), resolved.untargeted());
        final PluginTasks tasks = this.admitted.get(plugin);
        final int pendingLimit = this.maxPendingTasks.getAsInt();
        boolean capacityRejected = false;
        // Insert both indexes before scheduling: zero-delay work may finish before the executor
        // returns. Disable/quarantine admission and index changes share only this plugin's lock.
        synchronized (tasks) {
            if (tasks.disabled || task.cancelled() || quarantined(plugin)) {
                this.telemetry.rejected(plugin);
                task.markCancelled();
                return false;
            }
            if (pendingLimit > 0 && tasks.tasks.size() >= pendingLimit) {
                capacityRejected = true;
            } else if (this.scheduled.putIfAbsent(task.id(), entry) != null) {
                this.telemetry.rejected(plugin);
                task.markCancelled();
                return false;
            } else {
                tasks.tasks.put(task.id(), entry);
            }
        }
        if (capacityRejected) {
            this.telemetry.rejected(plugin);
            task.markCancelled();
            capacityFailure(plugin, task.id(), "pending task limit", pendingLimit);
            return false;
        }
        // Re-checked after indexing: an unload that began after the check above and before the entry
        // was indexed has either swept it (worldUnloaded runs after the ticket is set) or is seen here.
        if (anchoredInClosedWorld(route, entry.world())) {
            if (rejected(entry)) this.telemetry.failure(plugin, closedWorldFailure(task.id()));
            return false;
        }
        final Runnable body = () -> runGuarded(entry, period > 0);
        // An inferred anchor whose player is already gone when the task is scheduled is no anchor:
        // the entity scheduler reports that synchronously (Folia returns no task), and the task then
        // takes the route it would have taken without the inference. Nothing has run yet, so this is
        // a routing decision, not a retry. After scheduling, retirement cancels it like an EntityTask.
        final java.util.concurrent.atomic.AtomicBoolean handed = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean goneAtSchedule = new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable retired = !resolved.inferred() ? () -> retired(entry) : () -> {
            if (handed.get()) retiredInferred(entry);
            else goneAtSchedule.set(true);
        };
        try {
            handle.bind(Objects.requireNonNull(switch (route) {
                case IO_LANE -> this.executor.async(owner, body, delay, period, () -> rejectedIo(entry, period > 0));
                case REGION_OWNER -> this.executor.region(owner, anchor, body, delay, period);
                case ENTITY_OWNER -> this.executor.entity(owner, entity, body, delay, period, retired);
                default -> this.executor.global(owner, body, delay, period);
            }, "scheduler handle"));
        } catch (final RuntimeException failed) {
            rejected(entry);
            this.telemetry.failure(plugin, failed.toString());
            this.warn.accept("Aurora Bridge could not schedule task " + task.id() + " for " + plugin, failed);
            return false;
        }
        handed.set(true);
        if (goneAtSchedule.get()) {
            if (!remove(entry)) return !task.cancelled();
            handle.cancel();
            final Resolution fallback = fallback();
            this.telemetry.failure(plugin, "task " + task.id() + ": the player it was scheduled for (" + entity
                + ") was already gone when scheduled; routed to the " + (fallback.anchor() != null ? "caller region" : "global region"));
            return schedule(plugin, owner, task, delayTicks, fallback);
        }
        this.telemetry.redirect(plugin);
        if (route == BridgeRouter.Route.REGION_OWNER || route == BridgeRouter.Route.ENTITY_OWNER) {
            this.telemetry.handoff(plugin);
        }
        return !task.cancelled();
    }

    private boolean rejected(final Scheduled entry) {
        if (remove(entry)) {
            this.telemetry.rejected(entry.plugin());
            entry.task().markCancelled();
            entry.handle().cancel();
            return true;
        }
        return false;
    }

    /**
     * The entity scheduler retired the task: its entity was removed before the body could run.
     * Expected gameplay, not a fault: rejected, never a violation, and not logged per task.
     */
    private void retired(final Scheduled entry) {
        if (!rejected(entry)) return;
        this.telemetry.failure(entry.plugin(), "task " + entry.task().id()
            + " cancelled: its target entity was removed before it ran (retired)");
    }

    /**
     * The entity scheduler retired a task whose player anchor the bridge inferred from the scheduling
     * context: the player left between scheduling and the run. Same contract as an explicit
     * {@code EntityTask}: cancelled, rejected, never a violation. (A player already gone at scheduling
     * time is handled before this, by taking the fallback route instead.)
     */
    private void retiredInferred(final Scheduled entry) {
        if (!rejected(entry)) return;
        this.telemetry.failure(entry.plugin(), "task " + entry.task().id()
            + " cancelled: the player it was scheduled for (from their event or command) left before it ran");
    }

    /**
     * A world is unloading. Its regions never tick again, and the engine cancels their region
     * scheduler tasks without running them and without telling the bridge (WorldUnloadDrain), so
     * a bridged task anchored there would otherwise stay indexed for good: counted as pending,
     * reported by {@code isQueued}/{@code getPendingTasks}, never cancelled in the plugin's view.
     * Releases those entries like a retired entity task: rejected, never a violation. Entity
     * tasks are released by their retired callback; global and async tasks have no world.
     *
     * @param world the unloading world, compared by identity with the anchor's world
     * @return how many bridged tasks were released
     */
    public int worldUnloaded(final Object world) {
        if (world == null) return 0;
        int released = 0;
        for (final Scheduled entry : this.scheduled.values()) {
            if (entry.route() != BridgeRouter.Route.REGION_OWNER || entry.world() != world) continue;
            if (!rejected(entry)) continue;
            this.telemetry.failure(entry.plugin(), "task " + entry.task().id()
                + " cancelled: the world it was anchored in unloaded before it ran");
            ++released;
        }
        return released;
    }

    /**
     * The BRIDGE_IO lane refused a timed run. A one-shot task has no later run, so it is cancelled.
     * A repeating task only loses this run: it stays scheduled, the run is counted as skipped, and
     * the log line is rate-limited -- one plugin filling the lane must not cancel another plugin's
     * timer for good.
     */
    private void rejectedIo(final Scheduled entry, final boolean repeating) {
        final String reason = "BRIDGE_IO lane is full or stopping";
        if (repeating) {
            if (this.scheduled.get(entry.task().id()) != entry || entry.task().cancelled()) return;
            this.telemetry.skipped(entry.plugin());
            this.telemetry.failure(entry.plugin(), "run of repeating task " + entry.task().id() + " skipped: " + reason);
            final long suppressed = admitLog(entry.plugin(), reason);
            if (suppressed >= 0) {
                this.warn.accept("Aurora Bridge skipped a run of repeating async task " + entry.task().id() + " for "
                    + entry.plugin() + ": " + reason + "; the task stays scheduled" + suppressedNote(suppressed),
                    new java.util.concurrent.RejectedExecutionException(reason));
            }
            return;
        }
        if (!rejected(entry)) return;
        final var failure = new java.util.concurrent.RejectedExecutionException(reason);
        this.telemetry.failure(entry.plugin(), failure.getMessage());
        this.warn.accept("Aurora Bridge cancelled async task " + entry.task().id() + " for " + entry.plugin()
            + ": " + failure.getMessage(), failure);
    }

    /**
     * Whether a (plugin, reason) line may be logged now.
     *
     * @return {@code -1} to suppress it, otherwise how many lines the window suppressed before it
     */
    private long admitLog(final String plugin, final String reason) {
        final LogWindow window = this.logWindows.computeIfAbsent(plugin + '|' + reason, ignored -> new LogWindow());
        final long now = this.nanoClock.getAsLong();
        synchronized (window) {
            if (window.logged && now - window.lastNanos < LOG_WINDOW_NANOS) {
                window.suppressed++;
                return -1L;
            }
            final long suppressed = window.suppressed;
            window.logged = true;
            window.lastNanos = now;
            window.suppressed = 0L;
            return suppressed;
        }
    }

    private static String suppressedNote(final long suppressed) {
        return suppressed > 0 ? " (" + suppressed + " more suppressed in the last "
            + java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(LOG_WINDOW_NANOS) + " s)" : "";
    }

    /**
     * Called by {@link GuardViolations#report} on the thread where the engine's LOG-mode tick
     * guard saw a cross-region access.
     *
     * @return whether a bridged body is running on this thread, so the hit was recorded here
     */
    static boolean reportGuardViolation(final String reason) {
        final Invocation invocation = CURRENT.get();
        if (invocation == null) return false;
        invocation.runtime.guardViolation(invocation, reason == null ? "unknown" : reason);
        return true;
    }

    private void guardViolation(final Invocation invocation, final String reason) {
        final String plugin = invocation.plugin;
        if (invocation.violationCounted) {
            // A loop touching a hundred foreign blocks is one bad body run, not a hundred.
            return;
        }
        invocation.violationCounted = true;
        final String description = "cross-region access allowed by guard-severity LOG: " + reason;
        final long fatal = this.telemetry.fatal(plugin, description);
        final var evidence = new IllegalStateException("Thread failed main thread check: " + reason);
        if (fatal >= this.quarantineAfter.getAsInt() && this.telemetry.quarantine(plugin)) {
            cancelAll(plugin);
            this.warn.accept("Aurora Bridge quarantined " + plugin + " after " + fatal
                + " region-ownership violation(s); its bridged tasks are cancelled and new ones rejected. "
                + "Last: " + description, evidence);
            return;
        }
        final long suppressed = admitLog(plugin, reason);
        if (suppressed >= 0) {
            this.warn.accept("Bridged plugin " + plugin + " accessed state owned by another region in task "
                + invocation.taskId + " (" + reason + "; " + fatal + "/" + this.quarantineAfter.getAsInt()
                + " before quarantine)" + suppressedNote(suppressed) + sweepHint(invocation.untargeted), evidence);
        }
    }

    /**
     * Appended to a violation line when the task named no owner and ran on the global region. The
     * bridge routes a task to <em>one</em> owner it can name (the player of the event or command the
     * task was scheduled from, a {@code RegionTask}, an {@code EntityTask}); a body that sweeps
     * entities or blocks across the world from one task has no single owner, and no routing fixes it.
     */
    static String sweepHint(final boolean untargeted) {
        return untargeted ? " The task named no owner and ran on the global region (scheduled outside any player"
            + " event or command, without RegionTask/EntityTask); a body that sweeps entities or blocks across the"
            + " world cannot be routed to one owner and needs a region-aware (folia-supported) plugin version." : "";
    }

    /** Removes both indexes together; stale callbacks cannot remove another task's entry. */
    private boolean remove(final Scheduled entry) {
        final PluginTasks tasks = this.admitted.get(entry.plugin());
        synchronized (tasks) {
            if (!this.scheduled.remove(entry.task().id(), entry)) return false;
            tasks.tasks.remove(entry.task().id(), entry);
            return true;
        }
    }

    /** A handle that may be cancelled before the executor has produced the real one. */
    private static final class LateHandle implements Handle {
        private Handle delegate;
        private boolean cancelled;

        synchronized void bind(final Handle real) {
            this.delegate = Objects.requireNonNull(real, "real");
            if (this.cancelled) real.cancel();
        }

        @Override
        public synchronized void cancel() {
            this.cancelled = true;
            if (this.delegate != null) this.delegate.cancel();
        }
    }

    private void runGuarded(final Scheduled entry, final boolean repeating) {
        final String plugin = entry.plugin();
        final Task task = entry.task();
        if (this.scheduled.get(task.id()) != entry) return;
        final PluginTasks tasks = this.admitted.get(plugin);
        final boolean async = !task.sync();
        final int asyncLimit = async ? this.maxRunningAsyncTasks.getAsInt() : 0;
        final AsyncWorker worker = async ? new AsyncWorker(task.id(), entry.owner(), Thread.currentThread()) : null;
        final boolean unavailable;
        final boolean capacityRejected;
        // Disable and callback start share the admission monitor. A claimed running callback may
        // drain; disabled or over-limit callbacks never acquire a running slot.
        synchronized (tasks) {
            unavailable = this.scheduled.get(task.id()) != entry || tasks.disabled
                || task.cancelled() || quarantined(plugin);
            capacityRejected = !unavailable && async && asyncLimit > 0 && tasks.runningAsync >= asyncLimit;
            if (!unavailable && !capacityRejected) {
                this.running.compute(task.id(), (id, count) -> count == null ? 1 : count + 1);
                if (async) {
                    ++tasks.runningAsync;
                    this.workers.merge(worker, 1, Integer::sum);
                }
            }
        }
        if (unavailable) {
            // Retain entry identity: a stale callback must not cancel a later task using its ID.
            if (remove(entry)) {
                task.markCancelled();
                entry.handle().cancel();
            }
            return;
        }
        if (capacityRejected) {
            if (rejected(entry)) capacityFailure(plugin, task.id(), "running async callback limit", asyncLimit);
            return;
        }
        final Invocation previous = CURRENT.get();
        final Invocation invocation = new Invocation(this, plugin, task.id(), entry.untargeted());
        CURRENT.set(invocation);
        try {
            // Body wall time only: queue wait and failure handling are outside the timed span.
            final long started = this.nanoClock.getAsLong();
            // The body publishes exactly its own anchor: tasks it schedules follow the same player.
            try (SchedulingContext.Scope scope = SchedulingContext.body(entry.context())) {
                task.run();
            } finally {
                this.telemetry.bodyTime(plugin, BridgeTelemetry.BodyLane.of(entry.route()), this.nanoClock.getAsLong() - started);
            }
        } catch (final Throwable thrown) {
            onFailure(plugin, thrown, task.id(), invocation.violationCounted, invocation.untargeted);
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            this.running.computeIfPresent(task.id(), (id, count) -> count == 1 ? null : count - 1);
            if (async) {
                this.workers.computeIfPresent(worker, (key, count) -> count == 1 ? null : count - 1);
                synchronized (tasks) {
                    --tasks.runningAsync;
                }
            }
            if (!repeating) {
                if (remove(entry)) entry.handle().cancel();
            }
        }
    }

    /**
     * A task refused by a per-plugin limit. Every refusal is counted and becomes the last failure; the
     * WARN is rate-limited per (plugin, limit) like the I/O-lane line, and carries no stack trace: the
     * limit is the operator's configuration, not a fault, and a plugin scheduling past it used to log one
     * 35-line stack trace per task (2026-10-22 local run, 4 refusals = 140 lines).
     */
    private void capacityFailure(final String plugin, final int taskId, final String limit, final int value) {
        final String reason = limit + " " + value;
        this.telemetry.failure(plugin, reason);
        final long suppressed = admitLog(plugin, limit);
        if (suppressed >= 0) {
            this.warn.accept("Aurora Bridge cancelled task " + taskId + " for " + plugin + ": " + reason
                + " (aurora.bridge." + (limit.startsWith("pending") ? "max-pending-tasks-per-plugin"
                    : "max-running-async-tasks-per-plugin") + "); further refusals for this limit are counted in"
                + " /plugins " + plugin + " and logged at most once per "
                + java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(LOG_WINDOW_NANOS) + " s" + suppressedNote(suppressed), null);
        }
    }

    private void onFailure(final String plugin, final Throwable thrown, final int taskId, final boolean alreadyCounted,
                           final boolean untargeted) {
        final String description = thrown.getClass().getSimpleName()
            + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
        if (!ViolationClassifier.isRegionViolation(thrown) || alreadyCounted) {
            // A body whose guard hit already counted this run's violation is not counted twice.
            this.telemetry.failure(plugin, description);
            this.warn.accept("Bridged plugin " + plugin + " generated an exception while executing task "
                + taskId, thrown);
            return;
        }
        final long fatal = this.telemetry.fatal(plugin, description);
        if (fatal >= this.quarantineAfter.getAsInt() && this.telemetry.quarantine(plugin)) {
            cancelAll(plugin);
            this.warn.accept("Aurora Bridge quarantined " + plugin + " after " + fatal
                + " region-ownership violation(s); its bridged tasks are cancelled and new ones rejected. "
                + "Last: " + description, thrown);
        } else {
            this.warn.accept("Bridged plugin " + plugin + " violated region ownership in task " + taskId
                + " (" + fatal + "/" + this.quarantineAfter.getAsInt() + " before quarantine)" + sweepHint(untargeted), thrown);
        }
    }

    /** Cancels one task if the bridge scheduled it; returns whether it did. */
    public boolean cancel(final int taskId) {
        final Scheduled entry = this.scheduled.get(taskId);
        if (entry == null || !remove(entry)) return false;
        entry.task().markCancelled();
        entry.handle().cancel();
        return true;
    }

    /** Cancels current tasks without closing admission (Bukkit cancelTasks semantics). */
    public int cancelAll(final String plugin) {
        return cancelAll(plugin, false, false);
    }

    /** Cancels only this plugin's tasks backed by the global region scheduler. */
    public int cancelGlobal(final String plugin) {
        return cancelAll(plugin, false, true);
    }

    /** Closes admission before cancelling, so racing or later submissions cannot outlive disable. */
    public int disable(final String plugin) {
        return cancelAll(plugin, true, false);
    }

    private int cancelAll(final String plugin, final boolean disable, final boolean globalOnly) {
        final PluginTasks tasks = this.admitted.get(plugin);
        if (tasks == null) return 0;
        final java.util.List<Scheduled> removed;
        synchronized (tasks) {
            if (disable) tasks.disabled = true;
            removed = new java.util.ArrayList<>();
            for (final Scheduled entry : tasks.tasks.values()) {
                if (globalOnly && entry.route() != BridgeRouter.Route.GLOBAL_REGION) continue;
                removed.add(entry);
            }
            for (final Scheduled entry : removed) {
                this.scheduled.remove(entry.task().id(), entry);
                tasks.tasks.remove(entry.task().id(), entry);
            }
        }
        // Cancellation can call the scheduler; never invoke it while holding the index monitor.
        for (final Scheduled entry : removed) {
            entry.task().markCancelled();
            entry.handle().cancel();
        }
        return removed.size();
    }

    /** Whether the bridge holds this task: scheduled, not yet finished (one-shot) or cancelled. */
    public boolean knows(final int taskId) {
        return this.scheduled.containsKey(taskId);
    }

    /** Whether the task's body is executing right now. */
    public boolean running(final int taskId) {
        return this.running.containsKey(taskId);
    }

    /** Best-effort immutable snapshot of actual async workers, excluding queued callbacks. */
    public java.util.List<AsyncWorker> activeWorkers() {
        final var snapshot = new java.util.ArrayList<>(this.workers.keySet());
        snapshot.sort(java.util.Comparator.comparingInt(AsyncWorker::taskId)
            .thenComparingLong(worker -> worker.thread().threadId()));
        return java.util.List.copyOf(snapshot);
    }

    /** Running async bodies remain counted until completion, even when their task is cancelled. */
    public int runningAsync(final String plugin) {
        final PluginTasks tasks = this.admitted.get(plugin);
        if (tasks == null) return 0;
        synchronized (tasks) {
            return tasks.runningAsync;
        }
    }

    /** The server task objects of every bridged task still scheduled. */
    public java.util.List<Object> pendingHandles() {
        final java.util.List<Object> out = new java.util.ArrayList<>();
        for (final Scheduled entry : this.scheduled.values()) {
            final Object handle = entry.task().handle();
            if (handle != null && !entry.task().cancelled()) out.add(handle);
        }
        return out;
    }

    /** Bridged tasks currently scheduled for a plugin. */
    public int pending(final String plugin) {
        final PluginTasks tasks = this.admitted.get(plugin);
        return tasks == null ? 0 : tasks.tasks.size();
    }
}
