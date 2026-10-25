package dev.iyanz.sourbycraft.awf.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
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
 * temporary file that is forced to disk and atomically moved into place, and the directory is
 * forced after the move, so a crash or power loss mid-write leaves the previous version or the new
 * one. A file that is empty or holds {@code null} is refused like a malformed one, never read as
 * an empty list.</p>
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
    /** The vanilla dimensions' names: a managed world with one of them would be that dimension. */
    private static final Set<String> VANILLA_DIMENSIONS = Set.of("overworld", "the_nether", "the_end");

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
     * @param knownStores the storage folders ({@code region}, {@code entities}, {@code poi}) whose AWF
     *     store this server found for the world when it last loaded or saved it; a load refuses
     *     while one of them is missing, instead of opening an empty store in its place. {@code null}
     *     until first recorded, which is also how entries written before Build 50 read: those load
     *     as before until their stores are recorded at their next load
     */
    public record Entry(String name, String environment, Long seed, String generator, String worldType,
                        String template, boolean autoload,
                        dev.iyanz.sourbycraft.api.world.WorldProperties properties, List<String> knownStores) {

        /** An entry without properties. */
        public Entry(final String name, final String environment, final Long seed, final String generator,
                     final String worldType, final String template, final boolean autoload) {
            this(name, environment, seed, generator, worldType, template, autoload, null);
        }

        /** An entry whose stores were never recorded. */
        public Entry(final String name, final String environment, final Long seed, final String generator,
                     final String worldType, final String template, final boolean autoload,
                     final dev.iyanz.sourbycraft.api.world.WorldProperties properties) {
            this(name, environment, seed, generator, worldType, template, autoload, properties, null);
        }

        /** Never null: an entry written before properties existed reads as {@code NONE}. */
        public dev.iyanz.sourbycraft.api.world.WorldProperties propertiesOrNone() {
            return this.properties == null ? dev.iyanz.sourbycraft.api.world.WorldProperties.NONE : this.properties;
        }

        public Entry withAutoload(final boolean value) {
            return new Entry(this.name, this.environment, this.seed, this.generator, this.worldType, this.template, value,
                this.properties, this.knownStores);
        }

        public Entry withProperties(final dev.iyanz.sourbycraft.api.world.WorldProperties value) {
            return new Entry(this.name, this.environment, this.seed, this.generator, this.worldType, this.template,
                this.autoload, value, this.knownStores);
        }

        public Entry withKnownStores(final List<String> value) {
            return new Entry(this.name, this.environment, this.seed, this.generator, this.worldType, this.template,
                this.autoload, this.properties, value);
        }

        /**
         * A new world described by this one (a template's description), keeping its properties. Its
         * stores are its own and not recorded yet.
         */
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
    /** The entries being written; guarded by {@code this}, which a write holds across its fsyncs. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /**
     * What the readers see: {@link #entries} as of the last write that reached the file, replaced
     * whole and never changed. Readers run on tick threads and must not wait for a write's fsync.
     */
    private volatile Map<String, Entry> published = Map.of();

    /**
     * Reads {@code file} when it exists. A stored spawn outside the world is dropped with a WARN
     * naming the world ({@link #dropInvalidSpawn}) on each read until a write leaves it out (at
     * boot the AWF engine and the world service each read the file); it is no longer applied at
     * load.
     *
     * @throws IOException when the file is malformed, empty or {@code null}, or an entry holds
     *     other properties a record constructor refuses (save bounds with min above max)
     */
    public AuroraWorldRegistry(final Path file) throws IOException {
        this.file = file;
        if (Files.isRegularFile(file)) {
            final JsonElement stored;
            try {
                stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonElement.class);
            } catch (final JsonParseException malformed) {
                throw new IOException(file + " is not a valid world list; fix or remove it", malformed);
            }
            if (stored == null || stored.isJsonNull()) {
                // Gson reads an empty file as null and a literal null as JsonNull. This class always writes a
                // list ("[]" when empty), so that is a damaged file; reading it as empty would drop every world.
                throw new IOException(file + " is empty or null, not a world list (damaged by a crash?); restore it or"
                    + " remove it");
            }
            if (!stored.isJsonArray()) throw new IOException(file + " is not a valid world list; fix or remove it");
            for (final JsonElement element : stored.getAsJsonArray()) {
                if (element.isJsonNull()) continue;
                final Optional<String> droppedSpawn = dropInvalidSpawn(element);
                final Entry entry;
                try {
                    entry = GSON.fromJson(element, Entry.class);
                } catch (final JsonParseException malformed) {
                    throw new IOException(file + " is not a valid world list; fix or remove it", malformed);
                } catch (final RuntimeException refused) {
                    // Gson wraps a record constructor's refusal in a plain RuntimeException.
                    throw new IOException(file + " holds a world entry with invalid properties (" + refusal(refused)
                        + "); fix or remove it", refused);
                }
                final Optional<String> invalid = invalidName(entry.name());
                if (invalid.isPresent()) {
                    dev.iyanz.sourbycraft.util.SourbyLogger.warn("Aurora World Fabric: ignoring the"
                        + " registry entry '" + entry.name() + "': " + invalid.get());
                    continue;
                }
                droppedSpawn.ifPresent(reason -> dev.iyanz.sourbycraft.util.SourbyLogger.warn("Aurora World Fabric:"
                    + " ignoring the stored spawn of world '" + entry.name() + "' in " + file + " (" + reason + ");"
                    + " it is no longer applied at load (set a new one with /awf set " + entry.name() + " spawn), and"
                    + " the next write of the file leaves it out"));
                this.entries.put(entry.name(), entry);
            }
        }
        this.published = snapshot();
    }

    /**
     * Removes {@code properties.spawn} from a stored entry when
     * {@link dev.iyanz.sourbycraft.api.world.WorldProperties.Spawn} refuses its values, and returns
     * why; empty, the entry unchanged, otherwise. Build 50 started refusing a spawn outside the
     * world, so a registry or template file written before then can hold one, and refusing the
     * whole file would keep every AWF world from loading over one spawn. A spawn that is malformed
     * rather than out of range (not numbers) stays, for the entry's own read to refuse.
     */
    static Optional<String> dropInvalidSpawn(final JsonElement entry) {
        if (!(entry instanceof JsonObject object) || !(object.get("properties") instanceof JsonObject properties)) {
            return Optional.empty();
        }
        final JsonElement spawn = properties.get("spawn");
        if (spawn == null || spawn.isJsonNull()) return Optional.empty();
        try {
            GSON.fromJson(spawn, dev.iyanz.sourbycraft.api.world.WorldProperties.Spawn.class);
            return Optional.empty();
        } catch (final JsonParseException malformed) {
            return Optional.empty();
        } catch (final RuntimeException refused) {
            properties.remove("spawn");
            return Optional.of(refusal(refused));
        }
    }

    /** A record constructor's own message, which Gson wraps as the cause of a plain RuntimeException. */
    static String refusal(final RuntimeException refused) {
        return refused.getCause() != null && refused.getCause().getMessage() != null
            ? refused.getCause().getMessage() : refused.getMessage();
    }

    /** Why a name cannot be a world name, or empty when it can. */
    public static Optional<String> invalidName(final String name) {
        final Optional<String> invalid = invalidTemplateName(name);
        if (invalid.isPresent()) return invalid;
        if (VANILLA_DIMENSIONS.contains(name)) {
            return Optional.of("'" + name + "' is a vanilla dimension name and cannot be a world name");
        }
        if (name.equals(levelName())) {
            return Optional.of("'" + name + "' is the server's level-name (the main world's folder) and cannot be a"
                + " world name");
        }
        return Optional.empty();
    }

    /**
     * Why a name cannot be a template name, or empty when it can. Templates follow the world name
     * rules except the dimension and level-name ones: a template name is a folder under
     * {@code awf-templates/}, never a dimension.
     */
    public static Optional<String> invalidTemplateName(final String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.of("world names are 1-48 characters of a-z, 0-9, _ and -");
        }
        if (RESERVED.contains(name)) {
            return Optional.of("'" + name + "' is a storage folder name and cannot be a world name");
        }
        return Optional.empty();
    }

    /** The server's level-name, or {@code null} without a running server (unit tests). */
    private static String levelName() {
        if (org.bukkit.Bukkit.getServer() == null) return null;
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        return server == null ? null : server.storageSource.getLevelId();
    }

    public Optional<Entry> get(final String name) {
        return Optional.ofNullable(this.published.get(name));
    }

    public List<Entry> all() {
        return new ArrayList<>(this.published.values());
    }

    public boolean contains(final String name) {
        return this.published.containsKey(name);
    }

    /** Adds or replaces an entry and writes the file; readers see it once the write succeeded. */
    public synchronized void put(final Entry entry) throws IOException {
        final Entry previous = this.entries.put(entry.name(), entry);
        try {
            write();
        } catch (final IOException failed) {
            if (previous == null) this.entries.remove(entry.name());
            else this.entries.put(previous.name(), previous);
            throw failed;
        }
        this.published = snapshot();
    }

    /** Removes an entry and writes the file; readers stop seeing it once the write succeeded. */
    public synchronized void remove(final String name) throws IOException {
        final Entry previous = this.entries.remove(name);
        if (previous == null) return;
        try {
            write();
        } catch (final IOException failed) {
            this.entries.put(name, previous);
            throw failed;
        }
        this.published = snapshot();
    }

    /** An unmodifiable copy of {@link #entries}, in file order; called with {@code this} held or from the constructor. */
    private Map<String, Entry> snapshot() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(this.entries));
    }

    private void write() throws IOException {
        final Path parent = this.file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        final Path temporary = Files.createTempFile(parent, "aurora-worlds", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(new ArrayList<>(this.entries.values())) + "\n",
                StandardCharsets.UTF_8);
            // The bytes before the rename, the rename before success: after power loss the file is
            // the previous list or the new one, never a renamed empty file.
            dev.iyanz.sourbycraft.awf.Durability.DISK.forceFile(temporary);
            try {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
            dev.iyanz.sourbycraft.awf.Durability.DISK.forceDirectory(parent);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
