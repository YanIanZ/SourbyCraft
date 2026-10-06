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
 * explicit target region runs on that region. Otherwise a sync task
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

    private record Scheduled(String plugin, Object owner, Handle handle, Task task, BridgeRouter.Route route) {}

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
        synchronized (tasks) {
            tasks.disabled = false;
        }
        this.telemetry.register(plugin);
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
     * @return {@code false} when the task was rejected; it has then been marked cancelled
     */
    public boolean submit(final String plugin, final Object owner, final Task task, final long delayTicks) {
        if (!isBridged(plugin) || quarantined(plugin) || task.cancelled()) {
            this.telemetry.rejected(plugin);
            task.markCancelled();
            return false;
        }
        final dev.iyanz.sourbycraft.execution.region.RegionAnchor anchor;
        final Object entity;
        try {
            // Precedence: explicit entity > explicit region > caller-region/global fallback.
            entity = task.sync() && !task.global() ? task.targetEntity() : null;
            if (task.sync() && !task.global() && entity == null) {
                final dev.iyanz.sourbycraft.execution.region.RegionAnchor target = task.targetRegion();
                anchor = target != null ? target
                    : this.syncRoute.get() == dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute.CALLER_REGION
                        ? this.callerRegion.get() : null;
                if (anchor != null && anchor.world() == null) {
                    throw new IllegalArgumentException("task region has no world");
                }
            } else {
                anchor = null;
            }
        } catch (final RuntimeException failed) {
            this.telemetry.rejected(plugin);
            this.telemetry.failure(plugin, failed.toString());
            task.markCancelled();
            this.warn.accept("Aurora Bridge could not resolve owner of task " + task.id() + " for " + plugin, failed);
            return false;
        }
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
        final long delay = Math.max(0L, delayTicks);
        final long period = task.period() > 0 ? task.period() : 0L;
        final LateHandle handle = new LateHandle();
        final Scheduled entry = new Scheduled(plugin, owner, handle, task, route);
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
            capacityFailure(plugin, task.id(), "pending task limit " + pendingLimit);
            return false;
        }
        final Runnable body = () -> runGuarded(entry, period > 0);
        try {
            handle.bind(Objects.requireNonNull(switch (route) {
                case IO_LANE -> this.executor.async(owner, body, delay, period, () -> rejectedIo(entry));
                case REGION_OWNER -> this.executor.region(owner, anchor, body, delay, period);
                case ENTITY_OWNER -> this.executor.entity(owner, entity, body, delay, period, () -> retired(entry));
                default -> this.executor.global(owner, body, delay, period);
            }, "scheduler handle"));
        } catch (final RuntimeException failed) {
            rejected(entry);
            this.telemetry.failure(plugin, failed.toString());
            this.warn.accept("Aurora Bridge could not schedule task " + task.id() + " for " + plugin, failed);
            return false;
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

    private void rejectedIo(final Scheduled entry) {
        if (!rejected(entry)) return;
        final var failure = new java.util.concurrent.RejectedExecutionException("BRIDGE_IO lane is full or stopping");
        this.telemetry.failure(entry.plugin(), failure.getMessage());
        this.warn.accept("Aurora Bridge cancelled async task " + entry.task().id() + " for " + entry.plugin()
            + ": " + failure.getMessage(), failure);
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
            if (rejected(entry)) capacityFailure(plugin, task.id(), "running async callback limit " + asyncLimit);
            return;
        }
        try {
            // Body wall time only: queue wait and failure handling are outside the timed span.
            final long started = System.nanoTime();
            try {
                task.run();
            } finally {
                this.telemetry.bodyTime(plugin, BridgeTelemetry.BodyLane.of(entry.route()), System.nanoTime() - started);
            }
        } catch (final Throwable thrown) {
            onFailure(plugin, thrown, task.id());
        } finally {
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

    private void capacityFailure(final String plugin, final int taskId, final String reason) {
        final var failure = new java.util.concurrent.RejectedExecutionException(reason);
        this.telemetry.failure(plugin, reason);
        this.warn.accept("Aurora Bridge cancelled task " + taskId + " for " + plugin + ": " + reason, failure);
    }

    private void onFailure(final String plugin, final Throwable thrown, final int taskId) {
        final String description = thrown.getClass().getSimpleName()
            + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
        if (!ViolationClassifier.isRegionViolation(thrown)) {
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
                + " (" + fatal + "/" + this.quarantineAfter.getAsInt() + " before quarantine)", thrown);
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
