package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.awf.AwfBackend;
import dev.iyanz.sourbycraft.awf.AwfEngine;
import dev.iyanz.sourbycraft.awf.AwfStore;
import dev.iyanz.sourbycraft.awf.AwfWorldFile;
import dev.iyanz.sourbycraft.awf.ChunkKey;
import dev.iyanz.sourbycraft.awf.ChunkSource;
import dev.iyanz.sourbycraft.awf.Compression;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Whole worlds as {@code .awf} files ({@link AwfWorldFile}): export, import, and conversion from
 * Slime files. The metadata keys are the format's contract with other readers:
 * {@code format=awf-world}, {@code environment}, and when set {@code seed}, {@code generator},
 * {@code world-type}, {@code data-version}; plus informational {@code source}, {@code created},
 * {@code created-by}.
 */
public final class AuroraWorldFiles {

    public static final String FORMAT = "awf-world";

    /** What an export or a conversion wrote. */
    public record Written(Map<String, Integer> chunks, String codec) {
        public int total() {
            return this.chunks.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private AuroraWorldFiles() {}

    /** Metadata describing how to create the world again. */
    static Map<String, String> metadata(final AuroraWorldRegistry.Entry world, final String source) {
        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("format", FORMAT);
        meta.put("environment", world.environment());
        if (world.seed() != null) meta.put("seed", Long.toString(world.seed()));
        if (world.generator() != null) meta.put("generator", world.generator());
        if (world.worldType() != null) meta.put("world-type", world.worldType());
        meta.put("source", source);
        meta.put("created", Instant.now().toString());
        meta.put("created-by", "SourbyCraft Aurora World Fabric");
        return meta;
    }

    /**
     * Exports a world — its own chunks over its template's, as it reads — to one file. The world
     * must not be loaded, so the file is one consistent moment.
     */
    public static Written export(final AwfBackend backend, final Path worldFolder, final AuroraWorldRegistry.Entry world,
                                 final Path templatesRoot, final Path file) throws IOException {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        try (AwfWorldFile.Writer writer = AwfWorldFile.writer(file)) {
            for (final String folder : AwfEngine.STORAGE_FOLDERS) {
                final AwfStore from = AuroraWorldIo.openRead(backend, worldFolder, folder);
                final AwfStore under = world.template() == null ? null
                    : AuroraWorldIo.openTemplate(templatesRoot, world.template(), folder);
                try {
                    counts.put(folder, AuroraWorldIo.flatten(from, under, (key, bytes) -> writer.add(folder, key, bytes)));
                } finally {
                    if (from != null) from.close();
                }
            }
            writer.finish(metadata(world, "world " + world.name()));
            return new Written(counts, Compression.name(writer.codec()));
        }
    }

    /** The registry entry a file describes, under a new name. */
    static AuroraWorldRegistry.Entry describe(final AwfWorldFile file, final String name, final boolean autoload)
        throws IOException {
        final Map<String, String> meta = file.metadata();
        if (!FORMAT.equals(meta.get("format"))) throw new IOException("not an AWF world file (format " + meta.get("format") + ")");
        final String environment = meta.getOrDefault("environment", "normal").toLowerCase(Locale.ROOT);
        if (!environment.equals("normal") && !environment.equals("nether") && !environment.equals("the_end")) {
            throw new IOException("unknown environment " + environment);
        }
        Long seed = null;
        if (meta.containsKey("seed")) {
            try {
                seed = Long.parseLong(meta.get("seed"));
            } catch (final NumberFormatException bad) {
                throw new IOException("seed " + meta.get("seed") + " is not a number");
            }
        }
        return new AuroraWorldRegistry.Entry(name, environment, seed, meta.get("generator"), meta.get("world-type"),
            null, autoload);
    }

    /** Writes a file's chunks into a new world's stores on the configured backend. */
    static Map<String, Integer> importInto(final AwfWorldFile file, final Path worldFolder) throws IOException {
        final Map<String, ChunkSource> sources = new LinkedHashMap<>();
        for (final String folder : AwfEngine.STORAGE_FOLDERS) sources.put(folder, file.stream(folder));
        return AuroraWorldIo.writeWorld(worldFolder, sources);
    }

    /**
     * Converts a Slime file to an {@code .awf} file without creating a world.
     *
     * @param environment {@code normal}, {@code nether} or {@code the_end}: sets section heights
     */
    public static Written convertSlime(final byte[] slime, final String environment, final String source,
                                       final Path file) throws IOException {
        final SlimeImporter.Converted converted = SlimeImporter.convert(slime, environment.equals("normal") ? -4 : 0);
        final Map<String, Integer> counts = new LinkedHashMap<>();
        try (AwfWorldFile.Writer writer = AwfWorldFile.writer(file)) {
            for (final Map.Entry<String, Map<ChunkKey, byte[]>> stream : converted.byStorage().entrySet()) {
                for (final Map.Entry<ChunkKey, byte[]> chunk : new java.util.TreeMap<>(stream.getValue()).entrySet()) {
                    writer.add(stream.getKey(), chunk.getKey(), chunk.getValue());
                }
                counts.put(stream.getKey(), stream.getValue().size());
            }
            final Map<String, String> meta = metadata(new AuroraWorldRegistry.Entry(source, environment, null,
                "void", null, null, false), "slime " + source);
            meta.put("data-version", Integer.toString(converted.dataVersion()));
            writer.finish(meta);
            return new Written(counts, Compression.name(writer.codec()));
        }
    }
}
