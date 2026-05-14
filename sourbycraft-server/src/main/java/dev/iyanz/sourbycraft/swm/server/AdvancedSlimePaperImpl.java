package dev.iyanz.sourbycraft.swm.server;

import dev.iyanz.sourbycraft.swm.api.AdvancedSlimePaperAPI;
import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import dev.iyanz.sourbycraft.swm.api.SlimeNMSBridge;
import dev.iyanz.sourbycraft.swm.api.SlimeSerializationAdapter;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import dev.iyanz.sourbycraft.swm.api.events.LoadSlimeWorldEvent;
import dev.iyanz.sourbycraft.swm.api.exceptions.*;
import dev.iyanz.sourbycraft.swm.core.SimpleDataFixerConverter;
import dev.iyanz.sourbycraft.swm.core.SkeletonSlimeWorld;
import dev.iyanz.sourbycraft.swm.core.SlimeSerializer;
import dev.iyanz.sourbycraft.swm.core.reader.SlimeWorldReaderRegistry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.world.WorldLoadEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spigotmc.AsyncCatcher;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public class AdvancedSlimePaperImpl implements AdvancedSlimePaperAPI {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancedSlimePaperImpl.class);
    private static final SlimeNMSBridge BRIDGE_INSTANCE = SlimeNMSBridge.instance();

    private final Map<String, SlimeWorldInstance> loadedWorlds = new ConcurrentHashMap<>();

    public static AdvancedSlimePaperImpl instance() {
        return (AdvancedSlimePaperImpl) AdvancedSlimePaperAPI.instance();
    }

    @Override
    public SlimeWorld readWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap propertyMap)
            throws UnknownWorldException, IOException, CorruptedWorldException, NewerFormatException {
        Objects.requireNonNull(loader, "Loader cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        byte[] serializedWorld = loader.readWorld(worldName);
        SlimeWorld slimeWorld = SlimeWorldReaderRegistry.readWorld(loader, worldName, serializedWorld, propertyMap, readOnly);
        SlimeWorld dataFixed = SimpleDataFixerConverter.applyDataFixers(slimeWorld);

        if (!readOnly && dataFixed != slimeWorld) {
            loader.saveWorld(worldName, SlimeSerializer.serialize(dataFixed));
        }

        return dataFixed;
    }

    @Override
    public SlimeWorldInstance loadWorld(SlimeWorld world, boolean callWorldLoadEvent) throws IllegalArgumentException {
        AsyncCatcher.catchOp("SWM world load");
        Objects.requireNonNull(world, "SlimeWorld cannot be null");

        if (Bukkit.getWorld(world.getName()) != null) {
            throw new IllegalArgumentException("World " + world.getName() + " is already loaded");
        }

        MinecraftServer server = MinecraftServer.getServer();
        ResourceKey<Level> dimensionKey = Level.OVERWORLD;

        SlimeWorldInstance instance = BRIDGE_INSTANCE.loadInstance(world, server, dimensionKey);

        Bukkit.getPluginManager().callEvent(new LoadSlimeWorldEvent(instance));
        if (callWorldLoadEvent) {
            Bukkit.getPluginManager().callEvent(new WorldLoadEvent(instance.getBukkitWorld()));
        }

        registerWorld(instance);
        return instance;
    }

    @Override
    public boolean worldLoaded(SlimeWorld world) {
        return loadedWorlds.containsKey(world.getName());
    }

    @Override
    public void saveWorld(SlimeWorld world) throws IOException {
        Objects.requireNonNull(world, "SlimeWorld cannot be null");
        if (worldLoaded(world)) {
            SlimeWorldInstance instance = loadedWorlds.get(world.getName());
            World bukkitWorld = instance.getBukkitWorld();
            ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
            if (level instanceof SlimeLevelInstance slimeLevel) {
                Runnable saveTask = () -> {
                    slimeLevel.save(null, false, false);
                    Future<?> future = CompletableFuture.completedFuture(null);
                    try {
                        future.get();
                    } catch (Exception ignored) {}
                };
                if (Bukkit.isPrimaryThread()) {
                    saveTask.run();
                } else {
                    MinecraftServer.getServer().execute(saveTask);
                }
            }
        } else {
            Objects.requireNonNull(world.getLoader(), "World loader cannot be null");
            byte[] serializedWorld = SlimeSerializer.serialize(world);
            world.getLoader().saveWorld(world.getName(), serializedWorld);
        }
    }

    @Override
    public SlimeWorldInstance getLoadedWorld(String worldName) {
        return loadedWorlds.get(worldName);
    }

    @Override
    public List<SlimeWorldInstance> getLoadedWorlds() {
        return List.copyOf(loadedWorlds.values());
    }

    @Override
    public SlimeWorld createEmptyWorld(String worldName, boolean readOnly, SlimePropertyMap propertyMap, SlimeLoader loader) {
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        return new SkeletonSlimeWorld(worldName, loader, new Long2ObjectOpenHashMap<>(),
                new net.minecraft.nbt.CompoundTag(), propertyMap,
                net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(), readOnly);
    }

    @Override
    public void migrateWorld(String worldName, SlimeLoader currentLoader, SlimeLoader newLoader)
            throws IOException, WorldAlreadyExistsException, UnknownWorldException {
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(currentLoader, "Current loader cannot be null");
        Objects.requireNonNull(newLoader, "New loader cannot be null");

        if (newLoader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        byte[] serializedWorld = currentLoader.readWorld(worldName);
        newLoader.saveWorld(worldName, serializedWorld);
        currentLoader.deleteWorld(worldName);
    }

    @Override
    public SlimeWorld readVanillaWorld(File worldDir, String worldName, SlimeLoader loader)
            throws InvalidWorldException, WorldLoadedException, WorldTooBigException, IOException, WorldAlreadyExistsException {
        throw new UnsupportedOperationException("Vanilla world reading is not yet implemented");
    }

    @Override
    public SlimeSerializationAdapter getSerializer() {
        return new SlimeSerializationAdapter() {
            @Override
            public byte[] serialize(SlimeWorld world) throws IOException {
                return SlimeSerializer.serialize(world);
            }

            @Override
            public SlimeWorld deserialize(String worldName, byte[] data, SlimeLoader loader,
                                           SlimePropertyMap propertyMap, boolean readOnly)
                    throws CorruptedWorldException, NewerFormatException, IOException {
                return SlimeWorldReaderRegistry.readWorld(loader, worldName, data, propertyMap, readOnly);
            }

            @Override
            public int getSlimeFormat() {
                return 0x0D;
            }
        };
    }

    private void registerWorld(SlimeWorldInstance world) {
        this.loadedWorlds.put(world.getName(), world);
    }

    public void onWorldUnload(String name) {
        this.loadedWorlds.remove(name);
    }
}
