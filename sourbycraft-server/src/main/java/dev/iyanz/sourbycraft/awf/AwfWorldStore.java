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
public final class AwfWorldStore implements AwfStore {

    static final String INDEX_BLOB = "index";
    /**
     * Index value for a chunk this world deleted. It shadows the base (a region file or template)
     * the way a write does, so a deleted chunk does not reappear from underneath.
     */
    static final String TOMBSTONE = "-";

    private final GenerationStore generations;
    private final ObjectStore objects;
    private final WorldRole role;
    private final int retainedGenerations;
    private volatile SortedMap<ChunkKey, String> index;
    /**
     * Object names each retained generation references, newest first. Kept in memory so a commit
     * neither re-reads the retained indexes nor scans the object directory.
     */
    private final java.util.ArrayDeque<Set<String>> retainedNames = new java.util.ArrayDeque<>();
    /** Objects written by commits that failed before their generation committed. */
    private final Set<String> orphanCandidates = new HashSet<>();
    /** Set by {@link #close}; guarded by this store's monitor. */
    private boolean closed;

    private AwfWorldStore(final GenerationStore generations, final ObjectStore objects, final WorldRole role,
                          final int retainedGenerations, final SortedMap<ChunkKey, String> index) {
        this.generations = generations;
        this.objects = objects;
        this.role = role;
        this.retainedGenerations = retainedGenerations;
        this.index = index;
    }

    /**
     * Opens (or creates) a world store.
     *
     * @param retainedGenerations committed generations kept for recovery, at least 1
     */
    public static AwfWorldStore open(final Path root, final WorldRole role, final int retainedGenerations)
        throws IOException {
        return open(root, role, retainedGenerations, stage -> {});
    }

    /** As {@link #open(Path, WorldRole, int)}, with a crash-injection hook for tests. */
    static AwfWorldStore open(final Path root, final WorldRole role, final int retainedGenerations,
                              final GenerationStore.StageHook hook) throws IOException {
        final GenerationStore generations = GenerationStore.open(root.resolve("generations-root"), role,
            retainedGenerations, hook);
        final ObjectStore objects = new ObjectStore(root.resolve("objects"));
        final GenerationStore.Generation current = generations.read();
        final SortedMap<ChunkKey, String> index = current == null ? new TreeMap<>() : decodeIndex(current);
        final AwfWorldStore store = new AwfWorldStore(generations, objects, role, retainedGenerations,
            Collections.unmodifiableSortedMap(index));
        for (final GenerationStore.Generation retained : generations.readRetained()) {
            store.retainedNames.addLast(names(decodeIndex(retained)));
        }
        if (store.retainedNames.isEmpty() && current != null) store.retainedNames.add(names(index));
        // The one full scan: objects a crash left unreferenced. Only a writable store collects.
        if (role.acceptsCommits()) {
            final Set<String> live = new HashSet<>();
            store.retainedNames.forEach(live::addAll);
            objects.retainOnly(live);
        }
        return store;
    }

    private static Set<String> names(final SortedMap<ChunkKey, String> index) {
        final Set<String> names = new HashSet<>(index.values());
        names.remove(TOMBSTONE);
        return names;
    }

    @Override
    public long generation() throws IOException {
        return this.generations.currentGeneration();
    }

    /** Chunks with committed bytes; deleted chunks are not included. */
    @Override
    public Set<ChunkKey> keys() {
        final Set<ChunkKey> live = new java.util.TreeSet<>();
        this.index.forEach((key, name) -> {
            if (!TOMBSTONE.equals(name)) live.add(key);
        });
        return live;
    }

    /** Chunks this world deleted: they read as absent and shadow the base. */
    @Override
    public Set<ChunkKey> deleted() {
        final Set<ChunkKey> deleted = new java.util.TreeSet<>();
        this.index.forEach((key, name) -> {
            if (TOMBSTONE.equals(name)) deleted.add(key);
        });
        return deleted;
    }

    /** Whether the committed state says anything about a chunk: bytes or a deletion. */
    @Override
    public boolean has(final ChunkKey key) {
        return this.index.containsKey(key);
    }

    @Override
    public Optional<byte[]> read(final ChunkKey key) throws IOException {
        final String name = this.index.get(key);
        return name == null || TOMBSTONE.equals(name) ? Optional.empty() : Optional.of(this.objects.get(name));
    }

    /**
     * Commits a new generation: the current index with {@code changed} applied and {@code removed}
     * dropped.
     *
     * @param changed chunk bytes that differ from the committed state; arrays are not retained
     * @param removed chunks to drop from the world
     */
    public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                               final PersistenceMode mode) throws IOException {
        return commit(changed, removed, Set.of(), mode);
    }

    /**
     * As {@link #commit(Map, Set, PersistenceMode)}, also recording {@code deleted} chunks as
     * deletions that shadow the base.
     */
    @Override
    public synchronized CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                            final Set<ChunkKey> deleted, final PersistenceMode mode)
        throws IOException {
        if (mode == PersistenceMode.READ_ONLY || !this.role.acceptsCommits()) {
            throw new IllegalStateException("a " + this.role + " world in " + mode + " mode does not accept commits");
        }
        if (this.closed) throw new IllegalStateException("this AWF store was closed; it accepts no more commits");
        final SortedMap<ChunkKey, String> next = new TreeMap<>(this.index);
        removed.forEach(next::remove);
        deleted.forEach(key -> next.put(key, TOMBSTONE));

        int written = 0;
        long bytes = 0;
        final Set<String> toVerify = new HashSet<>();
        final long generation;
        try {
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
                    if (changed.containsKey(chunk.getKey()) || TOMBSTONE.equals(chunk.getValue())) continue;
                    final ObjectStore.Written w = this.objects.put(this.objects.get(chunk.getValue()), true);
                    written++;
                    bytes += w.bytes();
                    toVerify.add(w.name());
                }
            }
            if (mode == PersistenceMode.CHECKPOINT) {
                toVerify.addAll(next.values());
                toVerify.remove(TOMBSTONE);
            }
            // Verify before the commit point: a generation must never reference an object that
            // cannot be read back.
            for (final String name : toVerify) {
                this.objects.get(name);
            }

            generation = this.generations.commit(Map.of(INDEX_BLOB, encodeIndex(next)));
        } catch (final IOException | RuntimeException failed) {
            // Whatever this commit wrote may now be referenced by nothing; the next successful
            // commit checks it. A crash instead is covered by the scan when the store is opened.
            this.orphanCandidates.addAll(toVerify);
            throw failed;
        }
        this.index = Collections.unmodifiableSortedMap(next);

        // Collect only what can have become garbage: the objects of generations that just fell
        // out of retention, and those of earlier failed commits, minus everything still retained.
        this.retainedNames.addFirst(names(next));
        final Set<String> candidates = new HashSet<>(this.orphanCandidates);
        while (this.retainedNames.size() > this.retainedGenerations) {
            candidates.addAll(this.retainedNames.removeLast());
        }
        int removedObjects = 0;
        if (!candidates.isEmpty()) {
            final Set<String> live = new HashSet<>();
            this.retainedNames.forEach(live::addAll);
            for (final String name : candidates) {
                if (!live.contains(name) && this.objects.delete(name)) removedObjects++;
            }
        }
        this.orphanCandidates.clear();
        return new CommitResult(generation, next.size(), written, bytes, removedObjects);
    }

    /**
     * Refuses every later commit; reads still work. Synchronized with {@link #commit}, so it
     * returns only after a commit that is running has finished.
     */
    @Override
    public synchronized void close() {
        this.closed = true;
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
                if (parts.length != 3 || (parts[2].length() != 64 && !TOMBSTONE.equals(parts[2]))) throw new NumberFormatException(line);
                index.put(new ChunkKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])), parts[2]);
            } catch (final NumberFormatException malformed) {
                throw new GenerationStore.CorruptGenerationException("malformed index line: " + line);
            }
        }
        return index;
    }
}
