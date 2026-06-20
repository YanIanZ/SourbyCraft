package dev.iyanz.sourbycraft.swm.server;

import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.validation.DirectoryValidator;
import io.papermc.paper.world.PaperWorldLoader;
import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class SlimeLevelInstance extends ServerLevel {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlimeLevelInstance.class);

    public static LevelStorageSource CUSTOM_LEVEL_STORAGE;

    static {
        try {
            Path path = Files.createTempDirectory("swm-" + UUID.randomUUID().toString().substring(0, 5)).toAbsolutePath();
            DirectoryValidator directoryValidator = LevelStorageSource.parseValidator(path.resolve("allowed_symlinks.txt"));
            CUSTOM_LEVEL_STORAGE = new LevelStorageSource(path, path, directoryValidator, DataFixers.getDataFixer());
            path.toFile().deleteOnExit();
        } catch (IOException ex) {
            throw new IllegalStateException("Couldn't create dummy file directory.", ex);
        }
    }

    public final SlimeInMemoryWorld slimeInstance;

    // Keep our own handle to the temp LevelStorageAccess so deleteTempFiles() can
    // locate the directory after the world is unloaded. Paper 26.1.2 no longer
    // exposes a levelStorageAccess field on ServerLevel.
    private final LevelStorageSource.LevelStorageAccess customLevelStorageAccess;

    // Per-instance write chain: serializes saveWorld() writes for this world so an
    // older snapshot's write can never finish after a newer one's (stale persist).
    private java.util.concurrent.CompletableFuture<?> lastWrite =
            java.util.concurrent.CompletableFuture.completedFuture(null);

    public SlimeLevelInstance(
            SlimeBootstrap slimeBootstrap,
            PrimaryLevelData primaryLevelData,
            ResourceKey<net.minecraft.world.level.Level> worldKey,
            LevelStem levelStem,
            org.bukkit.World.Environment environment
    ) throws IOException {
        this(
                slimeBootstrap,
                primaryLevelData,
                worldKey,
                levelStem,
                environment,
                CUSTOM_LEVEL_STORAGE.createAccess(slimeBootstrap.initial().getName() + UUID.randomUUID())
        );
    }

    private SlimeLevelInstance(
            SlimeBootstrap slimeBootstrap,
            PrimaryLevelData primaryLevelData,
            ResourceKey<net.minecraft.world.level.Level> worldKey,
            LevelStem levelStem,
            org.bukkit.World.Environment environment,
            LevelStorageSource.LevelStorageAccess levelStorageAccess
    ) throws IOException {
        super(
                MinecraftServer.getServer(),
                MinecraftServer.getServer().executor,
                levelStorageAccess,
                // Paper 26.1.2: ServerLevel now expects WorldGenSettings (was PrimaryLevelData).
                // The level data is wired in via the SavedDataStorage / LoadedWorldData below.
                buildWorldGenSettings(MinecraftServer.getServer()),
                worldKey,
                levelStem,
                false,
                0,
                Collections.emptyList(),
                true,
                // Paper 26.1.2: added typeKey, savedDataStorage, loadedWorldData params.
                null,
                environment,
                null,
                null,
                buildSavedDataStorage(MinecraftServer.getServer(), worldKey),
                new PaperWorldLoader.LoadedWorldData(
                        slimeBootstrap.initial().getName(),
                        UUID.randomUUID(),
                        null,
                        PaperLevelOverrides.createFromLiveLevelData(primaryLevelData).attach(primaryLevelData, worldKey)
                )
        );

        this.customLevelStorageAccess = levelStorageAccess;
        this.slimeInstance = new SlimeInMemoryWorld(slimeBootstrap.initial(), this);

        SlimePropertyMap propertyMap = slimeBootstrap.initial().getPropertyMap();

        this.serverLevelData.setDifficulty(Difficulty.PEACEFUL);

        serverLevelData.setSpawn(
                new LevelData.RespawnData(
                        GlobalPos.of(
                                this.dimension(),
                                new BlockPos(
                                        propertyMap.getSpawnX(),
                                        propertyMap.getSpawnY(),
                                        propertyMap.getSpawnZ()
                                )
                        ),
                        Mth.wrapDegrees(0F),
                        Mth.wrapDegrees(0F)
                )
        );

        super.chunkSource.setSpawnSettings(false, false);

        // Swap Paper's vanilla EntityDataController + PoiDataController with SWM-backed
        // loaders so the chunk system serves entity + POI data from our in-memory
        // SlimeWorld instead of trying to read region files that don't exist. The
        // superclass fields were made protected via the SourbyCraft Paper patch; here
        // we replace them post-super so the chunk task scheduler picks them up the
        // first time it reads a chunk.
        try {
            this.entityDataController = new dev.iyanz.sourbycraft.swm.server.moonrise.SlimeEntityDataLoader(
                    new ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.EntityDataController.EntityRegionFileStorage(
                            new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                                    customLevelStorageAccess.getLevelId(),
                                    worldKey,
                                    "entities"),
                            customLevelStorageAccess.getDimensionPath(worldKey).resolve("entities"),
                            MinecraftServer.getServer().forceSynchronousWrites()),
                    this.moonrise$getChunkTaskScheduler(),
                    this);
            this.poiDataController = new dev.iyanz.sourbycraft.swm.server.moonrise.SlimePoiDataLoader(
                    this, this.moonrise$getChunkTaskScheduler());
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraftSWM").warn(
                    "Failed to install SWM data controllers; falling back to vanilla region-file loaders for entity/POI. " +
                            "Entities + villager POIs may not persist across world unloads.", t);
        }

        // Attempt to read PDC
        CompoundTag extraData = this.slimeInstance.getExtraData();
        if (extraData.contains("BukkitValues")) {
            extraData.getCompound("BukkitValues").ifPresent(values -> {
                getWorld().readBukkitValues(values);
            });
        }
    }

    private static WorldGenSettings buildWorldGenSettings(MinecraftServer server) {
        // Reuse the existing server-wide WorldOptions seed/structure flags so chunk
        // generation behaves consistently with the host server. The dimensions map
        // is sourced from the registry — we never persist this storage for SWM.
        WorldOptions options = new WorldOptions(0L, false, false);
        return WorldGenSettings.of(options, server.registryAccess());
    }

    private static SavedDataStorage buildSavedDataStorage(
            MinecraftServer server,
            ResourceKey<net.minecraft.world.level.Level> worldKey
    ) {
        java.nio.file.Path dataFolder = server.storageSource
                .getDimensionPath(worldKey)
                .resolve(LevelResource.DATA.id());
        // ASP dev/26.2 parity: SWM owns its persistence via the SlimeWorld
        // round-trip. Use the read-only storage so vanilla SavedDataStorage
        // doesn't schedule per-tick writes into the temp dir for raids /
        // scheduled events / forced_chunks — those would just be orphaned
        // when the temp dir is wiped on JVM exit.
        return new ReadOnlyDimensionDataStorage(dataFolder, DataFixers.getDataFixer(), server.registryAccess());
    }

    @Override
    public void save(@Nullable ProgressListener progressUpdate, boolean forceSave, boolean savingDisabled, boolean close) {
        if (!savingDisabled) saveWorld();
    }

    @Override
    public void saveIncrementally(boolean doFull) {
        if (doFull) {
            saveWorld();
        }
    }

    public void unload(@NotNull LevelChunk chunk) {
        slimeInstance.unload(chunk);
    }

    /**
     * ASP dev/26.2 parity. Called from the patched Moonrise NewChunkHolder
     * BEFORE the vanilla {@code world.unload(LevelChunk)} step so we can grab
     * the entity slices and POI chunk handles before they get nulled out by
     * unloadStage1. The 1-arg overload still serves Bukkit world-unload paths.
     */
    public void unload(@NotNull LevelChunk chunk,
                       @org.jetbrains.annotations.Nullable
                       ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices entitySlices,
                       @org.jetbrains.annotations.Nullable
                       ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk poiChunk) {
        slimeInstance.unload(chunk, entitySlices, poiChunk);
    }

    /**
     * ASP dev/26.2 parity. Wires the patched Moonrise {@code ChunkLoadTask} to
     * read chunk data from the in-memory {@link SlimeInMemoryWorld} instead of
     * vanilla region-file storage. Region files don't exist for SWM worlds, so
     * without this override the vanilla load path silently returns empty chunks
     * for any coordinate not already promoted into {@code chunkStorage}.
     */
    public dev.iyanz.sourbycraft.swm.server.moonrise.ChunkDataLoadTask getLoadTask(
            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkLoadTask task,
            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler scheduler,
            ServerLevel world,
            int chunkX,
            int chunkZ,
            ca.spottedleaf.concurrentutil.util.Priority priority,
            java.util.function.Consumer<ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.GenericDataLoadTask.TaskResult<net.minecraft.world.level.chunk.ChunkAccess, Throwable>> onRun
    ) {
        return new dev.iyanz.sourbycraft.swm.server.moonrise.ChunkDataLoadTask(
                task, scheduler, world, chunkX, chunkZ, priority, onRun);
    }

    public Future<?> saveWorld() {
        if (this.slimeInstance.isReadOnly() || this.slimeInstance.getLoader() == null) {
            return CompletableFuture.completedFuture(null);
        }
        final SlimeWorld world;
        try {
            // Main thread: PDC / Bukkit access happens here.
            world = this.slimeInstance.getSerializableCopy();
        } catch (Exception e) {
            LOGGER.error("There was a problem snapshotting the SlimeLevelInstance {}",
                    serverLevelData.getLevelName(), e);
            return CompletableFuture.failedFuture(e);
        }
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        var exec = dev.iyanz.sourbycraft.swm.plugin.SWPlugin.ioExecutor();
        Runnable writeTask = () -> {
            try {
                byte[] serializedWorld =
                        dev.iyanz.sourbycraft.swm.core.SlimeSerializer.serialize(world);
                this.slimeInstance.getLoader().saveWorld(this.slimeInstance.getName(), serializedWorld);
            } catch (Exception e) {
                LOGGER.error("There was a problem saving the SlimeLevelInstance {}",
                        serverLevelData.getLevelName(), e);
                throw new java.util.concurrent.CompletionException(e);
            }
        };
        if (exec != null) {
            // Chain on the per-instance future so writes for this world run strictly
            // in order. handle((r,t)->null) drops a prior failure so it can't poison
            // the chain. Writes across different worlds still run in parallel.
            this.lastWrite = this.lastWrite.handle((r, t) -> null).thenRunAsync(writeTask, exec.pool());
            return this.lastWrite;
        }
        // Fallback: run inline (e.g. during shutdown after executor stopped).
        try {
            writeTask.run();
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public SlimeWorldInstance getSlimeInstance() {
        return this.slimeInstance;
    }

    public void deleteTempFiles() {
        Path path = this.customLevelStorageAccess.levelDirectory.path();
        try {
            java.nio.file.Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    if (!file.equals(path)) {
                        java.nio.file.Files.deleteIfExists(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exception) throws IOException {
                    if (exception != null) throw exception;
                    java.nio.file.Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Unable to delete temp level directory", e);
        }
    }
}
