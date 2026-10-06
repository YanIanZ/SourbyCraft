/*
 * Adapted from LightingLuminol's FoliaSchedulerCompatibility by Bacteriawa
 * <A3167717663@hotmail.com>, commit 72d51c4d11b1ddfe70cde21f4736fdf660873cb3.
 * LightingLuminol / Luminol: GNU General Public License version 3.
 * SourbyCraft adaptations: global ownership, bridge cancellation notification,
 * shared task indexes and bridge exception/quarantine handling.
 * See META-INF/licenses/NOTICE_LIGHTINGLUMINOL and LICENSE_LIGHTINGLUMINOL_GPL.
 */
package dev.iyanz.sourbycraft.bridge;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** LightingLuminol's Bukkit-backed ScheduledTask adapter, restricted to global requests. */
public final class FoliaSchedulerCompatibility {

    private FoliaSchedulerCompatibility() {}

    /** Native region/entity schedulers retain their owner and retirement semantics. */
    public static boolean shouldUseBukkitScheduler(final Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        return AuroraBridge.handles(plugin);
    }

    /** Internal marker consumed by CraftScheduler's bridge adapter. */
    public sealed interface GlobalCallback extends Runnable permits BukkitBackedScheduledTask {
        /** Called when the bridge cancels/rejects the backing Bukkit task. */
        void bridgeCancelled();
    }

    public static ScheduledTask runDelayedOnBukkit(final Plugin plugin, final Consumer<ScheduledTask> task,
                                                   final long delayTicks) {
        validate(plugin, task);
        if (delayTicks <= 0) throw new IllegalArgumentException("Delay ticks may not be <= 0");
        final BukkitBackedScheduledTask ret = new BukkitBackedScheduledTask(plugin, -1, task);
        ret.setBukkitTask(Bukkit.getScheduler().runTaskLater(plugin, ret, delayTicks));
        if (!plugin.isEnabled()) ret.cancel();
        return ret;
    }

    public static ScheduledTask runAtFixedRateOnBukkit(final Plugin plugin, final Consumer<ScheduledTask> task,
                                                       final long initialDelayTicks, final long periodTicks) {
        validate(plugin, task);
        if (initialDelayTicks <= 0) throw new IllegalArgumentException("Initial delay ticks may not be <= 0");
        if (periodTicks <= 0) throw new IllegalArgumentException("Period ticks may not be <= 0");
        final BukkitBackedScheduledTask ret = new BukkitBackedScheduledTask(plugin, periodTicks, task);
        ret.setBukkitTask(Bukkit.getScheduler().runTaskTimer(plugin, ret, initialDelayTicks, periodTicks));
        if (!plugin.isEnabled()) ret.cancel();
        return ret;
    }

    private static void validate(final Plugin plugin, final Consumer<ScheduledTask> task) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        Objects.requireNonNull(task, "Task may not be null");
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
        }
    }

    private static final class BukkitBackedScheduledTask implements ScheduledTask, GlobalCallback {
        private static final int STATE_IDLE = 0;
        private static final int STATE_EXECUTING = 1;
        private static final int STATE_EXECUTING_CANCELLED = 2;
        private static final int STATE_FINISHED = 3;
        private static final int STATE_CANCELLED = 4;

        private final Plugin plugin;
        private final long repeatDelay;
        private volatile Consumer<ScheduledTask> run;
        private volatile BukkitTask bukkitTask;
        private volatile int state = STATE_IDLE;

        private BukkitBackedScheduledTask(final Plugin plugin, final long repeatDelay,
                                          final Consumer<ScheduledTask> run) {
            this.plugin = plugin;
            this.repeatDelay = repeatDelay;
            this.run = run;
        }

        private void setBukkitTask(final BukkitTask task) {
            this.bukkitTask = task;
            final int current = this.state;
            if (current == STATE_CANCELLED || current == STATE_EXECUTING_CANCELLED) task.cancel();
        }

        @Override
        public void run() {
            if (!this.plugin.isEnabled()) {
                this.cancel();
                return;
            }
            if (!this.compareAndSetState(STATE_IDLE, STATE_EXECUTING)) return;
            try {
                // Let the bridge classify/log failures and apply per-plugin quarantine.
                this.run.accept(this);
            } finally {
                if (!this.isRepeatingTask()) {
                    this.clearTask(STATE_FINISHED);
                } else {
                    if (!this.plugin.isEnabled()) this.cancel();
                    if (!this.compareAndSetState(STATE_EXECUTING, STATE_IDLE)) {
                        this.clearTask(STATE_CANCELLED);
                    }
                }
            }
        }

        @Override
        public Plugin getOwningPlugin() { return this.plugin; }

        @Override
        public boolean isRepeatingTask() { return this.repeatDelay > 0; }

        @Override
        public CancelledState cancel() {
            for (;;) {
                switch (this.state) {
                    case STATE_IDLE:
                        if (!this.compareAndSetState(STATE_IDLE, STATE_CANCELLED)) continue;
                        this.run = null;
                        this.cancelBukkitTask();
                        return CancelledState.CANCELLED_BY_CALLER;
                    case STATE_EXECUTING:
                        if (!this.isRepeatingTask()) return CancelledState.RUNNING;
                        if (!this.compareAndSetState(STATE_EXECUTING, STATE_EXECUTING_CANCELLED)) continue;
                        this.cancelBukkitTask();
                        return CancelledState.NEXT_RUNS_CANCELLED;
                    case STATE_EXECUTING_CANCELLED:
                        return CancelledState.NEXT_RUNS_CANCELLED_ALREADY;
                    case STATE_FINISHED:
                        return CancelledState.ALREADY_EXECUTED;
                    case STATE_CANCELLED:
                        return CancelledState.CANCELLED_ALREADY;
                    default:
                        throw new IllegalStateException("Unknown state: " + this.state);
                }
            }
        }

        @Override
        public void bridgeCancelled() {
            for (;;) {
                final int current = this.state;
                if (current == STATE_IDLE) {
                    if (!this.compareAndSetState(STATE_IDLE, STATE_CANCELLED)) continue;
                    this.run = null;
                } else if (current == STATE_EXECUTING && this.isRepeatingTask()) {
                    if (!this.compareAndSetState(STATE_EXECUTING, STATE_EXECUTING_CANCELLED)) continue;
                }
                // A running one-shot finishes normally; cancellation never interrupts its body.
                return;
            }
        }

        @Override
        public ExecutionState getExecutionState() {
            return switch (this.state) {
                case STATE_IDLE -> ExecutionState.IDLE;
                case STATE_EXECUTING -> ExecutionState.RUNNING;
                case STATE_EXECUTING_CANCELLED -> ExecutionState.CANCELLED_RUNNING;
                case STATE_FINISHED -> ExecutionState.FINISHED;
                case STATE_CANCELLED -> ExecutionState.CANCELLED;
                default -> throw new IllegalStateException("Unknown state: " + this.state);
            };
        }

        private synchronized boolean compareAndSetState(final int expected, final int updated) {
            if (this.state != expected) return false;
            this.state = updated;
            return true;
        }

        private void clearTask(final int finalState) {
            this.run = null;
            this.state = finalState;
        }

        private void cancelBukkitTask() {
            final BukkitTask task = this.bukkitTask;
            if (task != null) task.cancel();
        }
    }
}
