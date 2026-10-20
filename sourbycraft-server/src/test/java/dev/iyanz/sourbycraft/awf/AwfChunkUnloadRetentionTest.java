package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.retention.Reachability;
import dev.iyanz.sourbycraft.retention.Reachability.Tracker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chunk-unload retention for the AWF-owned chunk bytes (AURORA-TASKS F "chunk-unload retention test"),
 * at unit level: when the engine lets go of a chunk, does anything in {@code sourbycraft/awf} still hold
 * its serialized bytes?
 *
 * <p>How "the engine lets go" maps here: the chunk system writes a chunk when it unloads and the world
 * keeps the bytes resident until a commit makes them clean and the resident limit drops them
 * ({@code AwfWorld.evictCleanBeyondLimit}); a later load reads them back from the store. The arrays
 * tracked below are the exact instances the world passes to {@link AwfStore#commit} (they are the ones in
 * its {@code owned} map), captured by a spying store that keeps only a weak handle. Each release is paired
 * with a control: a chunk the world still holds must stay reachable, so a pass cannot mean "never held".</p>
 *
 * <p>Not covered: the Minecraft side of an unload (the engine's chunk holders, entities, block entities)
 * and a real heap comparison on a running server; this proves only that the AWF layer does not pin bytes.</p>
 */
class AwfChunkUnloadRetentionTest {

    @TempDir Path dir;

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Delegating store that remembers, weakly, every chunk array handed to {@link #commit}. */
    private static final class Spy implements AwfStore {
        private final AwfStore delegate;
        final Map<ChunkKey, Tracker> committed = new HashMap<>();

        Spy(final AwfStore delegate) {
            this.delegate = delegate;
        }

        @Override public boolean has(final ChunkKey key) { return this.delegate.has(key); }
        @Override public Optional<byte[]> read(final ChunkKey key) throws IOException { return this.delegate.read(key); }
        @Override public Set<ChunkKey> keys() { return this.delegate.keys(); }
        @Override public Set<ChunkKey> deleted() { return this.delegate.deleted(); }
        @Override public long generation() throws IOException { return this.delegate.generation(); }

        @Override
        public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                   final Set<ChunkKey> deleted, final PersistenceMode mode) throws IOException {
            changed.forEach((key, array) -> this.committed.put(key, Reachability.track("bytes of chunk " + key, array)));
            return this.delegate.commit(changed, removed, deleted, mode);
        }

        @Override public void close() throws IOException { this.delegate.close(); }
    }

    private Spy spy(final String name) throws IOException {
        return new Spy(AwfWorldStore.open(this.dir.resolve(name), WorldRole.VANILLA, 1));
    }

    /** Writes the chunks and saves; kept out of the test body so no local pins an array. */
    private static void writeAndSave(final AwfWorld world, final int chunks) throws Exception {
        for (int x = 0; x < chunks; x++) world.write(new ChunkKey(x, 0), bytes("chunk-" + x));
        world.save(PersistenceMode.INCREMENTAL, Runnable::run, 1).toCompletableFuture().get();
    }

    @Test
    void chunksTheWorldDroppedAreCollectableAndTheOnesItKeptAreHeld() throws Exception {
        final Spy store = spy("limit");
        final AwfWorld world = new AwfWorld("limit", WorldRole.VANILLA, null, store, 2);
        writeAndSave(world, 5);

        assertEquals(3, world.evicted(), "5 chunks, limit 2: three clean ones dropped");
        int released = 0;
        int kept = 0;
        for (int x = 0; x < 5; x++) {
            final ChunkKey key = new ChunkKey(x, 0);
            final Tracker tracker = store.committed.get(key);
            assertNotNull(tracker, "every chunk went through a commit");
            if (world.resident(key)) {
                tracker.assertStillReachable(); // control: the world really holds what it did not drop
                kept++;
            } else {
                tracker.assertCollected();      // the unloaded chunk's bytes are held by nothing
                released++;
            }
        }
        assertEquals(3, released);
        assertEquals(2, kept);
    }

    @Test
    void loadingEvictedChunksBackAndUnloadingThemAgainNeverGrowsTheResidentSet() throws Exception {
        final Spy store = spy("cycles");
        final AwfWorld world = new AwfWorld("cycles", WorldRole.VANILLA, null, store, 2);
        writeAndSave(world, 6);
        assertEquals(2, world.metrics().residentChunks());

        for (int cycle = 0; cycle < 50; cycle++) {
            for (int x = 0; x < 6; x++) {
                final ChunkKey key = new ChunkKey(x, 0);
                assertArrayEquals(bytes("chunk-" + x), world.read(key).orElseThrow(), "cycle " + cycle + " chunk " + x);
            }
        }

        assertEquals(2, world.metrics().residentChunks(), "reading evicted chunks from the store re-pins nothing");
        assertEquals(0, world.dirtyCount());
        assertEquals(0, world.pendingSaveCount());
    }

    @Test
    void rewritingAChunkReleasesItsPreviousBytesWithoutAnEviction() throws Exception {
        final Spy store = spy("rewrite");
        final AwfWorld world = new AwfWorld("rewrite", WorldRole.VANILLA, null, store, 0); // unlimited: nothing evicts
        final ChunkKey key = new ChunkKey(0, 0);
        writeAndSave(world, 1);
        final Tracker first = store.committed.get(key);
        assertNotNull(first);
        first.assertStillReachable();

        world.write(key, bytes("chunk-0 v2"));
        world.save(PersistenceMode.INCREMENTAL, Runnable::run, 1).toCompletableFuture().get();

        assertEquals(0, world.evicted());
        first.assertCollected();
        assertTrue(world.resident(key));
        assertArrayEquals(bytes("chunk-0 v2"), world.read(key).orElseThrow());
    }

    @Test
    void anEngineStorageReleasesUnloadedChunkBytesAfterAFlush() throws Exception {
        final Map<String, Spy> spies = new HashMap<>();
        final AwfBackend backend = new AwfBackend() {
            @Override public String name() { return "retention-spy"; }
            @Override public boolean exists(final String storageId) { return false; }

            @Override
            public AwfStore open(final String storageId, final WorldRole role, final int retained) throws IOException {
                final Spy spy = new Spy(AwfBackend.FILE.open(storageId, role, retained));
                spies.put(storageId, spy);
                return spy;
            }

            @Override public String retire(final String storageId) throws IOException { return AwfBackend.FILE.retire(storageId); }
        };
        final AwfEngine engine = new AwfEngine(new AwfSettings(Set.of("wu"), PersistenceMode.INCREMENTAL, 30, 3, 2, 2),
            Runnable::run, System::nanoTime, name -> backend);
        final Path folder = this.dir.resolve("wu").resolve("region");
        Files.createDirectories(folder);
        final AwfRegionStorage storage = engine.open(folder);
        assertNotNull(storage);

        for (int x = 0; x < 10; x++) storage.write(x, 0, bytes("chunk-" + x));
        storage.flush();

        assertEquals(1, spies.size());
        final Spy spy = spies.values().iterator().next();
        assertEquals(7, storage.stats().evicted());
        assertTrue(storage.stats().world().residentChunks() <= 3);
        // Least recently used first and nothing was read since the writes: chunks 0-6 are the unloaded ones,
        // 7-9 the control the world still holds. (AwfWorldTest pins the LRU order itself.)
        for (int x = 0; x < 10; x++) {
            final Tracker tracker = spy.committed.get(new ChunkKey(x, 0));
            assertNotNull(tracker);
            if (x < 7) tracker.assertCollected();
            else tracker.assertStillReachable();
        }
        for (int x = 0; x < 10; x++) assertArrayEquals(bytes("chunk-" + x), storage.read(x, 0), "still readable from the store");
        assertFalse(storage.closed());
        storage.close();
    }
}
