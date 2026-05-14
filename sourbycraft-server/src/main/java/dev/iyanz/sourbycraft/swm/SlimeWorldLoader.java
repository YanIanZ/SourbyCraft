package dev.iyanz.sourbycraft.swm;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

public final class SlimeWorldLoader {

    private static final Path SLIME_DIR = Path.of("slime_worlds");
    private static final byte[] SLIME_MAGIC = {0x0B, 0x10, 0x00, 0x00};

    public record SlimeWorldInfo(String worldName, byte version, short minX, short minZ, short width, short depth) {}

    public static List<SlimeWorldInfo> discoverWorlds() {
        if (!Files.exists(SLIME_DIR)) return List.of();
        try (Stream<Path> files = Files.list(SLIME_DIR)) {
            return files.filter(p -> p.toString().endsWith(".slime"))
                .map(SlimeWorldLoader::readInfo)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } catch (IOException e) { return List.of(); }
    }

    private static SlimeWorldInfo readInfo(Path file) {
        try (DataInputStream in = open(file)) {
            byte ver = in.readByte();
            short mx = in.readShort(), mz = in.readShort();
            short w = in.readShort(), d = in.readShort();
            return new SlimeWorldInfo(
                file.getFileName().toString().replace(".slime", ""),
                ver, mx, mz, w, d
            );
        } catch (IOException e) { return null; }
    }

    public static void loadWorld(String worldName) throws IOException {
        Path file = SLIME_DIR.resolve(worldName + ".slime");
        Path worldDir = Path.of(worldName);
        if (Files.exists(worldDir.resolve("level.dat"))) return;

        try (DataInputStream in = open(file)) {
            byte ver = in.readByte();
            in.readShort(); in.readShort(); in.readShort(); in.readShort(); // skip bounds
            int extraLen = in.readInt();
            byte[] extra = new byte[extraLen];
            in.readFully(extra);

            // Write level.dat
            Files.createDirectories(worldDir);
            Files.write(worldDir.resolve("level.dat"), composeLevelDat(extra, worldName));
        }
    }

    public static DataInputStream open(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (raw.length < 9) return new DataInputStream(new ByteArrayInputStream(raw));

        DataInputStream head = new DataInputStream(new ByteArrayInputStream(raw));
        byte[] magic = new byte[4]; head.readFully(magic);
        byte ver = head.readByte();
        int len = head.readInt();

        byte[] body = new byte[raw.length - 9];
        System.arraycopy(raw, 9, body, 0, body.length);

        InputStream decompressed = switch (ver) {
            case 1 -> new InflaterInputStream(new ByteArrayInputStream(body));
            case 2 -> new GZIPInputStream(new ByteArrayInputStream(body));
            default -> new ByteArrayInputStream(body);
        };

        return new DataInputStream(new BufferedInputStream(decompressed));
    }

    private static byte[] composeLevelDat(byte[] extra, String name) throws IOException {
        // Wrap extra NBT into a valid level.dat structure
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeByte(10); // TAG_Compound
        dos.writeUTF("");  // root name
        // Copy the extra data as-is (it's already a CompoundTag in NBT format)
        dos.write(extra);
        return out.toByteArray();
    }

    public static void loadAll(net.minecraft.server.MinecraftServer server) {
        if (!dev.iyanz.sourbycraft.SourbyCraftConfig.swmEnabled) return;
        for (SlimeWorldInfo info : discoverWorlds()) {
            try {
                loadWorld(info.worldName());
                org.bukkit.Bukkit.createWorld(org.bukkit.WorldCreator.name(info.worldName()));
            } catch (Exception ignored) {}
        }
    }

    public static void scheduleAutoLoad() {
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            org.bukkit.Bukkit.getPluginManager().getPlugins()[0],
            () -> {
                for (SlimeWorldInfo info : discoverWorlds()) {
                    if (org.bukkit.Bukkit.getWorld(info.worldName()) != null) continue;
                    try {
                        loadWorld(info.worldName());
                        org.bukkit.Bukkit.createWorld(org.bukkit.WorldCreator.name(info.worldName()));
                    } catch (Exception ignored) {}
                }
            }, 40L
        );
    }

    private SlimeWorldLoader() {}
}
