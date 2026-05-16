package dev.iyanz.sourbycraft.swm.server;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import dev.iyanz.sourbycraft.swm.api.SlimeChunk;
import dev.iyanz.sourbycraft.swm.api.SlimeChunkSection;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.storage.TagValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NMSSlimeChunk implements SlimeChunk {
    private static final Logger LOGGER = LoggerFactory.getLogger(NMSSlimeChunk.class);

    private LevelChunk chunk;
    private final CompoundTag extra;
    private final CompoundTag upgradeData;

    public NMSSlimeChunk(LevelChunk chunk, SlimeChunk reference) {
        this.chunk = chunk;
        CompoundTag refExtra = reference == null ? null : reference.getExtraData();
        this.extra = refExtra != null ? refExtra : new CompoundTag();
        this.upgradeData = reference == null ? null : reference.getUpgradeData();
    }

    @Override
    public int getX() {
        return chunk.getPos().x;
    }

    @Override
    public int getZ() {
        return chunk.getPos().z;
    }

    @Override
    public List<? extends SlimeChunkSection> getSections() {
        List<SlimeChunkSection> result = new ArrayList<>();
        LevelLightEngine lightEngine = chunk.level.getChunkSource().getLightEngine();

        LevelChunkSection[] arr = chunk.sections;
        if (arr == null) return result;

        for (int i = 0; i < arr.length; i++) {
            LevelChunkSection section = arr[i];
            if (section == null) continue;

            byte[] blockLight = null;
            DataLayer blockLayer = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(chunk.getPos(), i));
            if (blockLayer != null) blockLight = blockLayer.getData().clone();

            byte[] skyLight = null;
            DataLayer skyLayer = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(chunk.getPos(), i));
            if (skyLayer != null) skyLight = skyLayer.getData().clone();

            result.add(SlimeChunkConverter.convertChunkSection(
                    chunk.level.palettedContainerFactory().biomeContainerCodec(),
                    chunk.level.palettedContainerFactory().blockStatesContainerCodec(),
                    section, blockLight, skyLight));
        }

        return result;
    }

    @Override
    public CompoundTag getHeightMaps() {
        CompoundTag heightMaps = new CompoundTag();
        for (var entry : this.chunk.getHeightmaps()) {
            var type = entry.getKey();
            if (type.keepAfterWorldgen()) {
                heightMaps.putLongArray(type.getSerializedName(), entry.getValue().getRawData());
            }
        }
        return heightMaps;
    }

    @Override
    public CompoundTag getTileEntities() {
        CompoundTag result = new CompoundTag();
        ListTag list = new ListTag();
        for (BlockEntity be : this.chunk.blockEntities.values()) {
            list.add(be.saveWithFullMetadata(net.minecraft.server.MinecraftServer.getServer().registryAccess()));
        }
        result.put("tileEntities", list);
        return result;
    }

    @Override
    public ListTag getEntities() {
        NewChunkHolder chunkHolder = ((ChunkSystemServerLevel) chunk.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunk.getPos().x, chunk.getPos().z);
        if (chunkHolder == null) return new ListTag();
        ChunkEntitySlices slices = chunkHolder.getEntityChunk();
        return getEntities(slices);
    }

    public ListTag getEntities(ChunkEntitySlices slices) {
        if (slices == null) return new ListTag();
        ListTag entities = new ListTag();

        try (ProblemReporter.ScopedCollector scopedCollector = new ProblemReporter.ScopedCollector(ChunkAccess.problemPath(chunk.getPos()), LOGGER)) {
            for (Entity entity : slices.getAllEntities()) {
                try {
                    TagValueOutput tagValueOutput = TagValueOutput.createWithContext(scopedCollector, entity.registryAccess());
                    if (entity.save(tagValueOutput)) {
                        entities.add(tagValueOutput.buildResult());
                    }
                } catch (Exception e) {
                    LOGGER.error("Could not save entity {}: {}", entity, e);
                }
            }
        }

        return entities;
    }

    @Override
    public CompoundTag getExtraData() {
        return extra;
    }

    @Override
    public CompoundTag getUpgradeData() {
        return upgradeData;
    }

    @Override
    public CompoundTag getBlockTicks() {
        return null;
    }

    @Override
    public CompoundTag getFluidTicks() {
        return null;
    }

    @Override
    public CompoundTag getPoiChunkSections() {
        NewChunkHolder chunkHolder = ((ChunkSystemServerLevel) chunk.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunk.getPos().x, chunk.getPos().z);
        if (chunkHolder == null) return null;
        PoiChunk poiChunk = chunkHolder.getPoiChunk();
        if (poiChunk == null) return null;
        return SlimeChunkConverter.toSlimeSections(poiChunk);
    }

    public LevelChunk getChunk() {
        return chunk;
    }

    public void setChunk(LevelChunk chunk) {
        this.chunk = chunk;
    }
}
