package dev.iyanz.sourbycraft.swm;

import net.minecraft.world.level.chunk.storage.RegionFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class SlimeWorldLoader {

    private static final Path SLIME_DIR = Path.of("slime_worlds");
    private static final byte[] SLIME_HEADER = new byte[]{0x0B, 0x10, 0x00, 0x00};

    public static List<SlimeWorldInfo> discoverWorlds() {
        if (!Files.exists(SLIME_DIR)) return List.of();
        try (Stream<Path> files = Files.list(SLIME_DIR)) {
            return files.filter(p -> p.toString().endsWith("."))
                .map(SlimeWorldLoader::readSlimeInfo)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static SlimeWorldInfo readSlimeInfo(Path file) {
        try {
            byte[] data = Files.readAllBytes(file);
            data = decompress(data);

            // Read version (byte)
            byte version = data[0];

            // Read world name and properties
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data, 1, data.length - 1));

            // Read chunk count
            short minX = dis.readShort();
            short minZ = dis.readShort();
            short width = dis.readShort();
            short depth = dis.readShort();

            byte[] extraData = new byte[dis.readInt()];
            dis.readFully(extraData);

            // Parse NBT from extraData
            net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.read(
                new DataInputStream(new ByteArrayInputStream(extraData))
            );

            return new SlimeWorldInfo(file.getFileName().toString().replace("."),
                root, minX, minZ, width, depth, version);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] decompress(byte[] data) throws IOException {
        if (data.length < 5) return data;
        if (java.util.Arrays.equals(Arrays.copyOf(data, 4), SLIME_HEADER)) {
            // Slime format
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
            dis.readInt(); // skip magic
            byte version = dis.readByte();
            int length = dis.readInt();
            byte[] compressed = new byte[data.length - 9];
            System.arraycopy(data, 9, compressed, 0, compressed.length);

            if (version == 1) {
                return new InflaterInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
            } else if (version == 2) {
                return new GZIPInputStream(new ByteArrayInputStream(compressed)).readAllBytes();
            } else if (version == 3) {
                // Raw uncompressed
                return compressed;
            }
        }
        return data;
    }

    public record SlimeWorldInfo(
        String worldName,
        net.minecraft.nbt.CompoundTag extraData,
        short minX, short minZ,
        short width, short depth,
        byte version
    ) {}

    private SlimeWorldLoader() {}
}
