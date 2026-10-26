package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.awf.AwfBackend;
import dev.iyanz.sourbycraft.awf.AwfEngine;
import dev.iyanz.sourbycraft.awf.AwfStore;
import dev.iyanz.sourbycraft.awf.AwfWorldFile;
import dev.iyanz.sourbycraft.awf.ChunkKey;
import dev.iyanz.sourbycraft.awf.ChunkSource;
import dev.iyanz.sourbycraft.awf.Compression;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Whole worlds as {@code .awf} files ({@link AwfWorldFile}): export, import, and conversion from
 * Slime files. The metadata keys are the format's contract with other readers:
 * {@code format=awf-world}, {@code environment}, and when set {@code seed}, {@code generator},
 * {@code world-type}, {@code data-version}, {@code properties}; plus informational {@code source},
 * {@code created}, {@code created-by}.
 *
 * <p>{@code properties} is the registry's {@link dev.iyanz.sourbycraft.api.world.WorldProperties}
 * as JSON, written when the world has any. A file without it (every file written before it was
 * added) imports with no properties, exactly as before. A spawn in it that is outside the world or
 * not finite (an older file) is dropped with a WARN and the rest imports ({@link #properties}).</p>
 *
 * <p>{@code plugin-data} ({@link #PLUGIN_DATA}) is the world's persistent data container as base64
 * of the gzip-compressed NBT compound. Only {@link #convertSlime} writes it, from the Slime world's
 * {@code BukkitValues}, and only when it fits in one metadata value; {@link #export} does not (the
 * world's own container is not read). {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds#importWorld}
 * (and {@code /awf import}) merges it into the new world's persistent data container once the world
 * is created ({@code AuroraWorldsService.importWorldInternal}, with
 * {@link SlimeImporter#restorePluginData}). It is optional: a file without it has no plugin data, and
 * a build that does not know the key ignores it, as every reader ignores keys it does not use.</p>
 *
 * <p>The {@code /awf} command reads files from {@link #IMPORTS} and writes them to
 * {@link #EXPORTS} only ({@link #confine}); the {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds}
 * API takes any path, as a plugin has the file system anyway.</p>
 */
public final class AuroraWorldFiles {

    public static final String FORMAT = "awf-world";
    /** Metadata key of the world's properties, as JSON. */
    static final String PROPERTIES = "properties";
    /** Metadata key of the world's plugin data: base64 of the gzip-compressed NBT compound. */
    static final String PLUGIN_DATA = "plugin-data";
    /** The longest metadata value a file holds ({@code writeUTF}); base64 is one byte per character. */
    static final int MAX_METADATA_VALUE = 65_535;
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    /** What an export or a conversion wrote. */
    public record Written(Map<String, Integer> chunks, String codec) {
        public int total() {
            return this.chunks.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private AuroraWorldFiles() {}

    /**
     * Where {@code /awf export} and {@code /awf convert} write, under the server directory. An
     * operator's file name is confined to this folder: before, {@code /awf export world
     * /etc/cron.d/x} wrote wherever the server process could.
     */
    public static final Path EXPORTS = Path.of("awf", "exports");

    /** Where {@code /awf import} and {@code /awf convert} read, under the server directory. */
    public static final Path IMPORTS = Path.of("awf", "imports");

    /**
     * Resolves a file name an operator typed inside {@code folder} (relative to the server
     * directory, or wherever {@code folder} points when it is absolute). Accepted: a relative
     * name, in a sub-folder or not, optionally already prefixed with the folder itself (as the
     * command prints it). Refused: an empty name, an absolute path, any {@code ..} segment, and a
     * name whose existing part is a symbolic link that leaves the folder.
     *
     * @param folder {@link #EXPORTS} or {@link #IMPORTS}
     * @param typed the operator's argument, suffix already applied
     * @return the path inside {@code folder}, normalized
     * @throws IllegalArgumentException with the reason an operator can act on
     */
    public static Path confine(final Path folder, final String typed) {
        final String shown = folder.toString().replace(folder.getFileSystem().getSeparator(), "/");
        if (typed == null || typed.isBlank()) {
            throw new IllegalArgumentException("file names are relative to " + shown + "/: give a name");
        }
        final Path given;
        try {
            given = Path.of(typed.strip());
        } catch (final java.nio.file.InvalidPathException invalid) {
            throw new IllegalArgumentException("file names are relative to " + shown + "/: " + invalid.getReason());
        }
        if (given.isAbsolute() || typed.startsWith("/") || typed.startsWith("\\")) {
            throw new IllegalArgumentException("file names are relative to " + shown + "/ (an absolute path is"
                + " not accepted): " + typed);
        }
        for (final Path segment : given) {
            if (segment.toString().equals("..")) {
                throw new IllegalArgumentException("file names are relative to " + shown + "/ and may not contain"
                    + " '..': " + typed);
            }
        }
        // "awf/exports/x.awf" typed back from the command's own output is the same file.
        final Path relative = given.normalize().startsWith(folder) ? folder.relativize(given.normalize()) : given.normalize();
        final Path resolved = folder.resolve(relative).normalize();
        if (relative.toString().isEmpty() || !resolved.startsWith(folder)) {
            throw new IllegalArgumentException("file names are relative to " + shown + "/: " + typed);
        }
        // A symbolic link inside the folder could still point outside it. Follow the deepest part
        // that exists; a name whose parents do not exist yet cannot be a link.
        try {
            Path existing = resolved;
            while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing != null && existing.startsWith(folder) && !existing.equals(folder)
                && Files.exists(folder) && !existing.toRealPath().startsWith(folder.toRealPath())) {
                throw new IllegalArgumentException(typed + " leaves " + shown + "/ through a symbolic link");
            }
        } catch (final IOException unreadable) {
            throw new IllegalArgumentException("cannot resolve " + typed + " under " + shown + "/: "
                + unreadable.getMessage());
        }
        return resolved;
    }

    /** Metadata describing how to create the world again. */
    static Map<String, String> metadata(final AuroraWorldRegistry.Entry world, final String source) {
        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("format", FORMAT);
        meta.put("environment", world.environment());
        if (world.seed() != null) meta.put("seed", Long.toString(world.seed()));
        if (world.generator() != null) meta.put("generator", world.generator());
        if (world.worldType() != null) meta.put("world-type", world.worldType());
        if (world.properties() != null && !world.properties().equals(dev.iyanz.sourbycraft.api.world.WorldProperties.NONE)) {
            meta.put(PROPERTIES, GSON.toJson(world.properties()));
        }
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

    /**
     * The registry entry a file describes, under a new name. Without the file's path, the WARN of a
     * dropped spawn ({@link #properties}) names the world being imported and the file's recorded
     * {@code source}; {@link #describe(AwfWorldFile, Path, String, boolean)} names the path.
     */
    static AuroraWorldRegistry.Entry describe(final AwfWorldFile file, final String name, final boolean autoload)
        throws IOException {
        return describe(file, null, name, autoload);
    }

    /**
     * The registry entry a file describes, under a new name.
     *
     * @param path where {@code file} was opened from, named in the WARN of a dropped spawn
     *     ({@link #properties}); {@code null} when not known
     */
    static AuroraWorldRegistry.Entry describe(final AwfWorldFile file, final Path path, final String name,
                                              final boolean autoload) throws IOException {
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
        final String shown = path != null ? path.toString()
            : "the .awf file imported as '" + name + "' (recorded source: " + meta.getOrDefault("source", "none") + ")";
        return new AuroraWorldRegistry.Entry(name, environment, seed, meta.get("generator"), meta.get("world-type"),
            null, autoload, properties(meta.get(PROPERTIES), shown, name));
    }

    /**
     * The properties a file records, or {@code null} when it records none (an older file). A stored
     * spawn that {@link dev.iyanz.sourbycraft.api.world.WorldProperties.Spawn} refuses (outside the
     * world, or not finite) is dropped with one WARN naming {@code file}, and the rest of the file
     * imports: spawns were not range-checked before (builds up to and including v26.2-r50 exported
     * them unchecked), so refusing the whole import would strand such a file. The same helper drops
     * such a spawn from the registry and from templates ({@link AuroraWorldRegistry#dropInvalidSpawn});
     * a spawn that is not numbers is still refused.
     *
     * @param file the file as the WARN names it
     * @param world the name the world is imported under, for the WARN's {@code /awf set} hint
     * @throws IOException when the value is not JSON properties, or holds other properties that are
     *     refused (save bounds with min above max, a default biome that is not a key)
     */
    static dev.iyanz.sourbycraft.api.world.WorldProperties properties(final String json, final String file,
                                                                      final String world) throws IOException {
        if (json == null) return null;
        try {
            final com.google.gson.JsonElement stored = GSON.fromJson(json, com.google.gson.JsonElement.class);
            if (stored == null || stored.isJsonNull()) throw new IOException("the file's properties are empty");
            // dropInvalidSpawn reads a registry entry's shape, {"properties": {...}}. The holder shares the tree,
            // so a spawn it drops is gone from stored as well.
            final com.google.gson.JsonObject holder = new com.google.gson.JsonObject();
            holder.add("properties", stored);
            final java.util.Optional<String> droppedSpawn = AuroraWorldRegistry.dropInvalidSpawn(holder);
            final dev.iyanz.sourbycraft.api.world.WorldProperties parsed =
                GSON.fromJson(stored, dev.iyanz.sourbycraft.api.world.WorldProperties.class);
            if (parsed == null) throw new IOException("the file's properties are empty");
            // Validated as the registry would use them: bounds and the void biome key.
            if (parsed.saveBounds() != null) {
                final var b = parsed.saveBounds();
                new dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds(b.minChunkX(), b.minChunkZ(), b.maxChunkX(),
                    b.maxChunkZ());
            }
            if (parsed.defaultBiome() != null && org.bukkit.NamespacedKey.fromString(parsed.defaultBiome()) == null) {
                throw new IOException("default biome " + parsed.defaultBiome() + " is not a key");
            }
            droppedSpawn.ifPresent(reason -> dev.iyanz.sourbycraft.util.SourbyLogger.warn("Aurora World Fabric:"
                + " ignoring the stored spawn in " + file + " (" + reason + "), as a file written before spawns were"
                + " range-checked can hold one; '" + world + "' is imported without a spawn property (set one with"
                + " /awf set " + world + " spawn <x> <y> <z> [yaw])"));
            return parsed;
        } catch (final RuntimeException malformed) {          // JsonParseException, a refused record constructor
            throw new IOException("the file's properties are not valid: " + malformed.getMessage(), malformed);
        }
    }

    /**
     * The {@link #PLUGIN_DATA} value for a world's plugin data, or {@code null} when it does not fit
     * in one metadata value ({@value #MAX_METADATA_VALUE} base64 characters, about 48 KiB compressed).
     */
    static String encodePluginData(final net.minecraft.nbt.CompoundTag data) throws IOException {
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(bytes))) {
            net.minecraft.nbt.NbtIo.write(data, out);
        }
        final String encoded = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        return encoded.length() > MAX_METADATA_VALUE ? null : encoded;
    }

    /**
     * The plugin data a file records, or {@code null} when it records none (every file written
     * before the key existed, and every {@link #export}). Decoded within the Slime import's size
     * limit ({@link SlimeImporter#MAX_BYTES_PROPERTY}). To be merged into the world's container with
     * {@link SlimeImporter#restorePluginData} where the container may be written.
     *
     * @throws IOException when the value is not base64 of gzip-compressed NBT
     */
    static net.minecraft.nbt.CompoundTag pluginData(final Map<String, String> meta) throws IOException {
        final String encoded = meta.get(PLUGIN_DATA);
        if (encoded == null) return null;
        try (java.io.InputStream in = new java.util.zip.GZIPInputStream(
            new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(encoded)))) {
            return SlimeImporter.decode(in);
        } catch (final RuntimeException malformed) {          // not base64, an NBT reader's own exception
            throw new IOException("the file's plugin data is not valid: " + malformed.getMessage(), malformed);
        }
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
            final dev.iyanz.sourbycraft.api.world.WorldProperties properties = converted.world().properties();
            final Map<String, String> meta = metadata(new AuroraWorldRegistry.Entry(source, environment, null,
                "void", null, null, false, properties), "slime " + source);
            // The world's plugin data (BukkitValues) is kept under plugin-data when it fits in one value.
            final net.minecraft.nbt.CompoundTag pluginData = converted.world().pluginData();
            final String encodedPluginData = pluginData == null ? null : encodePluginData(pluginData);
            if (encodedPluginData != null) meta.put(PLUGIN_DATA, encodedPluginData);
            final java.util.List<String> dropped = converted.world().dropped(encodedPluginData != null);
            if (!dropped.isEmpty()) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("Aurora World Fabric: converting " + source
                    + " did not keep " + dropped + " (not representable in AWF yet, or a value that does not fit,"
                    + " such as a spawn outside the world"
                    + (pluginData != null && encodedPluginData == null ? "; the world's plugin data is larger than one"
                        + " .awf metadata value holds" : "") + ")");
            }
            meta.put("data-version", Integer.toString(converted.dataVersion()));
            writer.finish(meta);
            return new Written(counts, Compression.name(writer.codec()));
        }
    }
}
