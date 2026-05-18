package dev.iyanz.sourbycraft.swm.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;
import java.util.List;

public interface SlimeChunk {
    int getX();
    int getZ();
    List<? extends SlimeChunkSection> getSections();
    CompoundTag getHeightMaps();
    CompoundTag getTileEntities();
    ListTag getEntities();
    @Nullable CompoundTag getExtraData();
    @Nullable CompoundTag getUpgradeData();
    @Nullable CompoundTag getBlockTicks();
    @Nullable CompoundTag getFluidTicks();
    @Nullable CompoundTag getPoiChunkSections();
}
