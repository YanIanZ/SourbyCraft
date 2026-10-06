package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Chunks are built in the shape SerializableChunkData#write gives them. */
class ChunkPruningTest {

    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private static final Executor DIRECT = Runnable::run;

    private static byte[] encode(final CompoundTag tag) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    /** A chunk whose one section holds the given blocks (palette names), plus optional block entities. */
    private static byte[] chunk(final String status, final boolean blockEntity, final String... palette) throws IOException {
        return encode(chunkTag(status, blockEntity, palette));
    }

    private static CompoundTag chunkTag(final String status, final boolean blockEntity, final String... palette) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("Status", status);
        final ListTag sections = new ListTag();
        for (int y = -4; y < 0; y++) {
            final CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) y);
            final CompoundTag states = new CompoundTag();
            final ListTag entries = new ListTag();
            for (final String name : y == -1 ? palette : new String[] {"minecraft:air"}) {
                final CompoundTag entry = new CompoundTag();
                entry.putString("Name", name);
                entries.add(entry);
            }
            states.put("palette", entries);
            section.put("block_states", states);
            sections.add(section);
        }
        final CompoundTag lightOnly = new CompoundTag();
        lightOnly.putByte("Y", (byte) 0);
        lightOnly.putByteArray("SkyLight", new byte[2048]);
        sections.add(lightOnly);
        tag.put("sections", sections);
        final ListTag blockEntities = new ListTag();
        if (blockEntity) {
            final CompoundTag chest = new CompoundTag();
            chest.putString("id", "minecraft:chest");
            blockEntities.add(chest);
        }
        tag.put("block_entities", blockEntities);
        return tag;
    }

    /** An empty full chunk with everything else SerializableChunkData writes, all empty. */
    private static CompoundTag emptyFullChunk() {
        final CompoundTag tag = chunkTag("minecraft:full", false, "minecraft:air");
        tag.putInt("DataVersion", 4790);
        tag.putInt("xPos", 0);
        tag.putInt("yPos", -4);
        tag.putInt("zPos", 0);
        tag.putLong("LastUpdate", 10L);
        tag.putLong("InhabitedTime", 0L);
        tag.putBoolean("isLightOn", false);
        tag.putInt("starlight.light_version", 10);
        tag.put("block_ticks", new ListTag());
        tag.put("fluid_ticks", new ListTag());
        final ListTag postProcessing = new ListTag();
        for (int i = 0; i < 4; i++) postProcessing.add(new ListTag());
        tag.put("PostProcessing", postProcessing);
        tag.put("Heightmaps", new CompoundTag());
        final CompoundTag structures = new CompoundTag();
        structures.put("starts", new CompoundTag());
        structures.put("References", new CompoundTag());
        tag.put("structures", structures);
        return tag;
    }

    /** Sets every section's biome palette. */
    private static CompoundTag withBiomes(final CompoundTag tag, final String... biomes) {
        final ListTag sections = tag.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            final CompoundTag section = sections.getCompound(i).orElseThrow();
            final ListTag palette = new ListTag();
            for (final String biome : biomes) palette.add(net.minecraft.nbt.StringTag.valueOf(biome));
            final CompoundTag container = new CompoundTag();
            container.put("palette", palette);
            if (biomes.length > 1) container.putLongArray("data", new long[1]);
            section.put("biomes", container);
        }
        return tag;
    }

    @Test
    void nonRegenerableDataKeepsAnOtherwiseEmptyChunk() throws IOException {
        assertTrue(ChunkPruning.isEmpty("region", encode(emptyFullChunk())),
            "the full SerializableChunkData shape with nothing in it is still prunable");
        assertTrue(ChunkPruning.isEmpty("region", encode(withBiomes(emptyFullChunk(), "minecraft:the_void"))));
        assertTrue(ChunkPruning.isEmpty("region", encode(withBiomes(emptyFullChunk(), "minecraft:plains"))));

        final CompoundTag pdc = emptyFullChunk();
        final CompoundTag values = new CompoundTag();
        values.putString("myplugin:claim", "owner");
        pdc.put("ChunkBukkitValues", values);
        assertFalse(ChunkPruning.isEmpty("region", encode(pdc)), "plugin data on the chunk is kept");
        final CompoundTag emptyPdc = emptyFullChunk();
        emptyPdc.put("ChunkBukkitValues", new CompoundTag());
        assertTrue(ChunkPruning.isEmpty("region", encode(emptyPdc)), "an empty container holds nothing");

        assertFalse(ChunkPruning.isEmpty("region", encode(withBiomes(emptyFullChunk(), "minecraft:desert"))),
            "a biome the void generator may not make again is kept");
        assertFalse(ChunkPruning.isEmpty("region",
            encode(withBiomes(emptyFullChunk(), "minecraft:plains", "minecraft:the_void"))), "mixed biomes are kept");

        for (final String ticks : new String[] {"block_ticks", "fluid_ticks"}) {
            final CompoundTag scheduled = emptyFullChunk();
            final ListTag list = new ListTag();
            final CompoundTag tick = new CompoundTag();
            tick.putString("i", "minecraft:water");
            tick.putInt("x", 1);
            tick.putInt("y", 64);
            tick.putInt("z", 1);
            tick.putInt("t", 5);
            tick.putInt("p", 0);
            list.add(tick);
            scheduled.put(ticks, list);
            assertFalse(ChunkPruning.isEmpty("region", encode(scheduled)), ticks + " are kept");
        }

        final CompoundTag postProcessing = emptyFullChunk();
        final ListTag offsets = new ListTag();
        offsets.add(net.minecraft.nbt.ShortTag.valueOf((short) 17));
        postProcessing.getListOrEmpty("PostProcessing").set(2, offsets);
        assertFalse(ChunkPruning.isEmpty("region", encode(postProcessing)), "PostProcessing offsets are kept");

        final CompoundTag start = emptyFullChunk();
        final CompoundTag village = new CompoundTag();
        village.putString("id", "minecraft:village_plains");
        start.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").put("minecraft:village_plains", village);
        assertFalse(ChunkPruning.isEmpty("region", encode(start)), "a structure start is kept");
        final CompoundTag invalid = emptyFullChunk();
        final CompoundTag invalidStart = new CompoundTag();
        invalidStart.putString("id", "INVALID");
        invalid.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").put("minecraft:village_plains", invalidStart);
        assertTrue(ChunkPruning.isEmpty("region", encode(invalid)), "an INVALID start records no structure");

        final CompoundTag reference = emptyFullChunk();
        reference.getCompoundOrEmpty("structures").getCompoundOrEmpty("References")
            .putLongArray("minecraft:village_plains", new long[] {42L});
        assertFalse(ChunkPruning.isEmpty("region", encode(reference)), "a structure reference is kept");

        final CompoundTag unknown = emptyFullChunk();
        unknown.putString("SomeFuturePayload", "x");
        assertFalse(ChunkPruning.isEmpty("region", encode(unknown)), "a payload not understood is kept");
    }

    @Test
    void aVoidChunkWithOnlyPluginDataSurvivesUnloadAndReload() throws IOException {
        final Path folder = this.dir.resolve("world").resolve("region");
        Files.createDirectories(folder);
        final CompoundTag tag = emptyFullChunk();
        final CompoundTag values = new CompoundTag();
        values.putString("myplugin:claim", "owner");
        tag.put("ChunkBukkitValues", values);
        final byte[] withPdc = encode(tag);
        final AwfRegionStorage first = open(folder, true);
        first.write(3, 3, withPdc);
        first.write(4, 4, encode(emptyFullChunk()));
        assertEquals(1, first.pruned(), "only the chunk without plugin data is pruned");
        first.close();
        assertArrayEquals(withPdc, open(folder, true).read(3, 3));
    }

    @Test
    void emptinessFollowsFastChunkPruner() throws IOException {
        assertTrue(ChunkPruning.isEmpty("region", chunk("minecraft:full", false, "minecraft:air")));
        assertTrue(ChunkPruning.isEmpty("region", chunk("minecraft:full", false, "minecraft:air", "minecraft:cave_air")));
        assertFalse(ChunkPruning.isEmpty("region", chunk("minecraft:full", false, "minecraft:air", "minecraft:stone")));
        assertFalse(ChunkPruning.isEmpty("region", chunk("minecraft:full", true, "minecraft:air")), "a block entity is content");
        assertFalse(ChunkPruning.isEmpty("region", chunk("minecraft:features", false, "minecraft:air")),
            "a chunk still being generated is kept");
        assertFalse(ChunkPruning.isEmpty("region", new byte[] {1, 2, 3}), "unreadable bytes are never dropped");

        final CompoundTag noEntities = new CompoundTag();
        noEntities.put("Entities", new ListTag());
        assertTrue(ChunkPruning.isEmpty("entities", encode(noEntities)));
        final CompoundTag withEntity = new CompoundTag();
        final ListTag entities = new ListTag();
        entities.add(new CompoundTag());
        withEntity.put("Entities", entities);
        assertFalse(ChunkPruning.isEmpty("entities", encode(withEntity)));

        final CompoundTag poi = new CompoundTag();
        final CompoundTag poiSections = new CompoundTag();
        final CompoundTag emptySection = new CompoundTag();
        emptySection.put("Records", new ListTag());
        poiSections.put("0", emptySection);
        poi.put("Sections", poiSections);
        assertTrue(ChunkPruning.isEmpty("poi", encode(poi)));
    }

    private AwfRegionStorage open(final Path folder, final boolean prune) throws IOException {
        final AwfEngine engine = new AwfEngine(new AwfSettings(Set.of("world"), PersistenceMode.INCREMENTAL, 30, 1024, 2, 2),
            DIRECT, this.clock::get);
        final AwfRegionStorage storage = engine.open(folder);
        if (prune) storage.pruneEmpty("region");
        return storage;
    }

    @Test
    void emptyNewChunksAreNotStoredButAnEmptiedChunkIs() throws IOException {
        final Path folder = this.dir.resolve("world").resolve("region");
        Files.createDirectories(folder);
        final byte[] empty = chunk("minecraft:full", false, "minecraft:air");
        final byte[] island = chunk("minecraft:full", false, "minecraft:air", "minecraft:grass_block");

        final AwfRegionStorage first = open(folder, true);
        first.write(0, 0, empty);                  // never stored: pruned
        first.write(1, 0, island);                 // content: stored
        assertEquals(1, first.pruned());
        assertEquals(1, first.storedChunks(), "only the island chunk is held");
        first.close();

        final AwfRegionStorage second = open(folder, true);
        assertNull(second.read(0, 0), "the empty chunk was not stored; the void generator makes it again");
        assertArrayEquals(island, second.read(1, 0));
        second.write(1, 0, empty);                 // the island was dug away: must be stored
        assertEquals(0, second.pruned());
        assertEquals(1, second.storedChunks());
        second.close();

        assertArrayEquals(empty, open(folder, true).read(1, 0),
            "an emptied chunk is written, so the old blocks do not come back");
    }

    @Test
    void chunksOutsideTheSaveBoundsAreNotStored() throws IOException {
        final Path folder = this.dir.resolve("world").resolve("region");
        Files.createDirectories(folder);
        final byte[] island = chunk("minecraft:full", false, "minecraft:air", "minecraft:grass_block");
        final AwfRegionStorage storage = open(folder, false);
        storage.saveBounds(new dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds(-1, -1, 1, 1));
        storage.write(1, -1, island);
        storage.write(2, 0, island);
        storage.write(0, -5, island);
        assertEquals(2, storage.outOfBounds());
        storage.close();
        final AwfRegionStorage reopened = open(folder, false);
        assertArrayEquals(island, reopened.read(1, -1));
        assertNull(reopened.read(2, 0));
        assertNull(reopened.read(0, -5));
    }

    @Test
    void configureChangesStoragesThatAreAlreadyOpen() throws IOException {
        final Path folder = this.dir.resolve("isle").resolve("region");
        Files.createDirectories(folder);
        final AwfEngine engine = new AwfEngine(new AwfSettings(Set.of(), PersistenceMode.INCREMENTAL, 30, 1024, 2, 2),
            DIRECT, this.clock::get);
        engine.manageWorld("isle", null, false);
        final AwfRegionStorage storage = engine.open(folder);
        final byte[] empty = chunk("minecraft:full", false, "minecraft:air");
        storage.write(0, 0, empty);
        assertEquals(0, storage.pruned());
        engine.configureWorld("isle", true, new dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds(0, 0, 3, 3));
        storage.write(1, 1, empty);
        storage.write(9, 9, chunk("minecraft:full", false, "minecraft:stone"));
        assertEquals(1, storage.pruned());
        assertEquals(1, storage.outOfBounds());
    }

    @Test
    void worldsThatDoNotPruneStoreEmptyChunks() throws IOException {
        final Path folder = this.dir.resolve("world").resolve("region");
        Files.createDirectories(folder);
        final byte[] empty = chunk("minecraft:full", false, "minecraft:air");
        final AwfRegionStorage storage = open(folder, false);
        storage.write(0, 0, empty);
        assertEquals(0, storage.pruned());
        storage.close();
        assertArrayEquals(empty, open(folder, false).read(0, 0));
    }
}
