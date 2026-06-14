package dev.iyanz.sourbycraft.swm.server;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class FastChunkPruner {

    public static boolean canBePruned(SlimeWorld world, LevelChunk chunk) {
        return canBePruned(world, chunk, null);
    }

    public static boolean canBePruned(SlimeWorld world, LevelChunk chunk, ChunkEntitySlices slices) {
        NewChunkHolder chunkHolder = ((ChunkSystemServerLevel) chunk.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunk.getPos().x(), chunk.getPos().z());

        if (chunkHolder == null) {
            return false;
        }

        if (slices == null) {
            slices = chunkHolder.getEntityChunk();
        }

        return chunk.blockEntities.isEmpty() && (slices == null || slices.isEmpty()) && areSectionsEmpty(chunk);
    }

    private static boolean areSectionsEmpty(LevelChunk chunk) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir()) {
                return false;
            }
        }
        return true;
    }
}
