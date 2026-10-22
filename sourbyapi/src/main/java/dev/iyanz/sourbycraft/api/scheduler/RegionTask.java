package dev.iyanz.sourbycraft.api.scheduler;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.NullMarked;

/**
 * A synchronous Bukkit scheduler callback that explicitly names its owning chunk for the Aurora
 * Bridge. The scheduler follows that chunk's current region when the callback becomes due.
 *
 * <p>The callback may access only state owned by that region. Work on one entity should use
 * {@link EntityTask}, which follows the entity across regions. An asynchronous submission remains asynchronous; this marker does not make async
 * world access safe. This routing applies to plugins admitted by the Aurora Bridge.</p>
 */
@NullMarked
public record RegionTask(World world, int chunkX, int chunkZ, Runnable action) implements Runnable {

    public RegionTask {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(action, "action");
    }

    /** Captures world and chunk coordinates now; later changes to the Location do not move the task. */
    public static RegionTask at(final Location location, final Runnable action) {
        Objects.requireNonNull(location, "location");
        return new RegionTask(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, action);
    }

    @Override
    public void run() {
        this.action.run();
    }
}
