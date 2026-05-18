package dev.iyanz.sourbycraft.swm.api;

import dev.iyanz.sourbycraft.swm.api.exceptions.*;
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

    static AdvancedSlimePaperAPI instance() {
        return Holder.INSTANCE;
    }

    class Holder {
        private static final AdvancedSlimePaperAPI INSTANCE = Services.service(AdvancedSlimePaperAPI.class).orElseThrow();
    }
}
