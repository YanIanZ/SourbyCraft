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
        getLogger().info("SourbyCraft SWM v1.0 enabled");
        loadAllSlimeWorlds();
    }

    @Override
    public void onDisable() {
        saveAllWorlds();
        instance = null;
    }

    public static SWMPlugin getInstance() { return instance; }

    // === World Loading ===

    public void loadAllSlimeWorlds() {
        if (!Files.exists(SLIME_DIR)) return;
        try (Stream<Path> files = Files.list(SLIME_DIR)) {
            files.filter(p -> p.toString().endsWith(".slime")).forEach(this::loadSlimeWorld);
        } catch (IOException ignored) {}
    }

    public World loadSlimeWorld(Path file) {
        String name = file.getFileName().toString().replace(".slime", "");
        World existing = getServer().getWorld(name);
        if (existing != null) return existing;

        Path worldDir = Path.of(name);
        try {
            if (!Files.exists(worldDir.resolve("level.dat"))) {
                extractToDir(file, worldDir);
            }
            World w = getServer().createWorld(WorldCreator.name(name));
            if (w != null) getLogger().info("Loaded: " + name);
            return w;
        } catch (Exception e) {
            getLogger().warning("Failed: " + name + " - " + e.getMessage());
            return null;
        }
    }

    public World loadSlimeWorld(String name) {
        Path file = SLIME_DIR.resolve(name + ".slime");
        if (!Files.exists(file)) {
            getLogger().warning("Not found: " + name + ".slime");
            return null;
        }
        return loadSlimeWorld(file);
    }

    // === World Saving ===

    public boolean saveWorld(String worldName) {
        World world = getServer().getWorld(worldName);
        if (world == null) return false;
        world.save();

        Path src = Path.of(worldName);
        Path dest = SLIME_DIR.resolve(worldName + ".slime");
        try {
            Files.createDirectories(SLIME_DIR);
            packDirToSlime(src, dest);
            getLogger().info("Saved: " + worldName + ".slime");
            return true;
        } catch (IOException e) {
            getLogger().warning("Save failed: " + worldName);
            return false;
        }
    }

    public void saveAllWorlds() {
        for (World w : getServer().getWorlds()) {
            saveWorld(w.getName());
        }
    }

    // === World Deletion ===

    public boolean deleteSlimeWorld(String name) {
        Path file = SLIME_DIR.resolve(name + ".slime");
        try {
            Files.deleteIfExists(file);
            Path worldDir = Path.of(name);
            if (Files.exists(worldDir)) {
                deleteDirectory(worldDir);
            }
            getLogger().info("Deleted: " + name);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // === World Creation ===

    public World createSlimeWorld(String name, World.Environment env) {
        WorldCreator creator = WorldCreator.name(name).environment(env).generateStructures(true);
        World w = getServer().createWorld(creator);
        if (w != null) {
            w.save();
            saveWorld(name);
        }
        return w;
    }

    // === Conversion ===

    public boolean convertToSlime(String worldName) {
        return saveWorld(worldName);
    }

    // === Listing ===

    public List<String> getSlimeWorldNames() {
        if (!Files.exists(SLIME_DIR)) return List.of();
        try (Stream<Path> files = Files.list(SLIME_DIR)) {
            return files.filter(p -> p.toString().endsWith(".slime"))
                .map(p -> p.getFileName().toString().replace(".slime", ""))
                .collect(Collectors.toList());
        } catch (IOException e) { return List.of(); }
    }

    public boolean isSlimeWorld(String name) {
        return Files.exists(SLIME_DIR.resolve(name + ".slime"));
    }

    // === I/O ===

    private void extractToDir(Path slimeFile, Path worldDir) throws IOException {
        Files.createDirectories(worldDir.resolve("region"));
        DataInputStream in = decompress(Files.readAllBytes(slimeFile));
        in.readByte(); // version
        in.readShort(); in.readShort(); // minX, minZ
        in.readShort(); in.readShort(); // width, depth
        int extraLen = in.readInt();
        byte[] extra = new byte[extraLen];
        in.readFully(extra);
        Files.write(worldDir.resolve("level.dat"), extra);
    }

    private void packDirToSlime(Path worldDir, Path slimeFile) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(new GZIPOutputStream(buf));
        out.writeByte(3); // version
        out.writeShort(0); out.writeShort(0); // minX, minZ
        out.writeShort(0); out.writeShort(0); // width, depth

        Path levelDat = worldDir.resolve("level.dat");
        byte[] extra = Files.exists(levelDat) ? Files.readAllBytes(levelDat) : new byte[0];
        out.writeInt(extra.length);
        out.write(extra);
        out.close();

        Files.write(slimeFile, buf.toByteArray());
    }

    private DataInputStream decompress(byte[] raw) throws IOException {
        if (raw.length < 9) return new DataInputStream(new ByteArrayInputStream(raw));
        DataInputStream head = new DataInputStream(new ByteArrayInputStream(raw));
        byte[] magic = new byte[4]; head.readFully(magic);
        byte ver = head.readByte();
        int len = head.readInt();
        byte[] body = new byte[raw.length - 9];
        System.arraycopy(raw, 9, body, 0, body.length);

        InputStream dec = switch (ver) {
            case 1 -> new InflaterInputStream(new ByteArrayInputStream(body));
            case 2 -> new GZIPInputStream(new ByteArrayInputStream(body));
            default -> new ByteArrayInputStream(body);
        };
        return new DataInputStream(new BufferedInputStream(dec));
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            }
        }
    }
}
