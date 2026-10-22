package dev.iyanz.sourbycraft.execution.region;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Chunk-loading continuation used by command hooks outside an owned command-block scope. */
public interface RegionChunkLoading {

    /** @return the active chunk-loading adapter */
    static RegionChunkLoading current() {
        return MoonriseRegionOwnership.INSTANCE;
    }

    /**
     * Loads a footprint using the backend's existing completion and scheduling semantics.
     * Unlike an entity handoff, this does not promise delivery on a particular entity's owner.
     *
     * @param level the world whose chunks are requested
     * @param minChunkX minimum chunk X, inclusive
     * @param maxChunkX maximum chunk X, inclusive
     * @param minChunkZ minimum chunk Z, inclusive
     * @param maxChunkZ maximum chunk Z, inclusive
     * @param status the required chunk status
     * @param priority the opaque backend priority supplied by the upstream patch call site;
     *                 callers pass it through unchanged, and only the adapter interprets it
     * @param onLoad the original completion, with the backend's loaded chunk list
     */
    void loadChunksAsync(ServerLevel level, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
                         ChunkStatus status, Object priority, Consumer<List<ChunkAccess>> onLoad);
}
