package dev.iyanz.sourbycraft.swm.server;

import com.mojang.datafixers.DataFixer;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * ASP dev/26.2 parity. SWM worlds keep their state in the in-memory
 * SlimeWorld; the dimension data storage doesn't need to hit disk. Without
 * this, vanilla SavedDataStorage schedules writes into the temp level dir
 * on every tick for things like raids and scheduled events — wasted IO plus
 * orphan files left under the temp dir on hard shutdown.
 */
public class ReadOnlyDimensionDataStorage extends SavedDataStorage {

    public ReadOnlyDimensionDataStorage(Path dataFolder, DataFixer fixerUpper, HolderLookup.Provider registries) {
        super(dataFolder, fixerUpper, registries);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T extends SavedData> T get(SavedDataType<T> type) {
        Optional<SavedData> optional = this.cache.get(type);
        if (optional == null) {
            return null;
        }
        return (T) optional.orElse(null);
    }

    @Override
    public @NotNull CompletableFuture<?> scheduleSave() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void saveAndJoin() {}

    @Override
    public void close() {}
}
