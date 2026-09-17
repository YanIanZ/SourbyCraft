package dev.iyanz.sourbycraft.execution;

import io.papermc.paper.threadedregions.EntityScheduler;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;

/**
 * {@link OwnerHandoff} over the region backend's per-entity scheduler.
 *
 * <p>This is the only place SourbyCraft names {@code EntityScheduler}. Everything above it
 * depends on the contract, so replacing the backend is an edit here rather than a search for
 * every call site.</p>
 */
public final class RegionOwnerHandoff implements OwnerHandoff<Entity> {

    // Not scheduleOrExecute: that runs the task inline when the caller already owns the
    // entity, which would mutate gameplay on the completing worker, and it provides no
    // retirement callback for an entity removed after admission but before the next
    // owning-region tick.
    private static final long NEXT_OWNING_TICK = 1L;

    private final EntityScheduler scheduler;

    /**
     * The handoff for one entity, so callers never name the backend scheduler themselves.
     *
     * @param entity the subject whose owning context should receive the work
     * @return a handoff bound to that entity
     */
    public static RegionOwnerHandoff forEntity(final Entity entity) {
        return new RegionOwnerHandoff(entity.getBukkitEntity().taskScheduler);
    }


    public RegionOwnerHandoff(final EntityScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public Admission submit(final Consumer<? super Entity> delivery, final Runnable retirement) {
        try {
            // The backend hands the entity to the retirement callback too. The contract keeps
            // retirement a Runnable on purpose: that callback runs in a context where touching
            // the entity is not allowed, so it is not given one to touch.
            return this.scheduler.schedule(delivery::accept, retiredOwner -> retirement.run(),
                NEXT_OWNING_TICK) ? Admission.ACCEPTED : Admission.REJECTED;
        } catch (final RuntimeException failure) {
            // A backend that throws has not taken the work, so it is a refusal and the
            // caller still owns its bookkeeping. Reporting it as accepted would strand the
            // caller waiting for a callback that cannot arrive.
            return Admission.REJECTED;
        }
    }
}
