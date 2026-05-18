package dev.iyanz.sourbycraft.swm.api;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import java.util.Map;

public interface SlimeWorld {
    String getName();
    @Nullable SlimeLoader getLoader();
    SlimePropertyMap getPropertyMap();
    boolean isReadOnly();
    int getDataVersion();
    @Nullable SlimeChunk getChunk(int x, int z);
    Map<Long, ? extends SlimeChunk> getChunks();
    CompoundTag getExtraData();
}
