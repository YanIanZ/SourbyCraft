package dev.iyanz.sourbycraft.swm.server;

import dev.iyanz.sourbycraft.swm.api.SlimeChunk;
import dev.iyanz.sourbycraft.swm.api.SlimeChunkSection;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public record PartiallySerializedSlimeChunk(
        int x, int z,
        List<SlimeChunkSection> sections,
        @Nullable CompoundTag heightMap,
        CompoundTag tileEntities,
        @Nullable ListTag entities,
        CompoundTag extra,
        @Nullable CompoundTag upgradeData,
        @Nullable CompoundTag poiChunk,
        @Nullable CompoundTag blockTicks,
        @Nullable CompoundTag fluidTicks
) implements SlimeChunk {

    public static PartiallySerializedSlimeChunk of(NMSSlimeChunk slimeChunk, boolean saveBlockTicks, boolean saveFluidTicks, boolean savePoi) {
        LevelChunk chunk = slimeChunk.getChunk();

        List<SlimeChunkSection> sections = new ArrayList<>();
        LevelLightEngine lightEngine = chunk.level.getChunkSource().getLightEngine();

        for (int sectionId = 0; sectionId < chunk.getSections().length; sectionId++) {
            LevelChunkSection section = chunk.getSections()[sectionId];
            if (section == null) continue;

            byte[] blockLight = null;
            DataLayer blockLayer = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(chunk.getPos(), sectionId));
            if (blockLayer != null) {
                blockLight = blockLayer.getData().clone();
            }

            byte[] skyLight = null;
            DataLayer skyLayer = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(chunk.getPos(), sectionId));
            if (skyLayer != null) {
                skyLight = skyLayer.getData().clone();
            }

            sections.add(SlimeChunkConverter.convertChunkSection(
                    chunk.level.palettedContainerFactory().biomeContainerCodec(),
                    chunk.level.palettedContainerFactory().blockStatesContainerCodec(),
                    section, blockLight, skyLight));
        }

        CompoundTag serializedPoiChunk = savePoi ? slimeChunk.getPoiChunkSections() : null;
        ListTag entities = slimeChunk.getEntities();
        CompoundTag extra = slimeChunk.getExtraData().copy();

        return new PartiallySerializedSlimeChunk(
                chunk.locX,
                chunk.locZ,
                sections,
                slimeChunk.getHeightMaps(),
                slimeChunk.getTileEntities(),
                entities,
                extra,
                slimeChunk.getUpgradeData(),
                serializedPoiChunk,
                null,
                null
        );
    }

    @Override
    public int getX() {
        return this.x;
    }

    @Override
    public int getZ() {
        return this.z;
    }

    @Override
    public List<? extends SlimeChunkSection> getSections() {
        return this.sections;
    }

    @Override
    public CompoundTag getHeightMaps() {
        return this.heightMap;
    }

    @Override
    public CompoundTag getTileEntities() {
        return this.tileEntities;
    }

    @Override
    public ListTag getEntities() {
        return this.entities;
    }

    @Override
    public CompoundTag getExtraData() {
        return this.extra;
    }

    @Override
    public @Nullable CompoundTag getUpgradeData() {
        return this.upgradeData;
    }

    @Override
    public @Nullable CompoundTag getBlockTicks() {
        return this.blockTicks;
    }

    @Override
    public @Nullable CompoundTag getFluidTicks() {
        return this.fluidTicks;
    }

    @Override
    public @Nullable CompoundTag getPoiChunkSections() {
        return this.poiChunk;
    }
}
