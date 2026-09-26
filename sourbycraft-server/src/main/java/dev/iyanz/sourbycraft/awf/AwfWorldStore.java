package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A world's committed chunks on the FILE backend: a chunk index per generation in a
 * {@link GenerationStore}, chunk bytes in a content-addressed {@link ObjectStore}.
 *
 * <p>Commit sequence, for every mode: write objects (each forced and moved into place) → read back
 * and verify the objects this commit wrote (CHECKPOINT: every object the new index references) →
 * commit the index as a new generation, which is the single commit point → drop objects no
 * retained generation references. A crash anywhere before the generation commit leaves the
 * previous generation authoritative; the new objects are unreferenced and are removed later.</p>
 *
 * <p>Blocking; must not run on a region thread ({@link GenerationStore} refuses). {@link AwfWorld}
 * calls it from a storage executor.</p>
 */
public final class AwfWorldStore implements ChunkSource {

    static final String INDEX_BLOB = "index";

    /** What a commit did. */
    public record CommitResult(long generation, int chunks, int objectsWritten, long bytesWritten, int objectsRemoved) {}

    private final GenerationStore generations;
    private final ObjectStore objects;
    private final WorldRole role;
    private volatile SortedMap<ChunkKey, String> index;

    private AwfWorldStore(final GenerationStore generations, final ObjectStore objects, final WorldRole role,
                          final SortedMap<ChunkKey, String> index) {
        this.generations = generations;
        this.objects = objects;
        this.role = role;
        this.index = index;
    }

    /**
     * Opens (or creates) a world store.
     *
     * @param retainedGenerations committed generations kept for recovery, at least 1
     */
    public static AwfWorldStore open(final Path root, final WorldRole role, final int retainedGenerations)
        throws IOException {
        final GenerationStore generations = GenerationStore.open(root.resolve("generations-root"), role, retainedGenerations);
        final ObjectStore objects = new ObjectStore(root.resolve("objects"));
        final GenerationStore.Generation current = generations.read();
        final SortedMap<ChunkKey, String> index = current == null ? new TreeMap<>() : decodeIndex(current);
        return new AwfWorldStore(generations, objects, role, Collections.unmodifiableSortedMap(index));
    }

    public long generation() throws IOException {
        return this.generations.currentGeneration();
    }

    @Override
    public Set<ChunkKey> keys() {
        return this.index.keySet();
    }

    @Override
    public Optional<byte[]> read(final ChunkKey key) throws IOException {
        final String name = this.index.get(key);
        return name == null ? Optional.empty() : Optional.of(this.objects.get(name));
    }

    /**
     * Commits a new generation: the current index with {@code changed} applied and {@code removed}
     * dropped.
     *
     * @param changed chunk bytes that differ from the committed state; arrays are not retained
     * @param removed chunks to drop from the world
     */
    public synchronized CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                            final PersistenceMode mode) throws IOException {
        if (mode == PersistenceMode.READ_ONLY || !this.role.acceptsCommits()) {
            throw new IllegalStateException("a " + this.role + " world in " + mode + " mode does not accept commits");
        }
        final SortedMap<ChunkKey, String> next = new TreeMap<>(this.index);
        removed.forEach(next::remove);

        int written = 0;
        long bytes = 0;
        final Set<String> toVerify = new HashSet<>();
        for (final Map.Entry<ChunkKey, byte[]> chunk : changed.entrySet()) {
            final ObjectStore.Written w = this.objects.put(chunk.getValue(), mode == PersistenceMode.FULL);
            next.put(chunk.getKey(), w.name());
            if (w.bytes() > 0) {
                written++;
                bytes += w.bytes();
                toVerify.add(w.name());
            }
        }
        if (mode == PersistenceMode.FULL) {
            // FULL rewrites unchanged chunks as well, from their committed (verified) bytes.
            for (final Map.Entry<ChunkKey, String> chunk : next.entrySet()) {
                if (changed.containsKey(chunk.getKey())) continue;
                final ObjectStore.Written w = this.objects.put(this.objects.get(chunk.getValue()), true);
                written++;
                bytes += w.bytes();
                toVerify.add(w.name());
            }
        }
        if (mode == PersistenceMode.CHECKPOINT) {
            toVerify.addAll(next.values());
        }
        // Verify before the commit point: a generation must never reference an object that
        // cannot be read back.
        for (final String name : toVerify) {
            this.objects.get(name);
        }

        final long generation = this.generations.commit(Map.of(INDEX_BLOB, encodeIndex(next)));
        this.index = Collections.unmodifiableSortedMap(next);

        final Set<String> live = new HashSet<>(next.values());
        for (final GenerationStore.Generation retained : this.generations.readRetained()) {
            live.addAll(decodeIndex(retained).values());
        }
        final int removedObjects = this.objects.retainOnly(live);
        return new CommitResult(generation, next.size(), written, bytes, removedObjects);
    }

    static byte[] encodeIndex(final SortedMap<ChunkKey, String> index) {
        final StringBuilder out = new StringBuilder(index.size() * 80);
        for (final Map.Entry<ChunkKey, String> e : index.entrySet()) {
            out.append(e.getKey().x()).append(' ').append(e.getKey().z()).append(' ').append(e.getValue()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static SortedMap<ChunkKey, String> decodeIndex(final GenerationStore.Generation generation) throws IOException {
        final byte[] bytes = generation.blobs().get(INDEX_BLOB);
        if (bytes == null) throw new GenerationStore.CorruptGenerationException("generation " + generation.number() + " has no index");
        final SortedMap<ChunkKey, String> index = new TreeMap<>();
        for (final String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            final String[] parts = line.split(" ");
            try {
                if (parts.length != 3 || parts[2].length() != 64) throw new NumberFormatException(line);
                index.put(new ChunkKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])), parts[2]);
            } catch (final NumberFormatException malformed) {
                throw new GenerationStore.CorruptGenerationException("malformed index line: " + line);
            }
        }
        return index;
    }
}
