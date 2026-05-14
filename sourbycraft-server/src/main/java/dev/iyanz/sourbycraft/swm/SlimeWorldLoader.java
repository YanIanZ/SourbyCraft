package dev.iyanz.sourbycraft.swm;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.bukkit.Bukkit;
import org.bukkit.WorldCreator;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

public final class SlimeWorldLoader {

    private static final Path SLIME_DIR = Path.of("slime_worlds");
    private static final byte[] MAGIC = {0x0B, 0x10, 0x00, 0x00};

    public record SlimeWorldInfo(String name, byte version) {}

    public static List<SlimeWorldInfo> discoverWorlds() {
        if (!Files.exists(SLIME_DIR)) return List.of();
        try (Stream<Path> f = Files.list(SLIME_DIR)) {
            return f.filter(p -> p.toString().endsWith(".slime"))
                .map(p -> new SlimeWorldInfo(p.getFileName().toString().replace(".slime", ""), (byte) 0))
                .collect(Collectors.toList());
        } catch (IOException e) { return List.of(); }
    }

    public static void loadAll(MinecraftServer server) {
        if (!dev.iyanz.sourbycraft.SourbyCraftConfig.swmEnabled) return;
        for (SlimeWorldInfo info : discoverWorlds()) {
            try {
                extract(info.name());
                Bukkit.createWorld(WorldCreator.name(info.name()));
            } catch (IOException ignored) {}
        }
    }

    public static void extract(String name) throws IOException {
        Path file = SLIME_DIR.resolve(name + ".slime");
        Path worldDir = Path.of(name);
        if (Files.exists(worldDir.resolve("level.dat"))) return;

        byte[] raw = Files.readAllBytes(file);
        DataInputStream in = decompress(raw);
        in.readByte();
        in.readShort(); in.readShort(); in.readShort(); in.readShort();
        int elen = in.readInt();
        byte[] extra = new byte[elen];
        in.readFully(extra);

        Files.createDirectories(worldDir);
        Files.write(worldDir.resolve("level.dat"), extra);
    }

    private static DataInputStream decompress(byte[] raw) throws IOException {
        if (raw.length < 9) return new DataInputStream(new ByteArrayInputStream(raw));
        DataInputStream h = new DataInputStream(new ByteArrayInputStream(raw));
        h.readFully(new byte[4]);
        byte ver = h.readByte();
        h.readInt();
        byte[] body = new byte[raw.length - 9];
        System.arraycopy(raw, 9, body, 0, body.length);

        InputStream dec = switch (ver) {
            case 1 -> new InflaterInputStream(new ByteArrayInputStream(body));
            case 2 -> new GZIPInputStream(new ByteArrayInputStream(body));
            default -> new ByteArrayInputStream(body);
        };
        return new DataInputStream(new BufferedInputStream(dec));
    }

    private SlimeWorldLoader() {}
}
