package dev.iyanz.sourbycraft.swm.api;

import dev.iyanz.sourbycraft.swm.api.exceptions.*;
import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import net.kyori.adventure.util.Services;
import org.jetbrains.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.List;

public interface AdvancedSlimePaperAPI {

    SlimeWorld readWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap propertyMap)
            throws UnknownWorldException, IOException, CorruptedWorldException, NewerFormatException;

    SlimeWorldInstance getLoadedWorld(String worldName);

    List<SlimeWorldInstance> getLoadedWorlds();

    SlimeWorldInstance loadWorld(SlimeWorld world, boolean callWorldLoadEvent) throws IllegalArgumentException;

    boolean worldLoaded(SlimeWorld world);

    void saveWorld(SlimeWorld world) throws IOException;

    void migrateWorld(String worldName, SlimeLoader currentLoader, SlimeLoader newLoader)
            throws IOException, WorldAlreadyExistsException, UnknownWorldException;

    SlimeWorld createEmptyWorld(String worldName, boolean readOnly, SlimePropertyMap propertyMap, @Nullable SlimeLoader loader);

    SlimeWorld readVanillaWorld(File worldDir, String worldName, @Nullable SlimeLoader loader)
            throws InvalidWorldException, WorldLoadedException, WorldTooBigException, IOException, WorldAlreadyExistsException;

    SlimeSerializationAdapter getSerializer();

    java.util.concurrent.CompletableFuture<SlimeWorld> readWorldAsync(SlimeLoader loader, String worldName,
            boolean readOnly, SlimePropertyMap propertyMap);

    java.util.concurrent.CompletableFuture<SlimeWorldInstance> loadWorldAsync(SlimeWorld world,
            boolean callWorldLoadEvent);

    java.util.concurrent.CompletableFuture<Void> saveWorldAsync(SlimeWorld world);

    java.util.concurrent.CompletableFuture<SlimeWorld> readVanillaWorldAsync(java.io.File worldDir,
            String worldName, @Nullable SlimeLoader loader);

    java.util.concurrent.CompletableFuture<Void> migrateWorldAsync(String worldName,
            SlimeLoader currentLoader, SlimeLoader newLoader);

    static AdvancedSlimePaperAPI instance() {
        return Holder.INSTANCE;
    }

    class Holder {
        private static final AdvancedSlimePaperAPI INSTANCE = Services.service(AdvancedSlimePaperAPI.class).orElseThrow();
    }
}
