package dev.iyanz.sourbycraft.swm.core.reader;

import dev.iyanz.sourbycraft.swm.api.*;
import dev.iyanz.sourbycraft.swm.api.exceptions.*;
import dev.iyanz.sourbycraft.swm.core.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.*;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

public class v13SlimeWorldReader implements VersionedByteSlimeWorldReader<SlimeWorld> {

    @Override
    public SlimeWorld deserialize(SlimeLoader loader, String worldName, byte[] serializedWorld,
                                  SlimePropertyMap propertyMap, boolean readOnly)
            throws CorruptedWorldException, NewerFormatException, IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(serializedWorld));
        byte[] magic = new byte[2];
        in.readFully(magic);
        if (magic[0] != (byte)0xB1 || magic[1] != (byte)0x0B) {
            throw new CorruptedWorldException("Invalid magic bytes");
        }
        byte formatVer = in.readByte();
        if (formatVer != 0x0D) {
            throw new NewerFormatException("Expected v13 (0x0D) but got " + (formatVer & 0xFF));
        }

        int worldDataVersion = in.readInt();
        int flags = in.readByte() & 0xFF;

        int compressedChunkLen = in.readInt();
        int uncompressedChunkLen = in.readInt();
        byte[] compressedChunks = new byte[compressedChunkLen];
        in.readFully(compressedChunks);
        byte[] chunkData = decompress(compressedChunks, uncompressedChunkLen);

        Long2ObjectOpenHashMap<SlimeChunk> chunks = readChunks(chunkData, flags);

        int compressedExtraLen = in.readInt();
        int uncompressedExtraLen = in.readInt();
        byte[] compressedExtra = new byte[compressedExtraLen];
        in.readFully(compressedExtra);
        byte[] extraRaw = decompress(compressedExtra, uncompressedExtraLen);

        // Backward compatibility: pre-r9 worlds wrote extra data via
        // NbtIo.writeCompressed (gzip) inside the outer zlib pass. The reader
        // expected uncompressed NBT and threw "Invalid tag id: 31" because byte 0
        // of a gzip stream is 0x1F. Detect the gzip magic and dispatch
        // accordingly; fresh writes from r9 onwards use plain NbtIo.write.
        CompoundTag extraData;
        if (extraRaw.length >= 2 && (extraRaw[0] & 0xFF) == 0x1F && (extraRaw[1] & 0xFF) == 0x8B) {
            extraData = NbtIo.readCompressed(new ByteArrayInputStream(extraRaw), NbtAccounter.unlimitedHeap());
        } else {
            extraData = NbtIo.read(new DataInputStream(new ByteArrayInputStream(extraRaw)));
        }
        SlimePropertyMap resolvedProps = mergeProperties(propertyMap, extraData);

        return new SkeletonSlimeWorld(worldName, loader, chunks, extraData, resolvedProps, worldDataVersion, readOnly);
    }

    private Long2ObjectOpenHashMap<SlimeChunk> readChunks(byte[] data, int flags) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int count = in.readInt();
        Long2ObjectOpenHashMap<SlimeChunk> chunks = new Long2ObjectOpenHashMap<>(count);

        for (int i = 0; i < count; i++) {
            int x = in.readInt();
            int z = in.readInt();
            int sectionCount = in.readInt();
            List<SlimeChunkSection> sections = new ArrayList<>(sectionCount);

            for (int s = 0; s < sectionCount; s++) {
                int secFlags = in.readByte() & 0xFF;
                byte[] skyLight = (secFlags & 2) != 0 ? readNBytes(in, 2048) : null;
                byte[] blockLight = (secFlags & 1) != 0 ? readNBytes(in, 2048) : null;

                CompoundTag blockTag = readCompound(in);
                CompoundTag biomeTag = readCompound(in);
                sections.add(new SlimeChunkSectionSkeleton(blockTag, biomeTag, blockLight, skyLight));
            }

            CompoundTag heightMaps = readCompound(in);

            CompoundTag poiChunk = null;
            if ((flags & 1) != 0) poiChunk = readCompound(in);
            CompoundTag blockTicks = null;
            if ((flags & 2) != 0) blockTicks = readCompound(in);
            CompoundTag fluidTicks = null;
            if ((flags & 4) != 0) fluidTicks = readCompound(in);

            CompoundTag tileEntities = readCompound(in);
            ListTag entities = readListTag(in);
            CompoundTag extraData = readCompound(in);

            chunks.put(((long) x << 32) | (z & 0xFFFFFFFFL),
                new SlimeChunkSkeleton(x, z, sections, heightMaps, tileEntities, entities,
                    extraData, null, blockTicks, fluidTicks, poiChunk));
        }

        return chunks;
    }

    private @Nullable CompoundTag readCompound(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len <= 0) return null;
        byte[] data = new byte[len];
        in.readFully(data);
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(data)));
    }

    private @Nullable ListTag readListTag(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len <= 0) return null;
        byte[] data = new byte[len];
        in.readFully(data);
        Tag tag = NbtIo.readUnnamedTag(new DataInputStream(new ByteArrayInputStream(data)), NbtAccounter.unlimitedHeap());
        return tag instanceof ListTag ? (ListTag) tag : null;
    }

    private byte[] readNBytes(DataInputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    private static byte[] decompress(byte[] compressedData, int uncompressedSize) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressedData);
        byte[] result = new byte[uncompressedSize];
        int pos = 0;
        try {
            while (pos < uncompressedSize && !inflater.finished()) {
                int n = inflater.inflate(result, pos, uncompressedSize - pos);
                if (n <= 0) break;
                pos += n;
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("Failed to decompress data", e);
        } finally {
            inflater.end();
        }
        return result;
    }

    private SlimePropertyMap mergeProperties(@Nullable SlimePropertyMap defaults, CompoundTag extraData) {
        SlimePropertyMap result = defaults != null ? defaults : new SlimePropertyMap();
        if (extraData != null && extraData.contains("properties")) {
            extraData.getCompound("properties").ifPresent(props -> {
                props.getString("defaultBiome").ifPresent(result::setDefaultBiome);
                props.getInt("seaLevel").ifPresent(result::setSeaLevel);
                props.getBoolean("savePoi").ifPresent(result::setSavePoi);
                props.getBoolean("saveBlockTicks").ifPresent(result::setSaveBlockTicks);
                props.getBoolean("saveFluidTicks").ifPresent(result::setSaveFluidTicks);
            });
        }
        return result;
    }
}
