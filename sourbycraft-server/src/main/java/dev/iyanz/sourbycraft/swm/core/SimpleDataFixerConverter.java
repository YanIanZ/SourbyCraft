package dev.iyanz.sourbycraft.swm.core;

import ca.spottedleaf.dataconverter.minecraft.MCDataConverter;
import ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry;
import ca.spottedleaf.dataconverter.minecraft.util.Version;
import dev.iyanz.sourbycraft.swm.api.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.ArrayList;

public final class SimpleDataFixerConverter {

    public static SlimeWorld applyDataFixers(SlimeWorld world) {
        int currentVersion = Version.getCurrentVersion();
        if (world.getDataVersion() >= currentVersion) return world;

        Long2ObjectOpenHashMap<SlimeChunk> fixedChunks = new Long2ObjectOpenHashMap<>();
        for (var entry : world.getChunks().entrySet()) {
            SlimeChunk chunk = entry.getValue();
            if (chunk == null) continue;

            CompoundTag fixedTileEntities = chunk.getTileEntities() != null ?
                fixTileEntities(chunk.getTileEntities(), world.getDataVersion(), currentVersion) : null;
            ListTag fixedEntities = chunk.getEntities() != null ?
                fixEntities(chunk.getEntities(), world.getDataVersion(), currentVersion) : null;

            fixedChunks.put(entry.getKey(),
                new SlimeChunkSkeleton(chunk.getX(), chunk.getZ(), new ArrayList<>(chunk.getSections()),
                    chunk.getHeightMaps(), fixedTileEntities, fixedEntities,
                    chunk.getExtraData(), chunk.getUpgradeData(), chunk.getBlockTicks(),
                    chunk.getFluidTicks(), chunk.getPoiChunkSections()));
        }

        return new SkeletonSlimeWorld(world.getName(), world.getLoader(), fixedChunks,
            world.getExtraData(), world.getPropertyMap(), currentVersion, world.isReadOnly());
    }

    private static CompoundTag fixTileEntities(CompoundTag tileEntities, int fromVersion, int toVersion) {
        if (tileEntities.contains("tileEntities")) {
            tileEntities.getList("tileEntities").ifPresent(list -> {
                ListTag fixed = new ListTag();
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag te = list.getCompoundOrEmpty(i);
                    CompoundTag converted = MCDataConverter.convertTag(MCTypeRegistry.TILE_ENTITY, te, fromVersion, toVersion);
                    fixed.add(converted != null ? converted : te);
                }
                CompoundTag result = new CompoundTag();
                result.put("tileEntities", fixed);
            });
        }
        return tileEntities;
    }

    private static ListTag fixEntities(ListTag entities, int fromVersion, int toVersion) {
        ListTag fixed = new ListTag();
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entity = entities.getCompoundOrEmpty(i);
            CompoundTag converted = MCDataConverter.convertTag(MCTypeRegistry.ENTITY, entity, fromVersion, toVersion);
            fixed.add(converted != null ? converted : entity);
        }
        return fixed;
    }

    private SimpleDataFixerConverter() {}
}
