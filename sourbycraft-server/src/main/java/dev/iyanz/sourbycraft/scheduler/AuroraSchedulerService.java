package dev.iyanz.sourbycraft.scheduler;

import dev.iyanz.sourbycraft.api.scheduler.AuroraScheduler;
import dev.iyanz.sourbycraft.api.scheduler.TaskHandle;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Build 55: {@link AuroraScheduler} over Paper's public region-threading schedulers. Every call is delegated as it
 * is made, to {@link Bukkit#getRegionScheduler()}, {@link Entity#getScheduler()},
 * {@link Bukkit#getGlobalRegionScheduler()} or {@link Bukkit#getAsyncScheduler()}; this class holds no queue, no
 * thread and no task list, so it adds no capacity of its own and its tasks are cancelled with their plugin exactly
 * as the underlying schedulers cancel them.
 *
 * <p>The only translation is of delays: the facade's {@code 0} ("next tick") becomes the underlying schedulers'
 * minimum of one tick, and a negative delay or a period below one is refused before anything is scheduled.</p>
 *
 * <p>A handle cancelled before its task first ran never runs it, even when the cancel raced the scheduler's own
 * binding of the task. {@code runFor*} on an entity that was already removed schedules nothing: neither the task nor
 * {@code retired} runs and the handle reports cancelled ({@link AuroraScheduler#runFor(Plugin, Entity, Runnable, Runnable)}).</p>
 */
@NullMarked
public final class AuroraSchedulerService implements AuroraScheduler {

    /** The registered instance; stateless, so one is enough. */
    public static final AuroraSchedulerService INSTANCE = new AuroraSchedulerService();

    private static volatile boolean registered;

    private AuroraSchedulerService() {
    }

    /**
     * Registers {@link AuroraScheduler} in Bukkit's services manager, owned by {@code owner} (the server's internal
     * plugin). Called once at startup, before plugins load; a second call does nothing.
     */
    public static synchronized void register(final Plugin owner) {
        if (registered) return;
        Bukkit.getServicesManager().register(AuroraScheduler.class, INSTANCE, owner, ServicePriority.Normal);
        registered = true;
    }

    // ------------------------------------------------------------------ region of a location

    @Override
    public TaskHandle runAt(final Plugin plugin, final Location location, final Runnable task) {
        final World world = world(location);
        Objects.requireNonNull(task, "task");
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getRegionScheduler().run(plugin, world, location.getBlockX() >> 4, location.getBlockZ() >> 4,
            once(handle, task)));
        return handle;
    }

    @Override
    public TaskHandle runAtLater(final Plugin plugin, final Location location, final Runnable task, final long delayTicks) {
        final World world = world(location);
        Objects.requireNonNull(task, "task");
        final long delay = delay(delayTicks);
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getRegionScheduler().runDelayed(plugin, world, location.getBlockX() >> 4,
            location.getBlockZ() >> 4, once(handle, task), delay));
        return handle;
    }

    @Override
    public TaskHandle runAtTimer(final Plugin plugin, final Location location, final Consumer<TaskHandle> task,
                                 final long delayTicks, final long periodTicks) {
        final World world = world(location);
        Objects.requireNonNull(task, "task");
        final long delay = delay(delayTicks);
        final long period = period(periodTicks);
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getRegionScheduler().runAtFixedRate(plugin, world, location.getBlockX() >> 4,
            location.getBlockZ() >> 4, repeating(handle, task), delay, period));
        return handle;
    }

    @Override
    public TaskHandle runAt(final Plugin plugin, final World world, final int chunkX, final int chunkZ, final Runnable task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, once(handle, task)));
        return handle;
    }

    @Override
    public TaskHandle runAtLater(final Plugin plugin, final World world, final int chunkX, final int chunkZ,
                                 final Runnable task, final long delayTicks) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        final long delay = delay(delayTicks);
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, once(handle, task), delay));
        return handle;
    }

    @Override
    public TaskHandle runAtTimer(final Plugin plugin, final World world, final int chunkX, final int chunkZ,
                                 final Consumer<TaskHandle> task, final long delayTicks, final long periodTicks) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        final long delay = delay(delayTicks);
        final long period = period(periodTicks);
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getRegionScheduler().runAtFixedRate(plugin, world, chunkX, chunkZ, repeating(handle, task),
            delay, period));
        return handle;
    }

    // ------------------------------------------------------------------ an entity

    @Override
    public TaskHandle runFor(final Plugin plugin, final Entity entity, final Runnable task, final @Nullable Runnable retired) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        final Handle handle = new Handle(plugin);
        handle.bindOrUnscheduled(entity.getScheduler().run(plugin, once(handle, task), retired));
        return handle;
    }

    @Override
    public TaskHandle runForLater(final Plugin plugin, final Entity entity, final Runnable task, final long delayTicks,
                                  final @Nullable Runnable retired) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        final long delay = delay(delayTicks);
        final Handle handle = new Handle(plugin);
        handle.bindOrUnscheduled(entity.getScheduler().runDelayed(plugin, once(handle, task), retired, delay));
        return handle;
    }

    @Override
    public TaskHandle runForTimer(final Plugin plugin, final Entity entity, final Consumer<TaskHandle> task,
                                  final long delayTicks, final long periodTicks, final @Nullable Runnable retired) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        final long delay = delay(delayTicks);
        final long period = period(periodTicks);
        final Handle handle = new Handle(plugin);
        handle.bindOrUnscheduled(entity.getScheduler().runAtFixedRate(plugin, repeating(handle, task), retired, delay,
            period));
        return handle;
    }

    // ------------------------------------------------------------------ the global region

    @Override
    public TaskHandle runGlobal(final Plugin plugin, final Runnable task) {
        Objects.requireNonNull(task, "task");
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getGlobalRegionScheduler().run(plugin, once(handle, task)));
        return handle;
    }

    @Override
    public TaskHandle runGlobalLater(final Plugin plugin, final Runnable task, final long delayTicks) {
        Objects.requireNonNull(task, "task");
        final long delay = delay(delayTicks);
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, once(handle, task), delay));
        return handle;
    }

    @Override
    public TaskHandle runGlobalTimer(final Plugin plugin, final Consumer<TaskHandle> task, final long delayTicks,
                                     final long periodTicks) {
        Objects.requireNonNull(task, "task");
        final long delay = delay(delayTicks);
        final long period = period(periodTicks);
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, repeating(handle, task), delay, period));
        return handle;
    }

    // ------------------------------------------------------------------ off the regions

    @Override
    public TaskHandle runAsync(final Plugin plugin, final Runnable task) {
        Objects.requireNonNull(task, "task");
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getAsyncScheduler().runNow(plugin, once(handle, task)));
        return handle;
    }

    @Override
    public TaskHandle runAsyncLater(final Plugin plugin, final Runnable task, final Duration delay) {
        Objects.requireNonNull(task, "task");
        final long nanos = nanos(delay, "delay", false);
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getAsyncScheduler().runDelayed(plugin, once(handle, task), nanos, TimeUnit.NANOSECONDS));
        return handle;
    }

    @Override
    public TaskHandle runAsyncTimer(final Plugin plugin, final Consumer<TaskHandle> task, final Duration delay,
                                    final Duration period) {
        Objects.requireNonNull(task, "task");
        final long initial = nanos(delay, "delay", false);
        final long every = nanos(period, "period", true);
        final Handle handle = new Handle(plugin);
        handle.bind(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, repeating(handle, task), initial, every,
            TimeUnit.NANOSECONDS));
        return handle;
    }

    // ------------------------------------------------------------------ ownership

    @Override
    public boolean isOwnedByCurrentRegion(final Location location) {
        return Bukkit.isOwnedByCurrentRegion(Objects.requireNonNull(location, "location"));
    }

    @Override
    public boolean isOwnedByCurrentRegion(final Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(Objects.requireNonNull(entity, "entity"));
    }

    @Override
    public boolean isGlobalTickThread() {
        return Bukkit.isGlobalTickThread();
    }

    // ------------------------------------------------------------------ helpers

    private static World world(final Location location) {
        Objects.requireNonNull(location, "location");
        final World world = location.getWorld();
        if (world == null) throw new IllegalArgumentException("the location has no world");
        return world;
    }

    /** The facade's delay in the underlying schedulers' terms: 0 is the next tick, their minimum of 1. */
    static long delay(final long delayTicks) {
        if (delayTicks < 0) throw new IllegalArgumentException("delayTicks may not be negative: " + delayTicks);
        return Math.max(1L, delayTicks);
    }

    static long period(final long periodTicks) {
        if (periodTicks < 1) throw new IllegalArgumentException("periodTicks must be at least 1: " + periodTicks);
        return periodTicks;
    }

    static long nanos(final Duration duration, final String what, final boolean positive) {
        Objects.requireNonNull(duration, what);
        if (duration.isNegative() || (positive && duration.isZero())) {
            throw new IllegalArgumentException(what + (positive ? " must be positive: " : " may not be negative: ") + duration);
        }
        try {
            return duration.toNanos();
        } catch (final ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
    }

    /** A cancel that raced the first run (the handle was cancelled before the scheduler bound the task) runs nothing. */
    private static Consumer<ScheduledTask> once(final Handle handle, final Runnable task) {
        return scheduled -> {
            handle.bind(scheduled);
            if (handle.cancelledByCaller()) return;
            task.run();
        };
    }

    private static Consumer<ScheduledTask> repeating(final Handle handle, final Consumer<TaskHandle> task) {
        return scheduled -> {
            handle.bind(scheduled);
            if (handle.cancelledByCaller()) return;
            task.accept(handle);
        };
    }

    /**
     * A {@link TaskHandle} over one {@link ScheduledTask}. The task is bound when the scheduler returns it and again,
     * idempotently, when it first runs (a run may begin on another thread before the scheduling call returns). A
     * cancel that comes before the binding is applied by it, so a cancel is never lost.
     */
    static final class Handle implements TaskHandle {

        private final Plugin owner;
        private volatile @Nullable ScheduledTask task;
        private volatile boolean cancelled;
        /** The entity was already removed: nothing was scheduled. */
        private volatile boolean unscheduled;

        Handle(final Plugin owner) {
            this.owner = Objects.requireNonNull(owner, "plugin");
        }

        void bind(final ScheduledTask scheduled) {
            if (this.task == null) this.task = scheduled;
            if (this.cancelled) scheduled.cancel();
        }

        void bindOrUnscheduled(final @Nullable ScheduledTask scheduled) {
            if (scheduled == null) {
                this.unscheduled = true;
                return;
            }
            bind(scheduled);
        }

        boolean cancelledByCaller() {
            return this.cancelled;
        }

        @Override
        public void cancel() {
            this.cancelled = true;
            final ScheduledTask scheduled = this.task;
            if (scheduled != null) scheduled.cancel();
        }

        @Override
        public boolean isCancelled() {
            if (this.cancelled || this.unscheduled) return true;
            final ScheduledTask scheduled = this.task;
            return scheduled != null && scheduled.isCancelled();
        }

        @Override
        public Plugin owner() {
            return this.owner;
        }

        @Override
        public String toString() {
            return "TaskHandle[" + this.owner.getName() + (isCancelled() ? ", cancelled]" : "]");
        }
    }
}
