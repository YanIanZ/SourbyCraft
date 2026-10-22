package dev.iyanz.sourbycraft.execution.region;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Today's ownership queries, global-tick handoff and chunk-loading continuation behind Aurora's region contracts. */
public final class MoonriseRegionOwnership implements RegionOwnership, RegionChunkLoading {
    static final MoonriseRegionOwnership INSTANCE = new MoonriseRegionOwnership();

    @Override
    public boolean isTickThreadFor(final Entity entity) {
        return TickThread.isTickThreadFor(entity);
    }

    @Override
    public boolean isTickThreadFor(final Level level, final BlockPos pos) {
        return TickThread.isTickThreadFor(level, pos);
    }

    @Override
    public boolean isTickThreadFor(final Level level, final Vec3 pos) {
        return TickThread.isTickThreadFor(level, pos);
    }

    @Override
    public boolean isTickThreadFor(final Level level, final int chunkX, final int chunkZ) {
        return TickThread.isTickThreadFor(level, chunkX, chunkZ);
    }

    @Override
    public boolean isTickThreadFor(final Level level, final int minChunkX, final int minChunkZ,
                                   final int maxChunkX, final int maxChunkZ) {
        return TickThread.isTickThreadFor(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    @Override
    public boolean isTickThreadFor(final Level level, final AABB box) {
        return TickThread.isTickThreadFor(level, box);
    }

    @Override
    public void ensureTickThread(final Entity entity, final String reason) {
        TickThread.ensureTickThread(entity, reason);
    }

    @Override
    public void ensureGlobalTickThread(final String reason) {
        FoliaRegionBackend.ensureGlobalTickThread(reason);
    }

    @Override
    public boolean isGlobalTickThread() {
        return FoliaRegionBackend.isGlobalTickThread();
    }

    @Override
    public boolean isTickThread() {
        return TickThread.isTickThread();
    }

    @Override
    public boolean regionsTicking() {
        return FoliaRegionBackend.regionsStarted();
    }

    @Override
    public boolean isShutdownThread() {
        return TickThread.isShutdownThread();
    }

    @Override
    public void executeOnGlobalTick(final Runnable task) {
        FoliaRegionBackend.executeOnGlobalTick(task);
    }

    @Override
    public void loadChunksAsync(final ServerLevel level, final int minChunkX, final int maxChunkX,
                                final int minChunkZ, final int maxChunkZ, final ChunkStatus status,
                                final Object priority, final Consumer<List<ChunkAccess>> onLoad) {
        level.moonrise$loadChunksAsync(minChunkX, maxChunkX, minChunkZ, maxChunkZ, status, (Priority) priority, onLoad);
    }
}
