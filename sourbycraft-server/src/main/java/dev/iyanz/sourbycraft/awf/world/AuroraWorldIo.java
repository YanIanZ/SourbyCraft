package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.awf.AwfBackend;
import dev.iyanz.sourbycraft.awf.AwfEngine;
import dev.iyanz.sourbycraft.awf.AwfStore;
import dev.iyanz.sourbycraft.awf.ChunkKey;
import dev.iyanz.sourbycraft.awf.ChunkSource;
import dev.iyanz.sourbycraft.awf.PersistenceMode;
import dev.iyanz.sourbycraft.awf.WorldRole;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reading and writing a whole world's stores, on whichever backend they live: what templates,
 * {@code .awf} export and every import are built from.
 */
final class AuroraWorldIo {

    /**
     * Chunks per commit when writing, so a large world is never held in memory at once. Large,
     * because every commit pays the backend's durability wait (an fsync, or Redis's WAITAOF).
     */
    static final int BATCH = 4096;
    /** Bytes per commit, whichever limit comes first. */
    static final long BATCH_BYTES = 16L * 1024 * 1024;

    /** Receives a world's chunks one by one. */
    interface Sink {
        void accept(ChunkKey key, byte[] bytes) throws IOException;
    }

    private AuroraWorldIo() {}

    /** A world's store for one storage folder, opened read-only, or {@code null} when it has none. */
    static AwfStore openRead(final AwfBackend backend, final Path dimensionFolder, final String storage)
        throws IOException {
        final String id = AwfEngine.storeId(dimensionFolder, storage);
        return backend.exists(id) ? backend.open(id, WorldRole.READ_ONLY, 1) : null;
    }

    /** A template's store for one storage folder (always on disk), or {@code null}. */
    static AwfStore openTemplate(final Path templatesRoot, final String template, final String storage)
        throws IOException {
        return openRead(AwfBackend.FILE, templatesRoot.resolve(template), storage);
    }

    /**
     * Every chunk of {@code from} over {@code under}, as the world reads it. A chunk {@code from}
     * deleted is left out, so it is generated again, as it would be in the world. Neither input is
     * written.
     *
     * @return chunks passed to the sink
     */
    static int flatten(final AwfStore from, final AwfStore under, final Sink sink) throws IOException {
        if (from == null && under == null) return 0;
        final Set<ChunkKey> keys = new TreeSet<>();
        if (under != null) keys.addAll(under.keys());
        if (from != null) {
            keys.addAll(from.keys());
            keys.removeAll(from.deleted());
        }
        int written = 0;
        for (final ChunkKey key : keys) {
            final Optional<byte[]> bytes = from != null && from.has(key) ? from.read(key) : under.read(key);
            if (bytes.isEmpty()) continue;
            sink.accept(key, bytes.get());
            written++;
        }
        return written;
    }

    /** Copies every chunk of a source into a fresh store, committing in batches. */
    static int copy(final ChunkSource source, final AwfStore target) throws IOException {
        final BatchSink sink = into(target);
        for (final ChunkKey key : new TreeSet<>(source.keys())) {
            final Optional<byte[]> bytes = source.read(key);
            if (bytes.isPresent()) sink.accept(key, bytes.get());
        }
        return sink.finish();
    }

    /** A committing sink into {@code target}; call {@link BatchSink#finish} at the end. */
    static BatchSink into(final AwfStore target) {
        return new BatchSink(target);
    }

    static final class BatchSink implements Sink {
        private final AwfStore target;
        private final Map<ChunkKey, byte[]> batch = new HashMap<>();
        private long batchBytes;
        private int written;

        private BatchSink(final AwfStore target) {
            this.target = target;
        }

        @Override
        public void accept(final ChunkKey key, final byte[] bytes) throws IOException {
            this.batch.put(key, bytes);
            this.batchBytes += bytes.length;
            if (this.batch.size() >= BATCH || this.batchBytes >= BATCH_BYTES) {
                this.written += commit(this.target, this.batch);
                this.batchBytes = 0;
            }
        }

        int finish() throws IOException {
            return this.written + commit(this.target, this.batch);
        }
    }

    private static int commit(final AwfStore target, final Map<ChunkKey, byte[]> batch) throws IOException {
        if (batch.isEmpty()) return 0;
        final int size = batch.size();
        target.commit(batch, Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        batch.clear();
        return size;
    }

    /**
     * Writes chunks into a new world's stores on the configured backend, one storage folder at a
     * time, closing each store (releasing a network backend's lease) when done.
     *
     * @param sources storage folder name to its chunks
     * @return chunks written per storage folder
     */
    static Map<String, Integer> writeWorld(final Path dimensionFolder, final Map<String, ChunkSource> sources)
        throws IOException {
        final AwfBackend backend = AwfEngine.backend();
        final Map<String, Integer> written = new HashMap<>();
        for (final Map.Entry<String, ChunkSource> source : sources.entrySet()) {
            if (source.getValue().keys().isEmpty()) {
                written.put(source.getKey(), 0);
                continue;
            }
            final AwfStore store = backend.open(AwfEngine.storeId(dimensionFolder, source.getKey()), WorldRole.VANILLA, 1);
            try {
                written.put(source.getKey(), copy(source.getValue(), store));
            } finally {
                store.close();
            }
        }
        return written;
    }

    /** A chunk source over an in-memory map. */
    static ChunkSource of(final Map<ChunkKey, byte[]> chunks) {
        return new ChunkSource() {
            @Override
            public Optional<byte[]> read(final ChunkKey key) {
                final byte[] bytes = chunks.get(key);
                return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
            }

            @Override
            public Set<ChunkKey> keys() {
                return chunks.keySet();
            }
        };
    }
}
