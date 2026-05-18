package dev.iyanz.sourbycraft.swm.api;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

public interface SlimeChunkSection {
    CompoundTag getBlockStatesTag();
    CompoundTag getBiomeTag();
    @Nullable byte[] getBlockLight();
    @Nullable byte[] getSkyLight();
}
