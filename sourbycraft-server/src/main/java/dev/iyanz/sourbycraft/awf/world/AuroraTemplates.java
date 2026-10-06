package dev.iyanz.sourbycraft.awf.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.iyanz.sourbycraft.awf.AwfEngine;
import dev.iyanz.sourbycraft.awf.AwfRegionStorage;
import dev.iyanz.sourbycraft.awf.AwfStore;
import dev.iyanz.sourbycraft.awf.AwfWorldStore;
import dev.iyanz.sourbycraft.awf.ChunkKey;
import dev.iyanz.sourbycraft.awf.PersistenceMode;
import dev.iyanz.sourbycraft.awf.WorldRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Templates: frozen AWF worlds that copy-on-write instances read through.
 *
 * <p>A template is a folder under {@code awf-templates/} holding one store per storage folder
 * ({@code region.awf}, {@code entities.awf}, {@code poi.awf}) and {@code template.json}, the
 * environment, seed and generator its instances are created with. It is written once, by
 * flattening a world's committed chunks — and, for a world that is itself an instance, its own
 * template's chunks beneath them — into fresh stores. Nothing writes to it afterwards; instances
 * keep their own changes in their own stores.</p>
 *
 * <p>The template is built in a hidden folder and moved into place at the end, so a crash or a
 * failure part-way never leaves a half-written template that instances could be created from.</p>
 */
public final class AuroraTemplates {

    static final String METADATA = "template.json";
    /** Chunks per commit while flattening, so a large world is never held in memory at once. */
    static final int BATCH = 256;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path root;

    public AuroraTemplates(final Path root) {
        this.root = root;
    }

    /** Every complete template. */
    public Set<String> list() throws IOException {
        final Set<String> names = new TreeSet<>();
        if (!Files.isDirectory(this.root)) return names;
        try (Stream<Path> children = Files.list(this.root)) {
            children.filter(child -> Files.isRegularFile(child.resolve(METADATA)))
                .map(child -> child.getFileName().toString())
                .filter(name -> !name.startsWith("."))
                .forEach(names::add);
        }
        return names;
    }

    public boolean exists(final String template) {
        return Files.isRegularFile(this.root.resolve(template).resolve(METADATA));
    }

    /** What instances of the template are created with; the entry's name is the template's. */
    public Optional<AuroraWorldRegistry.Entry> read(final String template) throws IOException {
        final Path file = this.root.resolve(template).resolve(METADATA);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.ofNullable(GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                AuroraWorldRegistry.Entry.class));
        } catch (final JsonParseException malformed) {
            throw new IOException(file + " is not a valid template description", malformed);
        }
    }

    /**
     * Saves a world's committed chunks as a new template. The world must not be loaded: a loaded
     * world keeps committing, and a template is a single consistent moment.
     *
     * @param worldFolder the world's dimension folder
     * @param world the world's registry entry; its environment, seed and generator are recorded
     * @return chunks written, over all storage folders
     */
    public int save(final Path worldFolder, final AuroraWorldRegistry.Entry world, final String template)
        throws IOException {
        final Path target = this.root.resolve(template);
        if (Files.exists(target)) throw new IllegalStateException("a template named " + template + " already exists");
        Files.createDirectories(this.root);
        final Path partial = this.root.resolve("." + template + ".partial-" + System.nanoTime());
        try {
            int chunks = 0;
            for (final String folder : AwfEngine.STORAGE_FOLDERS) {
                final Path base = world.template() == null ? null : store(this.root.resolve(world.template()), folder);
                chunks += flatten(store(worldFolder, folder), base, store(partial, folder));
            }
            final AuroraWorldRegistry.Entry description = world.named(template, null, false);
            Files.writeString(partial.resolve(METADATA), GSON.toJson(description) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException notAtomic) {
                Files.move(partial, target);
            }
            return chunks;
        } finally {
            deleteRecursively(partial);
        }
    }

    /** Deletes a template. The caller has checked that no world is an instance of it. */
    public void delete(final String template) throws IOException {
        final Path target = this.root.resolve(template);
        if (!Files.isDirectory(target)) throw new IllegalArgumentException("no template named " + template);
        // Hide it first, so a failure part-way never leaves a template that looks complete.
        final Path doomed = this.root.resolve("." + template + ".deleting-" + System.nanoTime());
        Files.move(target, doomed);
        deleteRecursively(doomed);
    }

    /** The store directory of one storage folder under a dimension or template folder. */
    static Path store(final Path folder, final String storageFolder) {
        return AwfRegionStorage.storeFor(folder.resolve(storageFolder));
    }

    /**
     * Writes every chunk of {@code source} over {@code base} into a new store at {@code target}.
     * A chunk the source deleted is left out, so it is generated again, as it would be in the
     * source. Neither input is written.
     *
     * @return chunks written; 0 when neither input exists, in which case nothing is created
     */
    static int flatten(final Path source, final Path base, final Path target) throws IOException {
        final int retained = 1;
        final AwfStore from = Files.isDirectory(source) ? AwfWorldStore.open(source, WorldRole.READ_ONLY, retained) : null;
        final AwfStore under = base != null && Files.isDirectory(base)
            ? AwfWorldStore.open(base, WorldRole.READ_ONLY, retained) : null;
        if (from == null && under == null) return 0;
        final Set<ChunkKey> keys = new TreeSet<>();
        if (under != null) keys.addAll(under.keys());
        if (from != null) {
            keys.addAll(from.keys());
            keys.removeAll(from.deleted());
        }
        final AwfWorldStore out = AwfWorldStore.open(target, WorldRole.VANILLA, retained);
        final Map<ChunkKey, byte[]> batch = new HashMap<>();
        int written = 0;
        for (final ChunkKey key : keys) {
            final Optional<byte[]> bytes = from != null && from.has(key) ? from.read(key) : under.read(key);
            if (bytes.isEmpty()) continue;
            batch.put(key, bytes.get());
            if (batch.size() == BATCH) {
                written += commit(out, batch);
            }
        }
        written += commit(out, batch);
        return written;
    }

    private static int commit(final AwfWorldStore out, final Map<ChunkKey, byte[]> batch) throws IOException {
        if (batch.isEmpty()) return 0;
        final int size = batch.size();
        out.commit(batch, Set.of(), PersistenceMode.INCREMENTAL);
        batch.clear();
        return size;
    }

    public static void deleteRecursively(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            final List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (final Path path : paths) Files.delete(path);
        }
    }
}
