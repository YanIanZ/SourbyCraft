package dev.iyanz.sourbycraft.swm.server;

import dev.iyanz.sourbycraft.swm.api.SlimeChunk;
import dev.iyanz.sourbycraft.swm.api.SlimeChunkSection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SafeNmsChunkWrapper implements SlimeChunk {

    private final NMSSlimeChunk wrapper;
    private final SlimeChunk safety;

    public SafeNmsChunkWrapper(NMSSlimeChunk wrapper, SlimeChunk safety) {
        this.wrapper = wrapper;
        this.safety = safety;
    }

    @Override
    public int getX() {
        return this.wrapper.getX();
    }

    @Override
    public int getZ() {
        return this.wrapper.getZ();
    }

    @Override
    public List<? extends SlimeChunkSection> getSections() {
        if (shouldDefaultBackToSlimeChunk()) {
            return this.safety.getSections();
        }
        return this.wrapper.getSections();
    }

    @Override
    public CompoundTag getHeightMaps() {
        if (shouldDefaultBackToSlimeChunk()) {
            return this.safety.getHeightMaps();
        }
        return this.wrapper.getHeightMaps();
    }

    @Override
    public CompoundTag getTileEntities() {
        if (shouldDefaultBackToSlimeChunk()) {
            return this.safety.getTileEntities();
        }
        return this.wrapper.getTileEntities();
    }

    @Override
    public ListTag getEntities() {
        if (shouldDefaultBackToSlimeChunk()) {
            return this.safety.getEntities();
        }
        return this.wrapper.getEntities();
    }

    @Override
    public @Nullable CompoundTag getExtraData() {
        if (shouldDefaultBackToSlimeChunk()) {
            return this.safety.getExtraData();
        }
        return this.wrapper.getExtraData();
    }

    @Override
    public @Nullable CompoundTag getUpgradeData() {
        if (shouldDefaultBackToSlimeChunk()) {
            return this.safety.getUpgradeData();
        }
        return this.wrapper.getUpgradeData();
    }

    @Override
    public @Nullable CompoundTag getBlockTicks() {
        if (shouldDefaultBackToSlimeChunk()) {
            return this.safety.getBlockTicks();
        }
        return this.wrapper.getBlockTicks();
    }

    @Override
    public @Nullable CompoundTag getFluidTicks() {
        if (shouldDefaultBackToSlimeChunk()) {
            return this.safety.getFluidTicks();
        }
        return this.wrapper.getFluidTicks();
    }

    @Override
    public @Nullable CompoundTag getPoiChunkSections() {
        if (shouldDefaultBackToSlimeChunk()) {
            return this.safety.getPoiChunkSections();
        }
        return this.wrapper.getPoiChunkSections();
    }

    public boolean shouldDefaultBackToSlimeChunk() {
        return this.safety != null && !this.wrapper.getChunk().loaded;
    }

    public NMSSlimeChunk getWrapper() {
        return wrapper;
    }

    public SlimeChunk getSafety() {
        return safety;
    }
}
