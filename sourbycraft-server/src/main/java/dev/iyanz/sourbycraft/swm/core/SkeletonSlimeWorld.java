package dev.iyanz.sourbycraft.swm.core;

import dev.iyanz.sourbycraft.swm.api.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import java.util.Map;

public class SkeletonSlimeWorld implements SlimeWorld {
    private final String name;
    private final SlimeLoader loader;
    private final Long2ObjectOpenHashMap<SlimeChunk> chunks;
    private final CompoundTag extraData;
    private final SlimePropertyMap propertyMap;
    private final int dataVersion;
    private final boolean readOnly;

    public SkeletonSlimeWorld(String name, @Nullable SlimeLoader loader,
                              Long2ObjectOpenHashMap<SlimeChunk> chunks,
                              CompoundTag extraData, SlimePropertyMap propertyMap,
                              int dataVersion, boolean readOnly) {
        this.name = name;
        this.loader = loader;
        this.chunks = chunks;
        this.extraData = extraData;
        this.propertyMap = propertyMap;
        this.dataVersion = dataVersion;
        this.readOnly = readOnly;
    }

    @Override public String getName() { return name; }
    @Override public @Nullable SlimeLoader getLoader() { return loader; }
    @Override public SlimePropertyMap getPropertyMap() { return propertyMap; }
    @Override public boolean isReadOnly() { return readOnly; }
    @Override public int getDataVersion() { return dataVersion; }
    @Override public CompoundTag getExtraData() { return extraData; }
    @Override public @Nullable SlimeChunk getChunk(int x, int z) {
        return chunks.get(pairKey(x, z));
    }
    @Override public Map<Long, ? extends SlimeChunk> getChunks() {
        return Map.copyOf(chunks);
    }

    private static long pairKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
