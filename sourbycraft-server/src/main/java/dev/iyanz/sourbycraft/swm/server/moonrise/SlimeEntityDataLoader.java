package dev.iyanz.sourbycraft.swm.server.moonrise;

import ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.EntityDataController;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import dev.iyanz.sourbycraft.swm.api.SlimeChunk;
import dev.iyanz.sourbycraft.swm.server.SlimeLevelInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Moonrise EntityDataController extension for SWM-backed levels. Serves entity
 * data straight out of the in-memory {@link SlimeChunk#getEntities()} listing
 * so the chunk-system loader doesn't try to fetch entities from a region file
 * that doesn't exist for SWM worlds.
 *
 * <p>Save side is deliberately throwing — SWM's custom serialisation pipeline
 * is the only sanctioned write path; moonrise's per-chunk save would race
 * against fast world unloads and the data would never reach disk anyway.
 *
 * <p>Adapted from
 * {@code com.infernalsuite.asp.level.moonrise.SlimeEntityDataLoader}
 * (AdvancedSlimePaper dev/26.2). The ASP original uses Adventure NBT and
 * converts to NMS at read time; our fork already stores NMS {@link ListTag}
 * in {@code SlimeChunk.getEntities()}, so the conversion step is dropped.
 */
public class SlimeEntityDataLoader extends EntityDataController {

    private final SlimeLevelInstance instance;

    public SlimeEntityDataLoader(EntityRegionFileStorage storage, ChunkTaskScheduler taskScheduler, SlimeLevelInstance instance) {
        super(storage, taskScheduler);
        this.instance = instance;
    }

    @Override
    public WriteData startWrite(int chunkX, int chunkZ, CompoundTag compound) {
        // SWM owns its own save pipeline. Moonrise per-chunk writes would race
        // against fast world unloads, so block here loudly if something tries.
        throw new UnsupportedOperationException(
                "Slime worlds use the SWM serialisation pipeline; moonrise per-chunk entity writes are not supported.");
    }

    @Override
    public ReadData readData(int chunkX, int chunkZ) {
        SlimeChunk chunk = instance.slimeInstance.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return new ReadData(ReadData.ReadResult.NO_DATA, null, null, 0);
        }
        ListTag entities = chunk.getEntities();
        if (entities == null || entities.isEmpty()) {
            return new ReadData(ReadData.ReadResult.NO_DATA, null, null, 0);
        }

        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Position", new int[]{chunkX, chunkZ});
        tag.putInt("DataVersion", instance.slimeInstance.getDataVersion());
        tag.put("Entities", entities.copy());
        return new ReadData(ReadData.ReadResult.SYNC_READ, null, tag, 0);
    }

    @Override
    public CompoundTag finishRead(int chunkX, int chunkZ, ReadData readData) {
        return readData.syncRead();
    }
}
