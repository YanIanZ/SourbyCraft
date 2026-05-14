package dev.iyanz.sourbycraft.swm.core;

import dev.iyanz.sourbycraft.swm.api.SlimeChunk;
import dev.iyanz.sourbycraft.swm.api.SlimeChunkSection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;
import java.util.List;

public class SlimeChunkSkeleton implements SlimeChunk {
    private final int x;
    private final int z;
    private final List<SlimeChunkSection> sections;
    private final CompoundTag heightMaps;
    private final CompoundTag tileEntities;
    private final ListTag entities;
    private final @Nullable CompoundTag extraData;
    private final @Nullable CompoundTag upgradeData;
    private final @Nullable CompoundTag blockTicks;
    private final @Nullable CompoundTag fluidTicks;
    private final @Nullable CompoundTag poiChunk;

    public SlimeChunkSkeleton(
        int x, int z,
        List<SlimeChunkSection> sections,
        CompoundTag heightMaps,
        CompoundTag tileEntities,
        ListTag entities,
        @Nullable CompoundTag extraData,
        @Nullable CompoundTag upgradeData,
        @Nullable CompoundTag blockTicks,
        @Nullable CompoundTag fluidTicks,
        @Nullable CompoundTag poiChunk
    ) {
        this.x = x;
        this.z = z;
        this.sections = sections;
        this.heightMaps = heightMaps;
        this.tileEntities = tileEntities;
        this.entities = entities;
        this.extraData = extraData;
        this.upgradeData = upgradeData;
        this.blockTicks = blockTicks;
        this.fluidTicks = fluidTicks;
        this.poiChunk = poiChunk;
    }

    @Override public int getX() { return x; }
    @Override public int getZ() { return z; }
    @Override public List<SlimeChunkSection> getSections() { return sections; }
    @Override public CompoundTag getHeightMaps() { return heightMaps; }
    @Override public CompoundTag getTileEntities() { return tileEntities; }
    @Override public ListTag getEntities() { return entities; }
    @Override public @Nullable CompoundTag getExtraData() { return extraData; }
    @Override public @Nullable CompoundTag getUpgradeData() { return upgradeData; }
    @Override public @Nullable CompoundTag getBlockTicks() { return blockTicks; }
    @Override public @Nullable CompoundTag getFluidTicks() { return fluidTicks; }
    @Override public @Nullable CompoundTag getPoiChunkSections() { return poiChunk; }
}
