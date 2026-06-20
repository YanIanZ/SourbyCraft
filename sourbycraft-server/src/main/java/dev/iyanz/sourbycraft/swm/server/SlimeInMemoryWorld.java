package dev.iyanz.sourbycraft.swm.server;

import dev.iyanz.sourbycraft.swm.api.SlimeChunk;
import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import dev.iyanz.sourbycraft.swm.core.SkeletonSlimeWorld;
import dev.iyanz.sourbycraft.swm.core.SlimeChunkSkeleton;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;

public class SlimeInMemoryWorld implements SlimeWorld, SlimeWorldInstance {

    private final SlimeLevelInstance instance;
    private final String name;
    private final CompoundTag extraData;
    private final SlimePropertyMap propertyMap;
    private final SlimeLoader loader;
    private final boolean readOnly;

    private final Long2ObjectMap<SlimeChunk> chunkStorage = new Long2ObjectOpenHashMap<>();

    public SlimeInMemoryWorld(SlimeWorld bootstrap, SlimeLevelInstance instance) {
        this.instance = instance;
        this.name = bootstrap.getName();
        this.extraData = bootstrap.getExtraData().copy();
        this.propertyMap = bootstrap.getPropertyMap();
        this.loader = bootstrap.getLoader();
        this.readOnly = bootstrap.isReadOnly();

        for (Map.Entry<Long, ? extends SlimeChunk> entry : bootstrap.getChunks().entrySet()) {
            SlimeChunk slimeChunk = entry.getValue();
            if (slimeChunk != null) {
                this.chunkStorage.put(key(slimeChunk.getX(), slimeChunk.getZ()), slimeChunk);
            }
        }
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public @Nullable SlimeLoader getLoader() {
        return this.loader;
    }

    @Override
    public SlimePropertyMap getPropertyMap() {
        return this.propertyMap;
    }

    @Override
    public boolean isReadOnly() {
        return this.loader == null || this.readOnly;
    }

    @Override
    public int getDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    @Override
    public @Nullable SlimeChunk getChunk(int x, int z) {
        return this.chunkStorage.get(key(x, z));
    }

    @Override
    public Map<Long, ? extends SlimeChunk> getChunks() {
        // SourbyCraft - live unmodifiable view; iterate on the main thread only
        return java.util.Collections.unmodifiableMap(this.chunkStorage);
    }

    @Override
    public CompoundTag getExtraData() {
        return this.extraData;
    }

    @Override
    public @NotNull World getBukkitWorld() {
        return this.instance.getWorld();
    }

    @Override
    public @Nullable SlimeWorld getSerializableCopy() {
        Long2ObjectOpenHashMap<SlimeChunk> cloned = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<SlimeChunk> entry : this.chunkStorage.long2ObjectEntrySet()) {
            SlimeChunk clonedChunk = entry.getValue();

            // ASP dev/26.2 parity: NMSSlimeChunk is the only live wrapper we
            // promote into chunkStorage now. The previous SafeNmsChunkWrapper
            // branch was discarding live-mutated blocks on unload, which broke
            // SS2 schematic paste persistence.
            if (clonedChunk instanceof NMSSlimeChunk nmsChunk) {
                if (FastChunkPruner.canBePruned(this, nmsChunk.getChunk())) {
                    continue;
                }
                clonedChunk = PartiallySerializedSlimeChunk.of(
                        nmsChunk,
                        getPropertyMap().saveBlockTicks(),
                        getPropertyMap().saveFluidTicks(),
                        getPropertyMap().savePoi()
                );
            }

            cloned.put(entry.getLongKey(), clonedChunk);
        }

        // Serialize Bukkit Values (PDC)
        CompoundTag nmsTag = new CompoundTag();
        this.instance.getWorld().storeBukkitValues(nmsTag);
        nmsTag.getCompound("BukkitValues").ifPresent(bukkitValues -> {
            this.extraData.put("BukkitValues", bukkitValues);
        });

        return new SkeletonSlimeWorld(
                this.name,
                this.loader,
                cloned,
                this.extraData.copy(),
                this.propertyMap,
                this.getDataVersion(),
                this.isReadOnly()
        );
    }

    public LevelChunk createChunk(int x, int z, SlimeChunk chunk) {
        SlimeChunkLevel levelChunk;
        if (chunk == null) {
            net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(x, z);
            LevelChunkTicks<Block> blockTicks = new LevelChunkTicks<>();
            LevelChunkTicks<Fluid> fluidTicks = new LevelChunkTicks<>();

            levelChunk = new SlimeChunkLevel(this.instance, null, pos, UpgradeData.EMPTY,
                    blockTicks, fluidTicks, 0L, null, null, null);

            levelChunk.fillBiomesFromNoise(this.instance.chunkSource.getGenerator().getBiomeSource(),
                    this.instance.chunkSource.randomState().sampler());
        } else {
            levelChunk = SlimeChunkConverter.deserializeSlimeChunk(this.instance, chunk);
        }

        return levelChunk;
    }

    public void unload(LevelChunk providedChunk) {
        unload(providedChunk, null, null);
    }

    /**
     * ASP dev/26.2 parity. Three-arg overload preserves entity + POI data on
     * chunk unload by accepting the moonrise-supplied slices and POI chunk
     * before {@code NewChunkHolder.unloadStage1} nulls them out. The bare
     * 1-arg form survives for callers (e.g. Bukkit world-unload) that don't
     * have those handles available.
     */
    public void unload(LevelChunk providedChunk,
                       @Nullable ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices entitySlices,
                       @Nullable ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk poiChunk) {
        final int x = providedChunk.locX;
        final int z = providedChunk.locZ;

        if (FastChunkPruner.canBePruned(this, providedChunk)) {
            this.chunkStorage.remove(key(x, z));
            return;
        }

        NMSSlimeChunk nmsChunk;
        if (providedChunk instanceof SlimeChunkLevel slimeChunkLevel) {
            nmsChunk = slimeChunkLevel.getNmsSlimeChunk();
        } else {
            nmsChunk = new NMSSlimeChunk(providedChunk, getChunk(x, z));
        }

        net.minecraft.nbt.ListTag entities = entitySlices != null
                ? nmsChunk.getEntities(entitySlices)
                : nmsChunk.getEntities();
        CompoundTag poiSections = poiChunk != null
                ? nmsChunk.getPoiChunkSections(poiChunk)
                : nmsChunk.getPoiChunkSections();

        this.chunkStorage.put(key(x, z), new SlimeChunkSkeleton(
                nmsChunk.getX(),
                nmsChunk.getZ(),
                new java.util.ArrayList<>(nmsChunk.getSections()),
                nmsChunk.getHeightMaps(),
                nmsChunk.getTileEntities(),
                entities,
                nmsChunk.getExtraData(),
                nmsChunk.getUpgradeData(),
                nmsChunk.getBlockTicks(),
                nmsChunk.getFluidTicks(),
                poiSections
        ));
    }

    public void promoteInChunkStorage(SlimeChunkLevel chunk) {
        // ASP dev/26.2 parity: always promote the live NMSSlimeChunk view.
        chunkStorage.put(key(chunk.locX, chunk.locZ), chunk.getNmsSlimeChunk());
    }

    public SlimeLevelInstance getInstance() {
        return instance;
    }

    public Collection<SlimeChunk> getChunkStorage() {
        return this.chunkStorage.values();
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }
}
