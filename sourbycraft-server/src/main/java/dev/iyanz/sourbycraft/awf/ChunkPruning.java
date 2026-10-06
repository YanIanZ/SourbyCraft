package dev.iyanz.sourbycraft.awf;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * Which chunks hold nothing worth storing, after AdvancedSlimePaper's {@code FastChunkPruner}
 * ({@code pruning: aggressive}): a full chunk whose sections are all air, with no block entities
 * and no entities; an entity chunk with no entities; a POI chunk with no records.
 *
 * <p>Pruning is only lossless where an empty chunk is what the world generates again, so
 * {@link AwfRegionStorage} applies it to void worlds, and only to chunks neither its store nor its
 * base holds — a chunk that once had blocks is written as usual when it is emptied.</p>
 *
 * <p><b>Non-regenerable data is content.</b> Unlike {@code FastChunkPruner}, a full chunk is kept
 * when it carries anything the generator would not make again: a non-empty Paper persistent data
 * container ({@code ChunkBukkitValues}), scheduled block or fluid ticks, {@code PostProcessing}
 * offsets, a structure start (other than {@code INVALID}) or reference, a non-empty
 * {@code UpgradeData}, or a top-level key this class does not know. Biomes: the world's biome
 * source is not known here, so a section's biome palette is content unless it is a single entry
 * of {@code minecraft:the_void} or {@code minecraft:plains}. A void world without a single
 * {@code defaultBiome} of one of those therefore keeps its chunks rather than risk losing biome
 * edits.</p>
 */
public final class ChunkPruning {

    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    /** Biomes a pruned chunk may hold: the void generator's usual single biomes. */
    private static final Set<String> PRUNABLE_BIOMES = Set.of("minecraft:the_void", "minecraft:plains");
    /**
     * Top-level keys of a full chunk as {@code SerializableChunkData#write} produces them; the ones
     * that can carry data are checked below. Any other key is a payload this class does not
     * understand, and the chunk is kept.
     */
    private static final Set<String> KNOWN_CHUNK_KEYS = Set.of("DataVersion", "xPos", "yPos", "zPos", "LastUpdate",
        "InhabitedTime", "Status", "blending_data", "below_zero_retrogen", "UpgradeData", "sections", "isLightOn",
        "starlight.light_version", "block_entities", "entities", "block_ticks", "fluid_ticks", "PostProcessing",
        "Heightmaps", "structures", "ChunkBukkitValues");

    private ChunkPruning() {}

    /**
     * @param storageFolder {@code region}, {@code entities} or {@code poi}
     * @param nbt the chunk as the engine encodes it for its storage
     */
    public static boolean isEmpty(final String storageFolder, final byte[] nbt) {
        // Not an NBT compound at all: refuse without parsing. NbtIo reports parse failures through a
        // crash report, which is not a path to take for every odd write.
        if (nbt == null || nbt.length < 3 || nbt[0] != 10) return false;
        final CompoundTag tag;
        try {
            tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbt)));
        } catch (final IOException | RuntimeException unreadable) {
            return false;                 // never drop what cannot be understood
        }
        return switch (storageFolder) {
            case "region" -> emptyChunk(tag);
            case "entities" -> tag.getListOrEmpty("Entities").isEmpty();
            case "poi" -> emptyPoi(tag);
            default -> false;
        };
    }

    private static boolean emptyChunk(final CompoundTag tag) {
        // A chunk still being generated is kept: its later stages need what it holds now.
        if (!"minecraft:full".equals(tag.getStringOr("Status", ""))) return false;
        if (!tag.getListOrEmpty("block_entities").isEmpty()) return false;
        if (!tag.getListOrEmpty("entities").isEmpty()) return false;
        for (final String key : tag.keySet()) {
            if (!KNOWN_CHUNK_KEYS.contains(key)) return false;           // not understood: never dropped
        }
        // Plugin data on the chunk itself; Paper writes the compound only when it is non-empty.
        if (!tag.getCompoundOrEmpty("ChunkBukkitValues").isEmpty()) return false;
        if (!tag.getListOrEmpty("block_ticks").isEmpty() || !tag.getListOrEmpty("fluid_ticks").isEmpty()) return false;
        if (!tag.getCompoundOrEmpty("UpgradeData").isEmpty()) return false;
        final ListTag postProcessing = tag.getListOrEmpty("PostProcessing");
        for (int i = 0; i < postProcessing.size(); i++) {
            if (!(postProcessing.get(i) instanceof ListTag offsets) || !offsets.isEmpty()) return false;
        }
        if (!emptyStructures(tag.getCompoundOrEmpty("structures"))) return false;
        final ListTag sections = tag.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            final CompoundTag section = sections.getCompound(i).orElse(null);
            if (section == null) continue;
            final CompoundTag biomes = section.getCompound("biomes").orElse(null);
            if (biomes != null) {
                final ListTag biomePalette = biomes.getListOrEmpty("palette");
                if (biomePalette.size() > 1) return false;
                if (biomePalette.size() == 1
                    && !PRUNABLE_BIOMES.contains(biomePalette.getString(0).orElse(""))) return false;
            }
            final CompoundTag states = section.getCompound("block_states").orElse(null);
            if (states == null) continue;                 // light only
            final ListTag palette = states.getListOrEmpty("palette");
            for (int p = 0; p < palette.size(); p++) {
                final CompoundTag entry = palette.getCompound(p).orElse(null);
                if (entry == null || !AIR.contains(entry.getStringOr("Name", ""))) return false;
            }
        }
        return true;
    }

    /** No structure start other than {@code INVALID}, and no structure reference. */
    private static boolean emptyStructures(final CompoundTag structures) {
        final CompoundTag starts = structures.getCompoundOrEmpty("starts");
        for (final String key : starts.keySet()) {
            if (!(starts.get(key) instanceof CompoundTag start) || !"INVALID".equals(start.getStringOr("id", ""))) {
                return false;
            }
        }
        final CompoundTag references = structures.getCompoundOrEmpty("References");
        for (final String key : references.keySet()) {
            if (!(references.get(key) instanceof net.minecraft.nbt.LongArrayTag refs) || refs.getAsLongArray().length > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean emptyPoi(final CompoundTag tag) {
        final CompoundTag sections = tag.getCompound("Sections").orElse(null);
        if (sections == null) return true;
        for (final String key : sections.keySet()) {
            final Tag section = sections.get(key);
            if (section instanceof CompoundTag compound && !compound.getListOrEmpty("Records").isEmpty()) return false;
        }
        return true;
    }
}
