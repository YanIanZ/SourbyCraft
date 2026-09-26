package dev.iyanz.sourbycraft.bridge;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * sync task names nothing, so it runs on the global region; an async task runs on the async
 * scheduler. Every task body is guarded: an ownership violation it raises counts toward
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
    }

    /** A scheduled piece of routed work. */
    public interface Handle {
        void cancel();
    }

    /** A legacy scheduler task as the bridge needs to see it. */
    public interface Task {
        int id();
        boolean sync();
        /** Bukkit period in ticks; 0 or less means run once. */
        long period();
        boolean cancelled();
        void markCancelled();
        void run();
    }

    private record Scheduled(String plugin, Handle handle, Task task) {}

    private final BridgeMode mode;
    private final IntSupplier quarantineAfter;
    private final Executor executor;
    private final BridgeTelemetry telemetry;
    private final BiConsumer<String, Throwable> warn;
    private final Set<String> admitted = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Scheduled> scheduled = new ConcurrentHashMap<>();

    public BridgeRuntime(final BridgeMode mode, final IntSupplier quarantineAfter, final Executor executor,
                         final BridgeTelemetry telemetry, final BiConsumer<String, Throwable> warn) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.quarantineAfter = Objects.requireNonNull(quarantineAfter, "quarantineAfter");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.warn = Objects.requireNonNull(warn, "warn");
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
        this.admitted.add(plugin);
        this.telemetry.register(plugin);
        return true;
    }

    public boolean isBridged(final String plugin) {
        return plugin != null && this.admitted.contains(plugin);
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
        final BridgeRouter.Route route = !isBridged(plugin) || quarantined(plugin)
            ? BridgeRouter.Route.REJECT
            : BridgeRouter.route(this.mode, task.sync() ? BridgeRouter.Operation.syncTask()
                                                          : BridgeRouter.Operation.asyncTask());
        if (route == BridgeRouter.Route.REJECT) {
            this.telemetry.rejected(plugin);
            task.markCancelled();
            return false;
        }
        final long delay = Math.max(0L, delayTicks);
        final long period = task.period() > 0 ? task.period() : 0L;
        // Recorded before it is scheduled: a task with no delay can run, finish or be cancelled
        // before the executor call returns, and each of those paths looks the entry up.
        final LateHandle handle = new LateHandle();
        this.scheduled.put(task.id(), new Scheduled(plugin, handle, task));
        final Runnable body = () -> runGuarded(plugin, task, period > 0);
        handle.bind(route == BridgeRouter.Route.IO_LANE
            ? this.executor.async(owner, body, delay, period)
            : this.executor.global(owner, body, delay, period));
        this.telemetry.redirect(plugin);
        return true;
    }

    /** A handle that may be cancelled before the executor has produced the real one. */
    private static final class LateHandle implements Handle {
        private Handle delegate;
        private boolean cancelled;

        synchronized void bind(final Handle real) {
            this.delegate = real;
            if (this.cancelled) real.cancel();
        }

        @Override
        public synchronized void cancel() {
            this.cancelled = true;
            if (this.delegate != null) this.delegate.cancel();
        }
    }

    private void runGuarded(final String plugin, final Task task, final boolean repeating) {
        if (task.cancelled() || quarantined(plugin)) {
            cancel(task.id());
            return;
        }
        try {
            task.run();
        } catch (final Throwable thrown) {
            onFailure(plugin, thrown, task.id());
        } finally {
            if (!repeating) {
                this.scheduled.remove(task.id());
            }
        }
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
        final Scheduled entry = this.scheduled.remove(taskId);
        if (entry == null) {
            return false;
        }
        entry.task().markCancelled();
        entry.handle().cancel();
        return true;
    }

    /** Cancels every bridged task of a plugin, e.g. on disable or quarantine. */
    public int cancelAll(final String plugin) {
        int cancelled = 0;
        for (final Map.Entry<Integer, Scheduled> entry : this.scheduled.entrySet()) {
            if (entry.getValue().plugin().equals(plugin) && cancel(entry.getKey())) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Bridged tasks currently scheduled for a plugin. */
    public int pending(final String plugin) {
        return (int)this.scheduled.values().stream().filter(s -> s.plugin().equals(plugin)).count();
    }
}
