package dev.iyanz.sourbycraft.api.scheduler;

import java.util.Objects;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.NullMarked;

/**
 * A synchronous Bukkit scheduler callback that explicitly names the entity it works on, for the
 * Aurora Bridge. It is the entity counterpart of {@link RegionTask}.
 *
 * <p>When the callback becomes due it runs on the region that owns the entity at that moment, so it
 * follows the entity across regions. If the entity is removed before the callback runs, the
 * callback is not run: the bridged task is cancelled and counted as a rejected operation, never as
 * a region-ownership violation. These are the semantics of the "retired" callback of the entity
 * scheduler ({@link Entity#getScheduler()}). A repeating task stops at that point.</p>
 *
 * <p>The callback may access only that entity and state owned by the region that owns it. An
 * asynchronous submission remains asynchronous, and an explicit global scheduler request remains
 * global; this marker does not make async or global world access safe. This routing applies only
 * to plugins admitted by the Aurora Bridge. An entity target takes precedence over a
 * {@link RegionTask} target and over the caller-region or global fallback.</p>
 */
@NullMarked
public record EntityTask(Entity entity, Runnable action) implements Runnable {

    public EntityTask {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(action, "action");
    }

    /** Wraps {@code action} so that a bridged sync scheduler runs it on {@code entity}'s owner. */
    public static EntityTask of(final Entity entity, final Runnable action) {
        return new EntityTask(entity, action);
    }

    @Override
    public void run() {
        this.action.run();
    }
}
