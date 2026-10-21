package dev.iyanz.sourbycraft.execution.region;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Queries whether the calling thread currently owns an entity or world footprint.
 *
 * <p>An answer describes ownership now; it neither loads chunks nor reserves ownership for
 * later work. Callers must recheck after callbacks that can move or remove the subject.</p>
 */
public interface RegionOwnership {

    /**
     * Returns the active ownership backend for this process.
     *
     * @return the current ownership implementation
     */
    static RegionOwnership current() {
        return RegionOwnershipHolder.CURRENT.get();
    }

    /**
     * Replaces the process-wide backend, allowing tests to supply ownership without a server.
     *
     * @param ownership the non-null replacement; tests must restore the previous backend
     * @return the previous implementation
     */
    static RegionOwnership replace(final RegionOwnership ownership) {
        return RegionOwnershipHolder.CURRENT.getAndSet(Objects.requireNonNull(ownership));
    }

    /**
     * Tests ownership of an entity on the calling thread.
     *
     * @param entity the subject whose current owner is queried
     * @return whether the calling thread owns the entity
     */
    boolean isTickThreadFor(Entity entity);

    /**
     * Tests ownership of the chunk containing a block position.
     *
     * @param level the world containing the block
     * @param pos the block position
     * @return whether the calling thread owns the position
     */
    boolean isTickThreadFor(Level level, BlockPos pos);

    /**
     * Tests ownership of the chunk containing an exact position.
     *
     * @param level the world containing the position
     * @param pos the position in block coordinates
     * @return whether the calling thread owns the position
     */
    boolean isTickThreadFor(Level level, Vec3 pos);

    /**
     * Tests ownership of one chunk without loading it.
     *
     * @param level the world containing the chunk
     * @param chunkX the chunk's X coordinate
     * @param chunkZ the chunk's Z coordinate
     * @return whether the calling thread owns the chunk
     */
    boolean isTickThreadFor(Level level, int chunkX, int chunkZ);

    /**
     * Tests ownership of every chunk in an inclusive rectangle without loading them.
     *
     * @param level the world containing the rectangle
     * @param minChunkX the minimum chunk X coordinate
     * @param minChunkZ the minimum chunk Z coordinate
     * @param maxChunkX the maximum chunk X coordinate
     * @param maxChunkZ the maximum chunk Z coordinate
     * @return whether the calling thread owns the entire rectangle
     */
    boolean isTickThreadFor(Level level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);

    /**
     * Tests ownership of the chunks covered by an entity's movement bounding box.
     *
     * @param level the world containing the box
     * @param box the bounding box in block coordinates, including any caller-supplied margin
     * @return whether the calling thread owns the entire footprint
     */
    boolean isTickThreadFor(Level level, AABB box);

    /**
     * Requires ownership of an entity, retaining the backend's violation diagnostics.
     *
     * @param entity the entity that must be owned by the calling thread
     * @param reason the diagnostic reason for requiring ownership
     * @throws IllegalStateException when the calling thread does not own the entity
     */
    void ensureTickThread(Entity entity, String reason);

    /**
     * Requires that the calling thread is the global tick thread (the work that belongs to no
     * single region), retaining the backend's violation diagnostics.
     *
     * @param reason the diagnostic reason for requiring the global tick
     * @throws IllegalStateException when the calling thread is not the global tick thread
     */
    void ensureGlobalTickThread(String reason);

    /**
     * Runs work owned by the global tick: inline when the calling thread already is the global
     * tick thread, otherwise queued for the global tick with the backend's existing ordering. No
     * retirement callback; the global tick lives as long as the server.
     *
     * @param task the work to run on the global tick
     */
    void executeOnGlobalTick(Runnable task);
}

final class RegionOwnershipHolder {
    static final AtomicReference<RegionOwnership> CURRENT =
        new AtomicReference<>(MoonriseRegionOwnership.INSTANCE);

    private RegionOwnershipHolder() {}
}
