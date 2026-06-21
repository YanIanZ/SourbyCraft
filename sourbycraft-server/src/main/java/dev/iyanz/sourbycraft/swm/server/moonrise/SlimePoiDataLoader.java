package dev.iyanz.sourbycraft.swm.server.moonrise;

import ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.PoiDataController;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import dev.iyanz.sourbycraft.swm.api.SlimeChunk;
import dev.iyanz.sourbycraft.swm.server.SlimeChunkConverter;
import dev.iyanz.sourbycraft.swm.server.SlimeLevelInstance;
import net.minecraft.nbt.CompoundTag;

/**
 * Moonrise PoiDataController extension for SWM-backed levels. Streams POI data
 * directly from {@link SlimeChunk#getPoiChunkSections()} so the chunk-system
 * loader doesn't try to read POI from a region file that doesn't exist for SWM
 * worlds. Adapted from {@code com.infernalsuite.asp.level.moonrise.SlimePoiDataLoader}.
 */
public class SlimePoiDataLoader extends PoiDataController {

    private final SlimeLevelInstance instance;

    public SlimePoiDataLoader(SlimeLevelInstance instance, ChunkTaskScheduler taskScheduler) {
        super(instance, taskScheduler);
        this.instance = instance;
    }

    @Override
    public WriteData startWrite(int chunkX, int chunkZ, CompoundTag compound) {
        throw new UnsupportedOperationException(
                "Slime worlds use the SWM serialisation pipeline; moonrise per-chunk POI writes are not supported.");
    }

    @Override
    public ReadData readData(int chunkX, int chunkZ) {
        SlimeChunk chunk = instance.slimeInstance.getChunk(chunkX, chunkZ);
        if (chunk == null || chunk.getPoiChunkSections() == null) {
            return new ReadData(ReadData.ReadResult.NO_DATA, null, null, 0);
        }
        CompoundTag tag = SlimeChunkConverter.createPoiChunk(chunk);
        return new ReadData(ReadData.ReadResult.SYNC_READ, null, tag, 0);
    }

    @Override
    public CompoundTag finishRead(int chunkX, int chunkZ, ReadData readData) {
        return readData.syncRead();
    }
}
