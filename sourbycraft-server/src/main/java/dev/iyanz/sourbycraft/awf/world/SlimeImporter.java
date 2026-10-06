package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.awf.ChunkKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * Converts a Slime world file, as SourbyCraft's former Slime world manager wrote it, into AWF
 * stores. Versions 12 and 13 are read; both were checked against real island files.
 *
 * <p>Common layout: magic {@code B1 0B}, the version byte, the data version (int), then a
 * compressed chunk blob and a compressed extra-data blob, each preceded by its compressed and
 * uncompressed length. Each chunk is its x and z, its sections from the lowest up (light, block
 * states, biomes), heightmaps, then per-version fields, then block entities (under
 * {@code tileEntities}), entities and extra data. Every NBT value is preceded by its length, 0 for
 * none.</p>
 * <ul>
 *   <li><b>v13</b> ({@code 0x0D}): zlib (SourbyCraft's former writer) or zstd (AdvancedSlimePaper),
 *       told apart by the blob's magic. A flags byte after the data version (1 = POI, 2 = block
 *       ticks, 4 = fluid ticks saved, each then present per chunk after the heightmaps; unknown
 *       flags' values are skipped). A section starts with one flags byte (2 = sky light, 1 = block
 *       light follows). Entities are a bare list or, as ASP writes them, a compound holding
 *       {@code entities}.</li>
 *   <li><b>v12</b> ({@code 0x0C}): zstd, no flags byte, no POI or ticks. A section starts with two
 *       booleans (block light, sky light). Entities are a list under {@code entities} in a
 *       compound. zstd comes from the engine's runtime libraries.</li>
 * </ul>
 *
 * <p>Each chunk becomes the chunk NBT the engine itself writes, with status {@code full}, and goes
 * into the world's {@code region.awf}; its entities into {@code entities.awf} and its POI into
 * {@code poi.awf}. Light is <em>not</em> carried over: the former format recorded it against the
 * wrong section heights, so imported chunks are marked unlit and the engine lights them on first
 * load. The data version is kept, so the engine's data fixers upgrade chunks from older versions.
 * A chunk's {@code ChunkBukkitValues} is kept. The world's extra data is mapped by
 * {@link #worldData(CompoundTag)}: unambiguous properties become the new world's properties, and
 * everything else (other properties, the world's {@code BukkitValues}) is reported as dropped.</p>
 */
public final class SlimeImporter {

    static final int V12 = 0x0C;
    static final int V13 = 0x0D;
    private static final int FLAG_POI = 1;
    private static final int FLAG_BLOCK_TICKS = 2;
    private static final int FLAG_FLUID_TICKS = 4;
    /** Uncompressed blob sizes above this are refused rather than allocated. */
    private static final int MAX_BLOB = 1 << 30;

    /** One chunk as the file holds it. */
    record SlimeChunk(int x, int z, List<Section> sections, CompoundTag heightmaps, CompoundTag poi,
                      CompoundTag blockTicks, CompoundTag fluidTicks, CompoundTag tileEntities,
                      ListTag entities, CompoundTag extra) {}

    /** One section, lowest first; light is read and dropped. */
    record Section(CompoundTag blockStates, CompoundTag biomes) {}

    /**
     * A parsed file.
     *
     * @param worldExtra the world's extra data compound (AdvancedSlimePaper keeps its
     *     {@code properties} and the world's {@code BukkitValues} here); empty when the file has none
     */
    record SlimeFile(int dataVersion, List<SlimeChunk> chunks, CompoundTag worldExtra) {

        /** AdvancedSlimePaper's world properties ({@code spawnX}, {@code difficulty}, ...) as text. */
        Map<String, String> properties() {
            final Map<String, String> out = new java.util.LinkedHashMap<>();
            this.worldExtra.getCompound("properties").ifPresent(props -> {
                for (final String key : props.keySet()) {
                    final Tag value = props.get(key);
                    out.put(key, value instanceof net.minecraft.nbt.StringTag string ? string.value() : String.valueOf(value));
                }
            });
            return out;
        }
    }

    /**
     * What a file says about the world itself, beyond its chunks.
     *
     * @param properties the Slime properties that map unambiguously onto {@link
     *     dev.iyanz.sourbycraft.api.world.WorldProperties}; {@code NONE} when none do
     * @param dropped what the import does not keep, as {@code properties.<key>} and
     *     {@code extra.<key>} (such as {@code extra.BukkitValues}, the world's plugin data); empty when
     *     nothing is dropped
     */
    public record WorldData(dev.iyanz.sourbycraft.api.world.WorldProperties properties, List<String> dropped) {}

    /** What an import wrote. */
    public record Result(int chunks, int entityChunks, int poiChunks, int dataVersion) {}

    /** A file converted to the engine's chunk, entity and POI NBT, by storage folder. */
    public record Converted(Map<ChunkKey, byte[]> region, Map<ChunkKey, byte[]> entities, Map<ChunkKey, byte[]> poi,
                            int dataVersion, WorldData world) {

        public Result result() {
            return new Result(this.region.size(), this.entities.size(), this.poi.size(), this.dataVersion);
        }

        public Map<String, Map<ChunkKey, byte[]>> byStorage() {
            final Map<String, Map<ChunkKey, byte[]>> out = new java.util.LinkedHashMap<>();
            out.put("region", this.region);
            out.put("entities", this.entities);
            out.put("poi", this.poi);
            return out;
        }
    }

    private SlimeImporter() {}

    /**
     * Reads a file and writes its chunks into the stores under {@code dimensionFolder}.
     *
     * @param minSection the lowest section of the target dimension: -4 for the overworld, 0 for the
     *     nether and the end
     */
    public static Result importInto(final byte[] file, final Path dimensionFolder, final int minSection)
        throws IOException {
        final Converted converted = convert(file, minSection);
        final Map<String, dev.iyanz.sourbycraft.awf.ChunkSource> sources = new java.util.LinkedHashMap<>();
        converted.byStorage().forEach((folder, chunks) -> sources.put(folder, AuroraWorldIo.of(chunks)));
        AuroraWorldIo.writeWorld(dimensionFolder, sources);
        return converted.result();
    }

    /** Parses a file and converts every chunk, without writing anything. */
    public static Converted convert(final byte[] file, final int minSection) throws IOException {
        final SlimeFile parsed = read(file);
        final Map<ChunkKey, byte[]> region = new HashMap<>();
        final Map<ChunkKey, byte[]> entities = new HashMap<>();
        final Map<ChunkKey, byte[]> poi = new HashMap<>();
        for (final SlimeChunk chunk : parsed.chunks()) {
            final ChunkKey key = new ChunkKey(chunk.x(), chunk.z());
            region.put(key, encode(chunkTag(chunk, minSection, parsed.dataVersion())));
            final CompoundTag entityTag = entityTag(chunk, parsed.dataVersion());
            if (entityTag != null) entities.put(key, encode(entityTag));
            final CompoundTag poiTag = poiTag(chunk, parsed.dataVersion());
            if (poiTag != null) poi.put(key, encode(poiTag));
        }
        return new Converted(region, entities, poi, parsed.dataVersion(), worldData(parsed.worldExtra()));
    }

    static SlimeFile read(final byte[] file) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
        if (file.length < 3 || (in.readByte() & 0xFF) != 0xB1 || (in.readByte() & 0xFF) != 0x0B) {
            throw new IOException("not a Slime world file (bad magic)");
        }
        final int version = in.readByte() & 0xFF;
        if (version != V12 && version != V13) {
            throw new IOException("Slime format version " + version + " is not supported; versions 12 and 13 are");
        }
        final int dataVersion = in.readInt();
        final int flags = version == V13 ? in.readByte() & 0xFF : 0;
        // AdvancedSlimePaper writes zstd in both versions; SourbyCraft's former manager wrote v13 with
        // zlib. The blob says which by its first bytes.
        final byte[] chunkBlob = blob(in);
        final CompoundTag worldExtra = worldExtra(in);
        final DataInputStream chunks = new DataInputStream(new ByteArrayInputStream(chunkBlob));
        final int count = chunks.readInt();
        if (count < 0) throw new IOException("negative chunk count " + count);
        final List<SlimeChunk> out = new ArrayList<>(Math.min(count, 1 << 16));
        for (int i = 0; i < count; i++) {
            final int x = chunks.readInt();
            final int z = chunks.readInt();
            final int sectionCount = chunks.readInt();
            if (sectionCount < 0 || sectionCount > 4096) throw new IOException("chunk " + x + "," + z + " has " + sectionCount + " sections");
            final List<Section> sections = new ArrayList<>(sectionCount);
            for (int s = 0; s < sectionCount; s++) {
                if (version == V13) {
                    final int sectionFlags = chunks.readByte() & 0xFF;
                    if ((sectionFlags & 2) != 0) chunks.skipNBytes(2048);
                    if ((sectionFlags & 1) != 0) chunks.skipNBytes(2048);
                } else {
                    if (chunks.readBoolean()) chunks.skipNBytes(2048);    // block light
                    if (chunks.readBoolean()) chunks.skipNBytes(2048);    // sky light
                }
                sections.add(new Section(compound(chunks), compound(chunks)));
            }
            final CompoundTag heightmaps = compound(chunks);
            final CompoundTag poi = (flags & FLAG_POI) != 0 ? compound(chunks) : null;
            final CompoundTag blockTicks = (flags & FLAG_BLOCK_TICKS) != 0 ? compound(chunks) : null;
            final CompoundTag fluidTicks = (flags & FLAG_FLUID_TICKS) != 0 ? compound(chunks) : null;
            // Flags newer than this reader: each adds one length-prefixed value, skipped (as ASP does).
            for (int unknown = Integer.bitCount(flags & ~(FLAG_POI | FLAG_BLOCK_TICKS | FLAG_FLUID_TICKS)); unknown > 0; unknown--) {
                lengthPrefixed(chunks);
            }
            final CompoundTag tileEntities = compound(chunks);
            final ListTag entities = entities(chunks);
            final CompoundTag extra = compound(chunks);
            out.add(new SlimeChunk(x, z, sections, heightmaps, poi, blockTicks, fluidTicks, tileEntities, entities, extra));
        }
        return new SlimeFile(dataVersion, out, worldExtra);
    }

    /** The chunk NBT the engine writes for a full chunk, without light. */
    static CompoundTag chunkTag(final SlimeChunk chunk, final int minSection, final int dataVersion) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", dataVersion);
        tag.putInt("xPos", chunk.x());
        tag.putInt("yPos", minSection);
        tag.putInt("zPos", chunk.z());
        tag.putLong("LastUpdate", 0L);
        tag.putLong("InhabitedTime", 0L);
        tag.putString("Status", "minecraft:full");
        final ListTag sections = new ListTag();
        for (int i = 0; i < chunk.sections().size(); i++) {
            final Section section = chunk.sections().get(i);
            if (section.blockStates() == null && section.biomes() == null) continue;
            final CompoundTag sectionTag = new CompoundTag();
            sectionTag.putByte("Y", (byte) (minSection + i));
            if (section.blockStates() != null) sectionTag.put("block_states", section.blockStates());
            if (section.biomes() != null) sectionTag.put("biomes", section.biomes());
            sections.add(sectionTag);
        }
        tag.put("sections", sections);
        final ListTag blockEntities = new ListTag();
        if (chunk.tileEntities() != null) {
            chunk.tileEntities().getList("tileEntities").ifPresent(blockEntities::addAll);
        }
        tag.put("block_entities", blockEntities);
        tag.put("block_ticks", ticks(chunk.blockTicks(), "block_ticks"));
        tag.put("fluid_ticks", ticks(chunk.fluidTicks(), "fluid_ticks"));
        tag.put("PostProcessing", new ListTag());
        tag.put("Heightmaps", chunk.heightmaps() == null ? new CompoundTag() : chunk.heightmaps());
        final CompoundTag structures = new CompoundTag();
        structures.put("starts", new CompoundTag());
        structures.put("References", new CompoundTag());
        tag.put("structures", structures);
        if (chunk.extra() != null) {
            chunk.extra().getCompound("ChunkBukkitValues").ifPresent(values -> tag.put("ChunkBukkitValues", values));
        }
        return tag;
    }

    /** The entity-chunk NBT, or {@code null} when the chunk has no entities. */
    static CompoundTag entityTag(final SlimeChunk chunk, final int dataVersion) {
        if (chunk.entities() == null || chunk.entities().isEmpty()) return null;
        final CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", dataVersion);
        tag.put("Position", new IntArrayTag(new int[] {chunk.x(), chunk.z()}));
        tag.put("Entities", chunk.entities());
        return tag;
    }

    /** The POI-chunk NBT, or {@code null} when the file kept no POI for the chunk. */
    static CompoundTag poiTag(final SlimeChunk chunk, final int dataVersion) {
        if (chunk.poi() == null || chunk.poi().isEmpty()) return null;
        final CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", dataVersion);
        tag.put("Sections", chunk.poi());
        return tag;
    }

    /**
     * A chunk's entities: AdvancedSlimePaper writes a compound holding an {@code entities} list,
     * SourbyCraft's former v13 writer a bare list. The tag type byte tells them apart.
     */
    private static ListTag entities(final DataInputStream in) throws IOException {
        final byte[] bytes = lengthPrefixed(in);
        if (bytes == null) return null;
        final Tag tag = NbtIo.readUnnamedTag(new DataInputStream(new ByteArrayInputStream(bytes)), NbtAccounter.unlimitedHeap());
        if (tag instanceof ListTag list) return list;
        if (tag instanceof CompoundTag compound) return compound.getList("entities").orElse(null);
        return null;
    }

    /** A compressed blob, zstd or zlib by its magic bytes. */
    private static byte[] blob(final DataInputStream in) throws IOException {
        return blob(in, in.readInt());
    }

    private static byte[] blob(final DataInputStream in, final int compressedLength) throws IOException {
        final int length = in.readInt();
        if (compressedLength < 0 || length < 0 || length > MAX_BLOB) {
            throw new IOException("blob lengths " + compressedLength + "/" + length + " are not valid");
        }
        final byte[] compressed = new byte[compressedLength];
        in.readFully(compressed);
        if (length == 0) return new byte[0];
        final boolean zstd = compressed.length >= 4 && (compressed[0] & 0xFF) == 0x28 && (compressed[1] & 0xFF) == 0xB5
            && (compressed[2] & 0xFF) == 0x2F && (compressed[3] & 0xFF) == 0xFD;
        if (zstd) {
            if (!dev.iyanz.sourbycraft.awf.Compression.zstdAvailable()) {
                throw new IOException("zstd is not available on this server, so this Slime file cannot be read");
            }
            return dev.iyanz.sourbycraft.awf.Compression.unzstd(compressed, length);
        }
        return inflate(compressed, length);
    }

    /** The world's extra data compound; empty when the file ends before it (absent extra data is fine). */
    private static CompoundTag worldExtra(final DataInputStream in) throws IOException {
        final int compressedLength;
        try {
            compressedLength = in.readInt();
        } catch (final java.io.EOFException absent) {
            return new CompoundTag();
        }
        final byte[] extra = blob(in, compressedLength);
        if (extra.length == 0) return new CompoundTag();
        if (extra.length >= 2 && (extra[0] & 0xFF) == 0x1F && (extra[1] & 0xFF) == 0x8B) {
            return NbtIo.readCompressed(new ByteArrayInputStream(extra), NbtAccounter.unlimitedHeap());
        }
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(extra)));
    }

    /**
     * Reads only what a file says about the world: its header, then its extra data, skipping the
     * chunk blob without decompressing it. For deciding a new world's properties before its chunks
     * are imported.
     */
    public static WorldData worldData(final Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file)))) {
            if ((in.readByte() & 0xFF) != 0xB1 || (in.readByte() & 0xFF) != 0x0B) {
                throw new IOException("not a Slime world file (bad magic)");
            }
            final int version = in.readByte() & 0xFF;
            if (version != V12 && version != V13) {
                throw new IOException("Slime format version " + version + " is not supported; versions 12 and 13 are");
            }
            in.readInt();                                  // data version
            if (version == V13) in.readByte();             // flags
            final int compressedLength = in.readInt();
            final int length = in.readInt();
            if (compressedLength < 0 || length < 0 || length > MAX_BLOB) {
                throw new IOException("blob lengths " + compressedLength + "/" + length + " are not valid");
            }
            in.skipNBytes(compressedLength);
            return worldData(worldExtra(in));
        } catch (final java.io.EOFException truncated) {
            throw new IOException("Slime file " + file + " is truncated", truncated);
        }
    }

    /** Slime property keys {@link #worldData(CompoundTag)} maps, when their values are unambiguous. */
    private static final java.util.Set<String> MAPPED = java.util.Set.of("spawnX", "spawnY", "spawnZ", "spawnYaw",
        "difficulty", "pvp", "allowMonsters", "allowAnimals", "defaultBiome");

    /**
     * Maps AdvancedSlimePaper's properties onto {@link dev.iyanz.sourbycraft.api.world.WorldProperties}
     * where the meaning is unambiguous, reading typed NBT values (a boolean is a byte, yaw a float):
     * spawn needs {@code spawnX}, {@code spawnY} and {@code spawnZ} (yaw defaults to 0);
     * {@code difficulty} must name a difficulty; {@code pvp}, {@code allowMonsters} and
     * {@code allowAnimals} must be numeric (0 false) or the text {@code true}/{@code false};
     * {@code defaultBiome} must be a namespaced key. Everything else, including a mapped key with a
     * value that does not fit, and every other extra-data compound ({@code BukkitValues}, the
     * world's plugin data) is listed as dropped.
     */
    static WorldData worldData(final CompoundTag extra) {
        final List<String> dropped = new ArrayList<>();
        final CompoundTag props = extra.getCompoundOrEmpty("properties");
        for (final String key : new java.util.TreeSet<>(extra.keySet())) {
            if (!key.equals("properties")) dropped.add("extra." + key);
        }
        for (final String key : new java.util.TreeSet<>(props.keySet())) {
            if (!MAPPED.contains(key)) dropped.add("properties." + key);
        }
        dev.iyanz.sourbycraft.api.world.WorldProperties out = dev.iyanz.sourbycraft.api.world.WorldProperties.NONE;
        final Double x = number(props, "spawnX");
        final Double y = number(props, "spawnY");
        final Double z = number(props, "spawnZ");
        final Double yaw = props.contains("spawnYaw") ? number(props, "spawnYaw") : Double.valueOf(0);
        if (x != null && y != null && z != null && yaw != null) {
            out = out.withSpawn(new dev.iyanz.sourbycraft.api.world.WorldProperties.Spawn(x, y, z, yaw.floatValue()));
        } else {
            for (final String key : List.of("spawnX", "spawnY", "spawnZ", "spawnYaw")) {
                if (props.contains(key)) dropped.add("properties." + key);
            }
        }
        if (props.contains("difficulty")) {
            org.bukkit.Difficulty difficulty = null;
            if (props.get("difficulty") instanceof net.minecraft.nbt.StringTag text) {
                for (final org.bukkit.Difficulty candidate : org.bukkit.Difficulty.values()) {
                    if (candidate.name().equalsIgnoreCase(text.value().trim())) difficulty = candidate;
                }
            }
            if (difficulty != null) out = out.withDifficulty(difficulty);
            else dropped.add("properties.difficulty");
        }
        final Boolean pvp = flag(props, "pvp", dropped);
        if (pvp != null) out = out.withPvp(pvp);
        final Boolean monsters = flag(props, "allowMonsters", dropped);
        final Boolean animals = flag(props, "allowAnimals", dropped);
        if (monsters != null || animals != null) out = out.withSpawning(monsters, animals);
        if (props.contains("defaultBiome")) {
            final String biome = props.get("defaultBiome") instanceof net.minecraft.nbt.StringTag text
                ? text.value().trim().toLowerCase(java.util.Locale.ROOT) : null;
            if (biome != null && biome.indexOf(':') > 0 && org.bukkit.NamespacedKey.fromString(biome) != null) {
                out = out.withDefaultBiome(biome);
            } else {
                dropped.add("properties.defaultBiome");
            }
        }
        java.util.Collections.sort(dropped);
        return new WorldData(out, List.copyOf(dropped));
    }

    private static Double number(final CompoundTag props, final String key) {
        return props.get(key) instanceof net.minecraft.nbt.NumericTag numeric ? numeric.doubleValue() : null;
    }

    /** A boolean property: numeric (0 is false) or the text true/false; else dropped. */
    private static Boolean flag(final CompoundTag props, final String key, final List<String> dropped) {
        if (!props.contains(key)) return null;
        final Tag value = props.get(key);
        if (value instanceof net.minecraft.nbt.NumericTag numeric) return numeric.doubleValue() != 0;
        if (value instanceof net.minecraft.nbt.StringTag text) {
            if (text.value().equalsIgnoreCase("true")) return true;
            if (text.value().equalsIgnoreCase("false")) return false;
        }
        dropped.add("properties." + key);
        return null;
    }

    private static ListTag ticks(final CompoundTag wrapper, final String key) {
        if (wrapper == null) return new ListTag();
        return wrapper.getList(key).orElseGet(ListTag::new);
    }

    static byte[] encode(final CompoundTag tag) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        return bytes.toByteArray();
    }

    private static CompoundTag compound(final DataInputStream in) throws IOException {
        final byte[] bytes = lengthPrefixed(in);
        return bytes == null ? null : NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static byte[] lengthPrefixed(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length <= 0) return null;
        if (length > MAX_BLOB) throw new IOException("NBT value of " + length + " bytes");
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static byte[] inflate(final byte[] compressed, final int length) throws IOException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            final byte[] out = new byte[length];
            int position = 0;
            while (position < length && !inflater.finished()) {
                final int n = inflater.inflate(out, position, length - position);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                position += n;
            }
            if (position != length) throw new IOException("blob inflated to " + position + " of " + length + " bytes");
            return out;
        } catch (final DataFormatException corrupt) {
            throw new IOException("corrupt compressed blob", corrupt);
        } finally {
            inflater.end();
        }
    }
}
