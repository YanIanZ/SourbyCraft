package dev.iyanz.sourbycraft.awf.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.awf.AwfWorldStore;
import dev.iyanz.sourbycraft.awf.ChunkKey;
import dev.iyanz.sourbycraft.awf.WorldRole;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.Deflater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Files are written here exactly as SourbyCraft's former SlimeSerializer wrote SRF v13. */
class SlimeImporterTest {

    @TempDir Path dir;

    private static final int DATA_VERSION = 4440;

    private static byte[] nbt(final CompoundTag tag) throws IOException {
        return tag == null ? new byte[0] : SlimeImporter.encode(tag);
    }

    private static byte[] list(final ListTag tag) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeUnnamedTag(tag, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static void prefixed(final DataOutputStream out, final byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] deflate(final byte[] data) {
        final Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    private static CompoundTag named(final String key, final String value) {
        final CompoundTag tag = new CompoundTag();
        tag.putString(key, value);
        return tag;
    }

    /** One chunk with blocks in its lowest and fifth section, a chest, a cow and a POI section. */
    private static void chunk(final DataOutputStream out, final int x, final int z, final int flags,
                              final boolean withEntity) throws IOException {
        out.writeInt(x);
        out.writeInt(z);
        out.writeInt(24);
        for (int s = 0; s < 24; s++) {
            final boolean filled = s == 0 || s == 4;
            out.writeByte(filled ? 3 : 0);           // sky and block light
            if (filled) {
                out.write(new byte[2048]);
                out.write(new byte[2048]);
            }
            prefixed(out, filled ? nbt(named("palette", "stone-" + s)) : new byte[0]);
            prefixed(out, filled ? nbt(named("palette", "plains")) : new byte[0]);
        }
        final CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray("MOTION_BLOCKING", new long[37]);
        prefixed(out, nbt(heightmaps));
        if ((flags & 1) != 0) prefixed(out, nbt(named("0", "home")));
        if ((flags & 2) != 0) {
            final CompoundTag ticks = new CompoundTag();
            final ListTag list = new ListTag();
            list.add(named("i", "minecraft:redstone_wire"));
            ticks.put("block_ticks", list);
            prefixed(out, nbt(ticks));
        }
        final CompoundTag tiles = new CompoundTag();
        final ListTag tileList = new ListTag();
        tileList.add(named("id", "minecraft:chest"));
        tiles.put("tileEntities", tileList);
        prefixed(out, nbt(tiles));
        final ListTag entities = new ListTag();
        if (withEntity) entities.add(named("id", "minecraft:cow"));
        prefixed(out, withEntity ? list(entities) : new byte[0]);
        final CompoundTag extra = new CompoundTag();
        extra.put("ChunkBukkitValues", named("plugin:key", "value"));
        prefixed(out, nbt(extra));
    }

    private static byte[] file(final int flags) throws IOException {
        final ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        final DataOutputStream chunkOut = new DataOutputStream(chunks);
        chunkOut.writeInt(2);
        chunk(chunkOut, 0, 0, flags, true);
        chunk(chunkOut, -1, 3, flags, false);
        final byte[] chunkData = chunks.toByteArray();
        final byte[] extra = nbt(new CompoundTag());

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] {(byte) 0xB1, 0x0B});
        out.writeByte(0x0D);
        out.writeInt(DATA_VERSION);
        out.writeByte(flags);
        final byte[] compressed = deflate(chunkData);
        out.writeInt(compressed.length);
        out.writeInt(chunkData.length);
        out.write(compressed);
        final byte[] compressedExtra = deflate(extra);
        out.writeInt(compressedExtra.length);
        out.writeInt(extra.length);
        out.write(compressedExtra);
        return bytes.toByteArray();
    }

    private static CompoundTag stored(final Path world, final String folder, final ChunkKey key) throws IOException {
        final byte[] bytes = AwfWorldStore.open(AuroraTemplates.store(world, folder), WorldRole.READ_ONLY, 1)
            .read(key).orElseThrow();
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    @Test
    void chunksBecomeTheEnginesOwnChunkFormat() throws IOException {
        final Path world = this.dir.resolve("isl");
        final SlimeImporter.Result result = SlimeImporter.importInto(file(1 | 2), world, -4);
        assertEquals(new SlimeImporter.Result(2, 1, 2, DATA_VERSION), result);

        final CompoundTag chunk = stored(world, "region", new ChunkKey(0, 0));
        assertEquals(DATA_VERSION, chunk.getIntOr("DataVersion", 0), "kept, so data fixers upgrade old chunks");
        assertEquals("minecraft:full", chunk.getStringOr("Status", ""));
        assertEquals(-4, chunk.getIntOr("yPos", 0));
        final ListTag sections = chunk.getListOrEmpty("sections");
        assertEquals(2, sections.size(), "empty sections are left out, as the engine does");
        assertEquals(-4, sections.getCompound(0).orElseThrow().getByteOr("Y", (byte) 99));
        assertEquals(0, sections.getCompound(1).orElseThrow().getByteOr("Y", (byte) 99), "fifth section from -4");
        assertEquals("stone-4", sections.getCompound(1).orElseThrow().getCompound("block_states").orElseThrow()
            .getStringOr("palette", ""));
        assertFalse(sections.getCompound(0).orElseThrow().contains("SkyLight"), "light is recomputed, not imported");
        assertFalse(chunk.contains("isLightOn"));
        assertEquals("minecraft:chest", chunk.getListOrEmpty("block_entities").getCompound(0).orElseThrow()
            .getStringOr("id", ""));
        assertEquals(1, chunk.getListOrEmpty("block_ticks").size());
        assertEquals("value", chunk.getCompound("ChunkBukkitValues").orElseThrow().getStringOr("plugin:key", ""));
        assertTrue(chunk.getCompound("Heightmaps").orElseThrow().contains("MOTION_BLOCKING"));

        final CompoundTag entities = stored(world, "entities", new ChunkKey(0, 0));
        assertEquals("minecraft:cow", entities.getListOrEmpty("Entities").getCompound(0).orElseThrow().getStringOr("id", ""));
        final CompoundTag poi = stored(world, "poi", new ChunkKey(-1, 3));
        assertEquals("home", poi.getCompound("Sections").orElseThrow().getStringOr("0", ""));
        assertEquals(Set.of(new ChunkKey(0, 0)),
            AwfWorldStore.open(AuroraTemplates.store(world, "entities"), WorldRole.READ_ONLY, 1).keys(),
            "a chunk without entities gets no entity chunk");
    }

    @Test
    void sectionHeightsFollowTheTargetDimension() throws IOException {
        final Path world = this.dir.resolve("nether");
        SlimeImporter.importInto(file(0), world, 0);
        final CompoundTag chunk = stored(world, "region", new ChunkKey(-1, 3));
        assertEquals(0, chunk.getIntOr("yPos", 99));
        assertEquals(4, chunk.getListOrEmpty("sections").getCompound(1).orElseThrow().getByteOr("Y", (byte) 99));
        assertFalse(Files.exists(AuroraTemplates.store(world, "poi")), "no POI saved, no POI store");
    }

    /** Version 12: zstd, no world flags, two light booleans per section, wrapped entities. */
    private static byte[] v12File() throws Exception {
        final ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(chunks);
        out.writeInt(1);
        out.writeInt(2);
        out.writeInt(-5);
        out.writeInt(16);
        for (int s = 0; s < 16; s++) {
            final boolean filled = s == 3;
            out.writeBoolean(filled);               // block light
            if (filled) out.write(new byte[2048]);
            out.writeBoolean(false);                // sky light
            prefixed(out, filled ? nbt(named("palette", "netherrack")) : new byte[0]);
            prefixed(out, filled ? nbt(named("palette", "nether_wastes")) : new byte[0]);
        }
        prefixed(out, nbt(new CompoundTag()));
        final CompoundTag tiles = new CompoundTag();
        tiles.put("tileEntities", new ListTag());
        prefixed(out, nbt(tiles));
        final CompoundTag entities = new CompoundTag();
        final ListTag entityList = new ListTag();
        entityList.add(named("id", "minecraft:strider"));
        entities.put("entities", entityList);
        prefixed(out, nbt(entities));
        prefixed(out, nbt(new CompoundTag()));
        final Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd");
        final java.lang.reflect.Method compress = zstd.getMethod("compress", byte[].class);
        final byte[] chunkData = chunks.toByteArray();
        final byte[] extra = nbt(new CompoundTag());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream file = new DataOutputStream(bytes);
        file.write(new byte[] {(byte) 0xB1, 0x0B});
        file.writeByte(0x0C);
        file.writeInt(4189);
        for (final byte[] blob : new byte[][] {chunkData, extra}) {
            final byte[] compressed = (byte[]) compress.invoke(null, (Object) blob);
            file.writeInt(compressed.length);
            file.writeInt(blob.length);
            file.write(compressed);
        }
        return bytes.toByteArray();
    }

    @Test
    void version12FilesAreReadToo() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            SlimeImporterTest.class.getClassLoader().getResource("com/github/luben/zstd/Zstd.class") != null,
            "zstd-jni is not on the test classpath");
        final Path world = this.dir.resolve("old-nether");
        assertEquals(new SlimeImporter.Result(1, 1, 0, 4189), SlimeImporter.importInto(v12File(), world, 0));
        final CompoundTag chunk = stored(world, "region", new ChunkKey(2, -5));
        assertEquals(3, chunk.getListOrEmpty("sections").getCompound(0).orElseThrow().getByteOr("Y", (byte) 99));
        assertEquals("minecraft:strider", stored(world, "entities", new ChunkKey(2, -5)).getListOrEmpty("Entities")
            .getCompound(0).orElseThrow().getStringOr("id", ""));
    }

    @Test
    void otherFormatsAreRefusedBeforeAnythingIsWritten() throws IOException {
        final byte[] good = file(0);
        final byte[] asp = good.clone();
        asp[2] = 0x0B;
        final Path world = this.dir.resolve("x");
        assertThrows(IOException.class, () -> SlimeImporter.importInto(asp, world, -4));
        assertThrows(IOException.class, () -> SlimeImporter.importInto(new byte[] {1, 2, 3, 4}, world, -4));
        final byte[] truncated = java.util.Arrays.copyOf(good, good.length / 2);
        assertThrows(IOException.class, () -> SlimeImporter.importInto(truncated, world, -4));
        assertFalse(Files.exists(world));
    }
}
