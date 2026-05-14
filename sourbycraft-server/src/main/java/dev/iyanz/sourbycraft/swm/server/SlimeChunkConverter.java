package dev.iyanz.sourbycraft.swm.server;

import ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightEngine;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.iyanz.sourbycraft.swm.api.SlimeChunk;
import dev.iyanz.sourbycraft.swm.api.SlimeChunkSection;
import dev.iyanz.sourbycraft.swm.core.SlimeChunkSectionSkeleton;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class SlimeChunkConverter {

    private static final Codec<List<SavedTick<Block>>> BLOCK_TICKS_CODEC = SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()).listOf();
    private static final Codec<List<SavedTick<Fluid>>> FLUID_TICKS_CODEC = SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()).listOf();

    private static final CompoundTag EMPTY_BLOCK_STATE_PALETTE;
    private static final CompoundTag EMPTY_BIOME_PALETTE;

    static {
        PalettedContainerFactory factory = PalettedContainerFactory.create(net.minecraft.server.MinecraftServer.getServer().registryAccess());
        {
            PalettedContainer<BlockState> empty = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), factory.blockStatesStrategy(), null);
            EMPTY_BLOCK_STATE_PALETTE = (CompoundTag) factory.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, empty).getOrThrow();
        }
        {
            Registry<Biome> biomes = net.minecraft.server.MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME);
            PalettedContainer<Holder<Biome>> empty = new PalettedContainer<>(biomes.get(Biomes.PLAINS).orElseThrow(), factory.biomeStrategy(), null);
            EMPTY_BIOME_PALETTE = (CompoundTag) factory.biomeContainerRWCodec().encodeStart(NbtOps.INSTANCE, empty).getOrThrow();
        }
    }

    public static SlimeChunkLevel deserializeSlimeChunk(SlimeLevelInstance instance, SlimeChunk chunk) {
        int x = chunk.getX();
        int z = chunk.getZ();

        ChunkPos pos = new ChunkPos(x, z);

        LevelChunkSection[] sections = new LevelChunkSection[instance.getSectionsCount()];

        SWMRNibbleArray[] blockNibbles = StarLightEngine.getFilledEmptyLight(instance);
        SWMRNibbleArray[] skyNibbles = StarLightEngine.getFilledEmptyLight(instance);

        instance.getServer().scheduleOnMain(() -> {
            instance.getLightEngine().retainData(pos, true);
        });

        for (int sectionId = 0; sectionId < chunk.getSections().size(); sectionId++) {
            SlimeChunkSection slimeSection = chunk.getSections().get(sectionId);

            if (slimeSection != null) {
                byte[] blockLight = slimeSection.getBlockLight();
                if (blockLight != null) {
                    blockNibbles[sectionId] = new SWMRNibbleArray(blockLight);
                }

                byte[] skyLight = slimeSection.getSkyLight();
                if (skyLight != null) {
                    skyNibbles[sectionId] = new SWMRNibbleArray(skyLight);
                }

                PalettedContainer<BlockState> blockPalette;
                if (slimeSection.getBlockStatesTag() != null) {
                    DataResult<PalettedContainer<BlockState>> dataresult = instance.palettedContainerFactory().blockStatesContainerCodec()
                            .parse(NbtOps.INSTANCE, slimeSection.getBlockStatesTag())
                            .promotePartial((s) -> System.out.println("Recoverable error when parsing section " + x + "," + z + ": " + s));
                    blockPalette = dataresult.getOrThrow();
                } else {
                    blockPalette = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), instance.palettedContainerFactory().blockStatesStrategy(), null);
                }

                PalettedContainer<Holder<Biome>> biomePalette;
                if (slimeSection.getBiomeTag() != null) {
                    DataResult<PalettedContainer<Holder<Biome>>> dataresult = instance.palettedContainerFactory().biomeContainerRWCodec()
                            .parse(NbtOps.INSTANCE, slimeSection.getBiomeTag())
                            .promotePartial((s) -> System.out.println("Recoverable error when parsing section " + x + "," + z + ": " + s));
                    biomePalette = dataresult.getOrThrow();
                } else {
                    biomePalette = new PalettedContainer<>(
                            instance.registryAccess().lookupOrThrow(Registries.BIOME).get(Biomes.PLAINS).orElseThrow(),
                            instance.palettedContainerFactory().biomeStrategy(), null);
                }

                if (sectionId < sections.length) {
                    sections[sectionId] = new LevelChunkSection(blockPalette, biomePalette);
                }
            }
        }

        LevelChunkTicks<Block> blockLevelChunkTicks = new LevelChunkTicks<>();
        if (chunk.getBlockTicks() != null) {
            CompoundTag btCompound = chunk.getBlockTicks();
            Optional<ListTag> blockTickList = btCompound.getList("block_ticks");
            if (blockTickList.isPresent()) {
                List<SavedTick<Block>> blockList = SavedTick.filterTickListForChunk(
                        BLOCK_TICKS_CODEC.parse(NbtOps.INSTANCE, blockTickList.get()).resultOrPartial().orElse(List.of()), pos);
                blockLevelChunkTicks = new LevelChunkTicks<>(blockList);
            }
        }

        LevelChunkTicks<Fluid> fluidLevelChunkTicks = new LevelChunkTicks<>();
        if (chunk.getFluidTicks() != null) {
            CompoundTag ftCompound = chunk.getFluidTicks();
            Optional<ListTag> fluidTickList = ftCompound.getList("fluid_ticks");
            if (fluidTickList.isPresent()) {
                List<SavedTick<Fluid>> fluidList = SavedTick.filterTickListForChunk(
                        FLUID_TICKS_CODEC.parse(NbtOps.INSTANCE, fluidTickList.get()).resultOrPartial().orElse(List.of()), pos);
                fluidLevelChunkTicks = new LevelChunkTicks<>(fluidList);
            }
        }

        UpgradeData upgradeData;
        if (chunk.getUpgradeData() != null) {
            upgradeData = new UpgradeData(chunk.getUpgradeData(), instance);
        } else {
            upgradeData = UpgradeData.EMPTY;
        }

        // Process tile entities
        List<CompoundTag> tileEntityList = extractTileEntities(chunk.getTileEntities());

        LevelChunk.PostLoadProcessor processor = tileEntityList.isEmpty()
                ? null
                : createPostLoadProcessor(instance, tileEntityList);

        SlimeChunkLevel nmsChunk = new SlimeChunkLevel(instance, chunk, pos, upgradeData,
                blockLevelChunkTicks, fluidLevelChunkTicks, 0L, sections, processor, null);

        // Height Maps
        EnumSet<Heightmap.Types> heightMapTypes = nmsChunk.getPersistedStatus().heightmapsAfter();
        CompoundTag heightMaps = chunk.getHeightMaps();
        EnumSet<Heightmap.Types> unsetHeightMaps = EnumSet.noneOf(Heightmap.Types.class);

        nmsChunk.starlight$setBlockNibbles(blockNibbles);
        nmsChunk.starlight$setSkyNibbles(skyNibbles);

        for (Heightmap.Types type : heightMapTypes) {
            String name = type.getSerializedName();

            Optional<long[]> heightMapOpt = heightMaps.getLongArray(name);
            if (heightMapOpt.isPresent()) {
                long[] heightMap = heightMapOpt.get();
                if (heightMap.length > 0) {
                    nmsChunk.setHeightmap(type, heightMap);
                } else {
                    unsetHeightMaps.add(type);
                }
            } else {
                unsetHeightMaps.add(type);
            }
        }

        if (!unsetHeightMaps.isEmpty()) {
            Heightmap.primeHeightmaps(nmsChunk, unsetHeightMaps);
        }

        // PDC
        if (chunk.getExtraData() != null && chunk.getExtraData().contains("ChunkBukkitValues")) {
            chunk.getExtraData().getCompound("ChunkBukkitValues").ifPresent(values -> {
                nmsChunk.persistentDataContainer.putAll(values);
            });
        }

        return nmsChunk;
    }

    public static SlimeChunkSectionSkeleton convertChunkSection(
            Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
            Codec<PalettedContainer<BlockState>> blockCodec,
            LevelChunkSection section, byte[] blockLight, byte[] skyLight) {

        CompoundTag blockStateTag;
        if (section.hasOnlyAir()) {
            blockStateTag = EMPTY_BLOCK_STATE_PALETTE;
        } else {
            blockStateTag = (CompoundTag) blockCodec.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow();
        }

        CompoundTag biomeTag;
        PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) section.getBiomes();
        if (biomes.data.palette().getSize() == 1 && biomes.data.palette().maybeHas((h) -> h.is(Biomes.PLAINS))) {
            biomeTag = EMPTY_BIOME_PALETTE;
        } else {
            biomeTag = (CompoundTag) biomeCodec.encodeStart(NbtOps.INSTANCE, section.getBiomes()).getOrThrow();
        }

        return new SlimeChunkSectionSkeleton(blockStateTag, biomeTag, blockLight, skyLight);
    }

    public static CompoundTag createPoiChunk(SlimeChunk chunk) {
        return createPoiChunkFromSlimeSections(chunk.getPoiChunkSections(), SharedConstants.getCurrentVersion().dataVersion().version());
    }

    public static CompoundTag createPoiChunkFromSlimeSections(CompoundTag slimePoiSections, int dataVersion) {
        CompoundTag tag = new CompoundTag();
        if (slimePoiSections != null) {
            tag.put("Sections", slimePoiSections);
        }
        tag.putInt("DataVersion", dataVersion);
        return tag;
    }

    public static CompoundTag toSlimeSections(PoiChunk poiChunk) {
        CompoundTag save = poiChunk.save();
        return getSlimeSectionsFromPoiCompound(save);
    }

    public static CompoundTag getSlimeSectionsFromPoiCompound(CompoundTag save) {
        if (save == null) return null;
        return save.getCompound("Sections").orElse(null);
    }

    private static List<CompoundTag> extractTileEntities(@Nullable CompoundTag tileEntitiesTag) {
        List<CompoundTag> result = new ArrayList<>();
        if (tileEntitiesTag == null) return result;
        tileEntitiesTag.getList("tileEntities").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getCompound(i).ifPresent(result::add);
            }
        });
        return result;
    }

    private static LevelChunk.PostLoadProcessor createPostLoadProcessor(SlimeLevelInstance level, List<CompoundTag> blockEntities) {
        return chunk -> {
            for (CompoundTag compoundTag : blockEntities) {
                boolean keepPacked = compoundTag.getBooleanOr("keepPacked", false);
                if (keepPacked) {
                    chunk.setBlockEntityNbt(compoundTag);
                } else {
                    BlockPos posFromTag = BlockEntity.getPosFromTag(chunk.getPos(), compoundTag);
                    BlockEntity blockEntity = BlockEntity.loadStatic(posFromTag, chunk.getBlockState(posFromTag), compoundTag, level.registryAccess());
                    if (blockEntity != null) {
                        chunk.setBlockEntity(blockEntity);
                    }
                }
            }
        };
    }
}
