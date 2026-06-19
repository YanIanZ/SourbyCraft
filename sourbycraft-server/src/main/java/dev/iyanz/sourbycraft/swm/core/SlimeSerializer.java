package dev.iyanz.sourbycraft.swm.core;

import dev.iyanz.sourbycraft.swm.api.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import java.io.*;
import java.util.Map;
import java.util.zip.Deflater;

public final class SlimeSerializer {
    private static final byte[] MAGIC = new byte[]{(byte)0xB1, (byte)0x0B};
    private static final byte VERSION = 0x0D;

    public static byte[] serialize(SlimeWorld world) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.write(MAGIC);
        out.writeByte(VERSION);
        out.writeInt(world.getDataVersion());

        int flags = 0;
        SlimePropertyMap props = world.getPropertyMap();
        if (props != null) {
            if (props.savePoi()) flags |= 1;
            if (props.saveBlockTicks()) flags |= 2;
            if (props.saveFluidTicks()) flags |= 4;
        }
        out.writeByte(flags);

        byte[] chunkData = serializeChunks(world.getChunks(), flags);
        // Compress chunk blob on the common pool. The SwmIoExecutor pool is tiny
        // (2-4 threads) and saveWorld()'s write task already runs on it; submitting
        // a sub-task back to the same pool and blocking on .get() would deadlock.
        java.util.concurrent.Future<byte[]> chunkFuture =
                java.util.concurrent.ForkJoinPool.commonPool().submit(() -> compress(chunkData));

        CompoundTag extraCompound;
        if (world.getExtraData() != null && !world.getExtraData().isEmpty()) {
            extraCompound = world.getExtraData().copy();
        } else {
            extraCompound = new CompoundTag();
        }
        if (props != null) {
            CompoundTag propsTag = new CompoundTag();
            propsTag.putString("defaultBiome", props.getDefaultBiome());
            propsTag.putInt("seaLevel", props.getSeaLevel());
            propsTag.putBoolean("savePoi", props.savePoi());
            propsTag.putBoolean("saveBlockTicks", props.saveBlockTicks());
            propsTag.putBoolean("saveFluidTicks", props.saveFluidTicks());
            extraCompound.put("properties", propsTag);
        }
        ByteArrayOutputStream extraBaos = new ByteArrayOutputStream();
        // Uncompressed NBT here so the outer zlib pass is the only compression
        // layer. Previously this used NbtIo.writeCompressed (gzip) which double-
        // wrapped the payload — the v13 reader's `NbtIo.read(...)` hit the gzip
        // magic byte 0x1F as a tag id and threw "Invalid tag id: 31" on every
        // SS2 island join.
        NbtIo.write(extraCompound, new DataOutputStream(extraBaos));
        byte[] extraRaw = extraBaos.toByteArray();
        byte[] compressedExtra = compress(extraRaw);

        byte[] compressedChunks;
        try {
            compressedChunks = chunkFuture.get();
        } catch (Exception e) {
            throw new IOException("SWM chunk compression failed", e);
        }
        out.writeInt(compressedChunks.length);
        out.writeInt(chunkData.length);
        out.write(compressedChunks);

        out.writeInt(compressedExtra.length);
        out.writeInt(extraRaw.length);
        out.write(compressedExtra);

        return baos.toByteArray();
    }

    private static byte[] serializeChunks(Map<Long, ? extends SlimeChunk> chunks, int flags) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        int count = 0;
        for (var entry : chunks.entrySet()) {
            if (entry.getValue() != null) count++;
        }
        out.writeInt(count);

        for (var entry : chunks.entrySet()) {
            SlimeChunk chunk = entry.getValue();
            if (chunk == null) continue;

            out.writeInt(chunk.getX());
            out.writeInt(chunk.getZ());

            var sections = chunk.getSections();
            out.writeInt(sections.size());
            for (SlimeChunkSection section : sections) {
                int secFlags = 0;
                if (section.getSkyLight() != null) secFlags |= 2;
                if (section.getBlockLight() != null) secFlags |= 1;
                out.writeByte(secFlags);

                if (section.getSkyLight() != null) out.write(section.getSkyLight());
                if (section.getBlockLight() != null) out.write(section.getBlockLight());

                byte[] blockTag = section.getBlockStatesTag() != null ?
                    toBytes(section.getBlockStatesTag()) : new byte[0];
                out.writeInt(blockTag.length);
                out.write(blockTag);

                byte[] biomeTag = section.getBiomeTag() != null ?
                    toBytes(section.getBiomeTag()) : new byte[0];
                out.writeInt(biomeTag.length);
                out.write(biomeTag);
            }

            byte[] hmBytes = chunk.getHeightMaps() != null ?
                toBytes(chunk.getHeightMaps()) : new byte[0];
            out.writeInt(hmBytes.length);
            out.write(hmBytes);

            if ((flags & 1) != 0) {
                byte[] poi = chunk.getPoiChunkSections() != null ?
                    toBytes(chunk.getPoiChunkSections()) : new byte[0];
                out.writeInt(poi.length);
                out.write(poi);
            }
            if ((flags & 2) != 0) {
                byte[] bt = chunk.getBlockTicks() != null ?
                    toBytes(chunk.getBlockTicks()) : new byte[0];
                out.writeInt(bt.length);
                out.write(bt);
            }
            if ((flags & 4) != 0) {
                byte[] ft = chunk.getFluidTicks() != null ?
                    toBytes(chunk.getFluidTicks()) : new byte[0];
                out.writeInt(ft.length);
                out.write(ft);
            }

            byte[] teBytes = chunk.getTileEntities() != null ?
                toBytes(chunk.getTileEntities()) : new byte[0];
            out.writeInt(teBytes.length);
            out.write(teBytes);

            byte[] entBytes = chunk.getEntities() != null ?
                toBytes(chunk.getEntities()) : new byte[0];
            out.writeInt(entBytes.length);
            out.write(entBytes);

            byte[] extra = chunk.getExtraData() != null ?
                toBytes(chunk.getExtraData()) : new byte[0];
            out.writeInt(extra.length);
            out.write(extra);
        }

        return baos.toByteArray();
    }

    private static byte[] toBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        NbtIo.write(tag, dos);
        return baos.toByteArray();
    }

    private static byte[] toBytes(ListTag tag) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        NbtIo.writeUnnamedTag(tag, dos);
        return baos.toByteArray();
    }

    private static byte[] compress(byte[] data) throws IOException {
        Deflater deflater = new Deflater(gg.pufferfish.pufferfish.PufferfishConfig.swmCompressionLevel);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            baos.write(buf, 0, n);
        }
        deflater.end();
        return baos.toByteArray();
    }

    private SlimeSerializer() {}
}
