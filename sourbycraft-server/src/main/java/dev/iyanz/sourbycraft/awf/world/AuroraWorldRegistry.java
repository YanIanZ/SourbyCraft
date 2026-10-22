package dev.iyanz.sourbycraft.awf.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The worlds managed through {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds}, persisted in
 * {@code sourbycraft_config/aurora-worlds.json}.
 *
 * <p>The file is what makes a world created at runtime an AWF world on the next boot before its
 * store exists on disk, and what decides which worlds load at startup. It is rewritten through a
 * temporary file and an atomic move, so a crash mid-write leaves the previous version.</p>
 */
public final class AuroraWorldRegistry {

    /** Lower-case names only: they become path elements and dimension keys. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,48}");
    /**
     * Path elements every world's storage folder contains or that a dimension path is built from;
     * a world with one of these names would claim other worlds' folders through AWF's path match.
     */
    private static final Set<String> RESERVED = Set.of("region", "entities", "poi", "data", "dimensions",
        "minecraft", "players", "datapacks", "playerdata", "advancements", "stats", "awf");

    /**
     * One managed world, or a template's description of the worlds made from it.
     *
     * @param environment {@code normal}, {@code nether} or {@code the_end}
     * @param seed the seed, or {@code null} for a random one at creation
     * @param generator {@code void}, a plugin generator as {@code Plugin[:id]}, or {@code null}
     *     for the environment's own terrain
     * @param worldType {@code normal}, {@code flat}, {@code amplified} or {@code large_biomes}; or
     *     {@code null} for normal
     * @param template the template this world is a copy-on-write instance of, or {@code null}
     */
    public record Entry(String name, String environment, Long seed, String generator, String worldType,
                        String template, boolean autoload,
                        dev.iyanz.sourbycraft.api.world.WorldProperties properties) {

        /** An entry without properties. */
        public Entry(final String name, final String environment, final Long seed, final String generator,
                     final String worldType, final String template, final boolean autoload) {
            this(name, environment, seed, generator, worldType, template, autoload, null);
        }

        /** Never null: an entry written before properties existed reads as {@code NONE}. */
        public dev.iyanz.sourbycraft.api.world.WorldProperties propertiesOrNone() {
            return this.properties == null ? dev.iyanz.sourbycraft.api.world.WorldProperties.NONE : this.properties;
        }

        public Entry withAutoload(final boolean value) {
            return new Entry(this.name, this.environment, this.seed, this.generator, this.worldType, this.template, value,
                this.properties);
        }

        public Entry withProperties(final dev.iyanz.sourbycraft.api.world.WorldProperties value) {
            return new Entry(this.name, this.environment, this.seed, this.generator, this.worldType, this.template,
                this.autoload, value);
        }

        /** A new world described by this one (a template's description), keeping its properties. */
        public Entry named(final String other, final String fromTemplate, final boolean value) {
            return new Entry(other, this.environment, this.seed, this.generator, this.worldType, fromTemplate, value,
                this.properties);
        }

        /** Whether empty never-stored chunks are pruned: the property, else on for void worlds. */
        public boolean prunesEmptyChunks() {
            final Boolean configured = propertiesOrNone().pruneEmptyChunks();
            return configured != null ? configured : VoidGenerator.NAME.equals(this.generator);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public AuroraWorldRegistry(final Path file) throws IOException {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try {
                final List<Entry> stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<List<Entry>>() { }.getType());
                if (stored != null) {
                    for (final Entry entry : stored) {
                        this.entries.put(entry.name(), entry);
                    }
                }
            } catch (final JsonParseException malformed) {
                throw new IOException(file + " is not a valid world list; fix or remove it", malformed);
            }
        }
    }

    /** Why a name cannot be used, or empty when it can. */
    public static Optional<String> invalidName(final String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.of("world names are 1-48 characters of a-z, 0-9, _ and -");
        }
        if (RESERVED.contains(name)) {
            return Optional.of("'" + name + "' is a storage folder name and cannot be a world name");
        }
        return Optional.empty();
    }

    public synchronized Optional<Entry> get(final String name) {
        return Optional.ofNullable(this.entries.get(name));
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(this.entries.values());
    }

    public synchronized boolean contains(final String name) {
        return this.entries.containsKey(name);
    }

    /** Adds or replaces an entry and writes the file. */
    public synchronized void put(final Entry entry) throws IOException {
        final Entry previous = this.entries.put(entry.name(), entry);
        try {
            write();
        } catch (final IOException failed) {
            if (previous == null) this.entries.remove(entry.name());
            else this.entries.put(previous.name(), previous);
            throw failed;
        }
    }

    /** Removes an entry and writes the file. */
    public synchronized void remove(final String name) throws IOException {
        final Entry previous = this.entries.remove(name);
        if (previous == null) return;
        try {
            write();
        } catch (final IOException failed) {
            this.entries.put(name, previous);
            throw failed;
        }
    }

    private void write() throws IOException {
        final Path parent = this.file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        final Path temporary = Files.createTempFile(parent, "aurora-worlds", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(new ArrayList<>(this.entries.values())) + "\n",
                StandardCharsets.UTF_8);
            try {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
