package dev.iyanz.sourbycraft.swm.core;

import dev.iyanz.sourbycraft.swm.api.SlimeChunkSection;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

public class SlimeChunkSectionSkeleton implements SlimeChunkSection {
    private final CompoundTag blockStatesTag;
    private final CompoundTag biomeTag;
    private final @Nullable byte[] blockLight;
    private final @Nullable byte[] skyLight;

    public SlimeChunkSectionSkeleton(
        CompoundTag blockStatesTag,
        CompoundTag biomeTag,
        @Nullable byte[] blockLight,
        @Nullable byte[] skyLight
    ) {
        this.blockStatesTag = blockStatesTag;
        this.biomeTag = biomeTag;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
    }

    @Override public CompoundTag getBlockStatesTag() { return blockStatesTag; }
    @Override public CompoundTag getBiomeTag() { return biomeTag; }
    @Override public @Nullable byte[] getBlockLight() { return blockLight; }
    @Override public @Nullable byte[] getSkyLight() { return skyLight; }
}
