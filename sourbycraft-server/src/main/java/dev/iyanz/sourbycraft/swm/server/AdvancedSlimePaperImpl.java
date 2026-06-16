package dev.iyanz.sourbycraft.swm.server;

import dev.iyanz.sourbycraft.swm.api.AdvancedSlimePaperAPI;
import dev.iyanz.sourbycraft.swm.api.SlimeChunk;
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
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.world.WorldLoadEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spigotmc.AsyncCatcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import dev.iyanz.sourbycraft.util.VirtualExecutor;

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

        // Operator diagnostic: surface empty / under-populated worlds at load time
        // instead of waiting for a "no safe blocks" teleport failure to expose them.
        // Persisted chunk count is read from the bootstrap SkeletonSlimeWorld
        // BEFORE wrapping; afterwards the in-memory map is mutated by NMS callbacks.
        try {
            int persistedChunks = world.getChunks() == null ? 0 : world.getChunks().size();
            if (persistedChunks == 0) {
                LOGGER.warn("SlimeWorld '{}' loaded with 0 persisted chunks. Originating plugin must repopulate "
                                + "(e.g. SS2 schematic paste) or the world will appear as empty void. "
                                + "Use `/swm inspect {}` to confirm and `/swm delete {}` to clear stale state.",
                        world.getName(), world.getName(), world.getName());
            }
        } catch (Throwable ignored) {}

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
                    } catch (Exception e) {
                        LOGGER.warn("Failed to complete save task future", e);
                    }
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
        Objects.requireNonNull(worldDir, "World directory cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");

        if (loader != null && loader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }
        if (getLoadedWorld(worldName) != null) {
            throw new WorldLoadedException(worldName);
        }
        if (!worldDir.exists() || !worldDir.isDirectory()) {
            throw new InvalidWorldException(worldName, new FileNotFoundException("World directory not found: " + worldDir.getAbsolutePath()));
        }

        File levelFile = new File(worldDir, "level.dat");
        if (!levelFile.exists()) {
            throw new InvalidWorldException(worldName, new FileNotFoundException("level.dat not found in " + worldDir.getAbsolutePath()));
        }

        CompoundTag dataTag;
        int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        try (FileInputStream fis = new FileInputStream(levelFile)) {
            CompoundTag levelRoot = NbtIo.readCompressed(fis, NbtAccounter.unlimitedHeap());
            dataTag = levelRoot.getCompound("Data").orElse(null);
            if (dataTag != null) {
                dataVersion = dataTag.getIntOr("DataVersion", dataVersion);
            }
        }

        SlimePropertyMap propertyMap = new SlimePropertyMap();
        if (dataTag != null) {
            int spawnX = dataTag.getIntOr("SpawnX", 0);
            int spawnY = dataTag.getIntOr("SpawnY", 64);
            int spawnZ = dataTag.getIntOr("SpawnZ", 0);
            propertyMap.setSpawnX(spawnX);
            propertyMap.setSpawnY(spawnY);
            propertyMap.setSpawnZ(spawnZ);
        }

        Long2ObjectOpenHashMap<SlimeChunk> chunksMap = new Long2ObjectOpenHashMap<>();
        final int MAX_CHUNKS = 100_000;
        int totalChunks = 0;
        File regionDir = new File(worldDir, "region");
        if (regionDir.exists() && regionDir.isDirectory()) {
            Path regionPath = regionDir.toPath();
            File[] regionFiles = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
            if (regionFiles != null) {
                for (File regionFile : regionFiles) {
                    String[] parts = regionFile.getName().split("\\.");
                    if (parts.length < 4) continue;
                    int regionX;
                    int regionZ;
                    try {
                        regionX = Integer.parseInt(parts[1]);
                        regionZ = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException e) {
                        LOGGER.warn("Skipping region file with malformed name: {}", regionFile.getName());
                        continue;
                    }

                    RegionStorageInfo storageInfo = new RegionStorageInfo(worldName, Level.OVERWORLD, "region");
                    RegionFile region = new RegionFile(storageInfo, regionFile.toPath(), regionPath, true);
                    try {
                        for (int cx = 0; cx < 32; cx++) {
                            for (int cz = 0; cz < 32; cz++) {
                                int chunkX = regionX * 32 + cx;
                                int chunkZ = regionZ * 32 + cz;
                                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

                                if (!region.doesChunkExist(chunkPos)) continue;

                                try {
                                    CompoundTag chunkNbt = SlimeChunkConverter.readChunkNbt(region, chunkPos);
                                    if (chunkNbt == null) continue;

                                    SlimeChunk slimeChunk = SlimeChunkConverter.fromVanilla(chunkNbt);
                                    chunksMap.put(((long) slimeChunk.getX() << 32) | (slimeChunk.getZ() & 0xFFFFFFFFL), slimeChunk);
                                    totalChunks++;
                                    if (totalChunks > MAX_CHUNKS) {
                                        throw new WorldTooBigException("World exceeds " + MAX_CHUNKS + " chunks");
                                    }
                                } catch (WorldTooBigException e) {
                                    throw e;
                                } catch (Exception e) {
                                    LOGGER.warn("Failed to convert chunk ({}, {}) in region file {}.{}.mca",
                                            chunkX, chunkZ, regionX, regionZ, e);
                                }
                            }
                        }
                    } finally {
                        region.close();
                    }
                }
            }
        }

        CompoundTag extraData = new CompoundTag();
        SkeletonSlimeWorld slimeWorld = new SkeletonSlimeWorld(worldName, loader, chunksMap,
                extraData, propertyMap, dataVersion, false);

        if (loader != null) {
            loader.saveWorld(worldName, SlimeSerializer.serialize(slimeWorld));
        }

        return slimeWorld;
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

    @Override
    public CompletableFuture<SlimeWorld> readWorldAsync(SlimeLoader loader, String worldName,
                                                        boolean readOnly, SlimePropertyMap propertyMap) {
        Objects.requireNonNull(loader);
        Objects.requireNonNull(worldName);
        Objects.requireNonNull(propertyMap);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readWorld(loader, worldName, readOnly, propertyMap);
            } catch (UnknownWorldException | CorruptedWorldException | NewerFormatException | IOException e) {
                throw new CompletionException(e);
            }
        }, VirtualExecutor.executor());
    }

    @Override
    public CompletableFuture<SlimeWorldInstance> loadWorldAsync(SlimeWorld world, boolean callWorldLoadEvent) {
        Objects.requireNonNull(world);
        return CompletableFuture.supplyAsync(() -> {
            // NMS load must happen on main thread — bridge via server.execute
            CompletableFuture<SlimeWorldInstance> bridge = new CompletableFuture<>();
            MinecraftServer.getServer().execute(() -> {
                try {
                    bridge.complete(loadWorld(world, callWorldLoadEvent));
                } catch (Throwable t) {
                    bridge.completeExceptionally(t);
                }
            });
            try {
                return bridge.join();
            } catch (CompletionException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, VirtualExecutor.executor());
    }

    @Override
    public CompletableFuture<Void> saveWorldAsync(SlimeWorld world) {
        Objects.requireNonNull(world);
        return CompletableFuture.runAsync(() -> {
            try {
                saveWorld(world);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, VirtualExecutor.executor());
    }

    @Override
    public CompletableFuture<SlimeWorld> readVanillaWorldAsync(File worldDir, String worldName,
                                                               SlimeLoader loader) {
        Objects.requireNonNull(worldDir);
        Objects.requireNonNull(worldName);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readVanillaWorld(worldDir, worldName, loader);
            } catch (InvalidWorldException | WorldLoadedException | WorldTooBigException |
                     WorldAlreadyExistsException | IOException e) {
                throw new CompletionException(e);
            }
        }, VirtualExecutor.executor());
    }

    @Override
    public CompletableFuture<Void> migrateWorldAsync(String worldName, SlimeLoader currentLoader,
                                                     SlimeLoader newLoader) {
        Objects.requireNonNull(worldName);
        Objects.requireNonNull(currentLoader);
        Objects.requireNonNull(newLoader);
        return CompletableFuture.runAsync(() -> {
            try {
                migrateWorld(worldName, currentLoader, newLoader);
            } catch (WorldAlreadyExistsException | UnknownWorldException | IOException e) {
                throw new CompletionException(e);
            }
        }, VirtualExecutor.executor());
    }

    public void onWorldUnload(String name) {
        this.loadedWorlds.remove(name);
    }
}
