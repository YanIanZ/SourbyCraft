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
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.validation.DirectoryValidator;
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

    public SlimeLevelInstance(
            SlimeBootstrap slimeBootstrap,
            PrimaryLevelData primaryLevelData,
            ResourceKey<net.minecraft.world.level.Level> worldKey,
            LevelStem levelStem,
            org.bukkit.World.Environment environment
    ) throws IOException {
        super(
                MinecraftServer.getServer(),
                MinecraftServer.getServer().executor,
                CUSTOM_LEVEL_STORAGE.createAccess(
                        slimeBootstrap.initial().getName() + UUID.randomUUID(),
                        (net.minecraft.resources.ResourceKey) (Object) worldKey
                ),
                primaryLevelData,
                worldKey,
                levelStem,
                false,
                0,
                Collections.emptyList(),
                true,
                null,
                environment,
                null,
                null
        );

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

        // Attempt to read PDC
        CompoundTag extraData = this.slimeInstance.getExtraData();
        if (extraData.contains("BukkitValues")) {
            extraData.getCompound("BukkitValues").ifPresent(values -> {
                getWorld().readBukkitValues(values);
            });
        }
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
            return CompletableFuture.runAsync(writeTask, exec.pool());
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
        Path path = this.levelStorageAccess.levelDirectory.path();
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
