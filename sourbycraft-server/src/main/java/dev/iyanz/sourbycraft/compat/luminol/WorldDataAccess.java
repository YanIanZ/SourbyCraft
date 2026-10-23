package dev.iyanz.sourbycraft.compat.luminol;

import dev.iyanz.sourbycraft.guards.ThreadChecks;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * How the Minecraft feature patch "cross-region world data for relaxed thread checks" reaches a chunk's
 * {@link RegionizedWorldData}: {@code try (var lease = WorldDataAccess.at(level, pos)) { lease.data()... }}.
 *
 * <p>Enforced checks ({@code guards/ThreadChecks.RELAXED_SCOPE == false}, a {@code static final}): the lease is
 * {@code level.getCurrentWorldData()} and {@link Lease#close()} does nothing -- the same call the base made, so
 * an enforced server pays one folded branch and an allocation the JIT scalar-replaces. Relaxed checks: the
 * {@link RegionizedWorldDataPool} of the level answers (LightingLuminol's {@code worldDataPool.getAndHeldReference}
 * / {@code releaseReference} pairs, minecraft patch 0001 at revision 72d51c4d), so an access made from the wrong
 * thread reaches the region that owns the chunk instead of the caller's region (a region thread) or
 * {@code null} (the global tick thread or an async thread).</p>
 */
public final class WorldDataAccess {

    private WorldDataAccess() {}

    /** A world-data reference held for the duration of a {@code try} block. */
    public static final class Lease implements AutoCloseable {
        private final RegionizedWorldData data;
        private final RegionizedWorldDataPool pool;
        private final RegionizedWorldDataPool.Lease held;

        private Lease(final RegionizedWorldData data, final RegionizedWorldDataPool pool, final RegionizedWorldDataPool.Lease held) {
            this.data = data;
            this.pool = pool;
            this.held = held;
        }

        /** The region's data; {@code null} only on an enforced server off every region (as the base behaved). */
        public RegionizedWorldData data() {
            return this.data;
        }

        @Override
        public void close() {
            if (this.held != null) {
                this.pool.release(this.held);
            }
        }
    }

    public static Lease at(final ServerLevel level, final int chunkX, final int chunkZ) {
        if (!ThreadChecks.RELAXED_SCOPE) {
            return new Lease(level.getCurrentWorldData(), null, null);
        }
        final RegionizedWorldDataPool pool = level.sourbycraft$worldDataPool();
        final RegionizedWorldDataPool.Lease held = pool.acquire(chunkX, chunkZ);
        return new Lease(held.data(), pool, held);
    }

    public static Lease at(final ServerLevel level, final BlockPos pos) {
        return at(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static Lease at(final ServerLevel level, final Entity entity) {
        return at(level, entity.chunkPosition().x(), entity.chunkPosition().z());
    }

    /** The entity's own level. */
    public static Lease at(final Entity entity) {
        return at((ServerLevel) entity.level(), entity);
    }
}
