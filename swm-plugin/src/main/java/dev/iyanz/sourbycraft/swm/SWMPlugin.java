package dev.iyanz.sourbycraft.swm;

import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

public class SWMPlugin extends JavaPlugin {

    private static SWMPlugin instance;
    private static final Path SLIME_DIR = Path.of("slime_worlds");
    private static final byte[] MAGIC = {0x0B, 0x10, 0x00, 0x00};

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("SourbyCraft SWM enabled");
        loadAllSlimeWorlds();
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    public static SWMPlugin getInstance() { return instance; }

    public void loadAllSlimeWorlds() {
        if (!Files.exists(SLIME_DIR)) return;
        try (Stream<Path> files = Files.list(SLIME_DIR)) {
            files.filter(p -> p.toString().endsWith(".slime")).forEach(this::loadSlimeWorld);
        } catch (IOException ignored) {}
    }

    public void loadSlimeWorld(Path file) {
        String name = file.getFileName().toString().replace(".slime", "");
        if (getServer().getWorld(name) != null) return;
        Path worldDir = Path.of(name);

        try {
            if (!Files.exists(worldDir.resolve("level.dat"))) {
                extractSlime(file, worldDir);
            }
            World w = getServer().createWorld(WorldCreator.name(name));
            if (w != null) getLogger().info("Loaded slime world: " + name);
        } catch (Exception e) {
            getLogger().warning("Failed to load " + name + ": " + e.getMessage());
        }
    }

    private void extractSlime(Path file, Path worldDir) throws IOException {
        Files.createDirectories(worldDir.resolve("region"));
        byte[] raw = Files.readAllBytes(file);
        if (raw.length < 9) return;

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

        DataInputStream in = new DataInputStream(new BufferedInputStream(decompressed));
        in.readByte(); in.readShort(); in.readShort(); in.readShort(); in.readShort();
        int extraLen = in.readInt();
        byte[] extra = new byte[extraLen];
        in.readFully(extra);
        Files.write(worldDir.resolve("level.dat"), extra);
    }

    public List<String> getSlimeWorldNames() {
        if (!Files.exists(SLIME_DIR)) return List.of();
        try (Stream<Path> files = Files.list(SLIME_DIR)) {
            return files.filter(p -> p.toString().endsWith(".slime"))
                .map(p -> p.getFileName().toString().replace(".slime", ""))
                .collect(Collectors.toList());
        } catch (IOException e) { return List.of(); }
    }
}
