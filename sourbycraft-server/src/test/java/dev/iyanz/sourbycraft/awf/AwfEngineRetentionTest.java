package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.retention.Reachability;
import dev.iyanz.sourbycraft.retention.Reachability.Tracker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@link AwfEngine} keeps per world, and that a world unload (a storage close, with or without
 * saving) and a world delete ({@code release}) let go of it. The engine indexes open storages by folder,
 * each storage holds its world's resident chunk bytes, so a slot that outlives its storage pins both the
 * chunks and the backend store (for Redis, the world's lease).
 *
 * <p>Kept on purpose, not asserted as released: the set of managed world <em>names</em> after a plain
 * unload (the world is still in the registry and reattaches on load) and read-only template stores (shared
 * by every instance of a template until the template is deleted).</p>
 */
class AwfEngineRetentionTest {

    @TempDir Path dir;

    /** FILE backend that counts store opens/closes, can fail commits, and never reports a store as existing. */
    private static final class Backend implements AwfBackend {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        volatile boolean failCommits;

        @Override public String name() { return "retention"; }

        @Override public boolean exists(final String storageId) { return false; }

        @Override
        public AwfStore open(final String storageId, final WorldRole role, final int retained) throws IOException {
            final AwfStore store = AwfBackend.FILE.open(storageId, role, retained);
            this.opened.incrementAndGet();
            return new AwfStore() {
                @Override public boolean has(final ChunkKey key) { return store.has(key); }
                @Override public java.util.Optional<byte[]> read(final ChunkKey key) throws IOException { return store.read(key); }
                @Override public Set<ChunkKey> keys() { return store.keys(); }
                @Override public Set<ChunkKey> deleted() { return store.deleted(); }
                @Override public long generation() throws IOException { return store.generation(); }

                @Override
                public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                           final Set<ChunkKey> deleted, final PersistenceMode mode) throws IOException {
                    if (Backend.this.failCommits) throw new IOException("backend refused the commit");
                    return store.commit(changed, removed, deleted, mode);
                }

                @Override
                public void close() throws IOException {
                    Backend.this.closed.incrementAndGet();
                    store.close();
                }
            };
        }

        @Override public String retire(final String storageId) throws IOException { return AwfBackend.FILE.retire(storageId); }
    }

    private AwfEngine engine(final Backend backend, final Set<String> worlds) {
        return new AwfEngine(new AwfSettings(worlds, PersistenceMode.INCREMENTAL, 30, 1024, 2, 2), Runnable::run,
            System::nanoTime, name -> backend);
    }

    private Path region(final String world) throws IOException {
        final Path folder = this.dir.resolve(world).resolve("region");
        Files.createDirectories(folder);
        return folder;
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Opens, writes (resident bytes), shows the engine holds it, then runs {@code release}. */
    private Tracker unload(final AwfEngine engine, final Path folder, final boolean discard, final boolean expectCloseFailure)
        throws Exception {
        final AwfRegionStorage storage = engine.open(folder);
        assertNotNull(storage);
        storage.write(0, 0, bytes("resident chunk"));
        final Tracker tracker = Reachability.track("AWF storage of " + folder.getParent().getFileName(), storage);
        tracker.assertStillReachable();
        assertThrows(IOException.class, () -> engine.open(folder), "while open the engine holds the slot");
        if (discard) storage.discard();
        if (expectCloseFailure) {
            assertThrows(AwfRegionStorage.CloseFailure.class, storage::close);
        } else {
            storage.close();
        }
        return tracker;
    }

    @Test
    void closingAStorageFreesItsSlotItsStoreAndTheStorageItself() throws Exception {
        final Backend backend = new Backend();
        final AwfEngine engine = engine(backend, Set.of("w1"));
        final Path folder = region("w1");

        final Tracker first = unload(engine, folder, false, false);

        assertEquals(backend.opened.get(), backend.closed.get(), "the backend store was released");
        final AwfRegionStorage again = engine.open(folder);
        assertNotNull(again, "the folder can be opened again: no stale slot");
        first.assertCollected();
        again.close();
        assertEquals(backend.opened.get(), backend.closed.get());
    }

    @Test
    void anUnloadWithoutSavingStillFreesTheSlotAndKeepsTheLastCommit() throws Exception {
        final Backend backend = new Backend();
        final AwfEngine engine = engine(backend, Set.of("w2"));
        final Path folder = region("w2");

        final Tracker first = unload(engine, folder, true, false);

        final AwfRegionStorage again = engine.open(folder);
        assertNotNull(again);
        assertNull(again.read(0, 0), "the discarded write was not saved");
        first.assertCollected();
        again.close();
        assertEquals(backend.opened.get(), backend.closed.get());
    }

    @Test
    void aFinalCommitThatFailsStillFreesTheSlotSoTheWorldCanBeOpenedAgain() throws Exception {
        final Backend backend = new Backend();
        final AwfEngine engine = engine(backend, Set.of("w3"));
        final Path folder = region("w3");
        backend.failCommits = true;

        final Tracker first = unload(engine, folder, false, true);

        assertEquals(backend.opened.get(), backend.closed.get(), "the store is released even though the commit failed");
        backend.failCommits = false;
        final AwfRegionStorage again = engine.open(folder);
        assertNotNull(again);
        first.assertCollected();
        again.close();
    }

    @Test
    void severalWorldsAreReleasedIndependently() throws Exception {
        final Backend backend = new Backend();
        final AwfEngine engine = engine(backend, Set.of("a", "b"));
        final AwfRegionStorage a = engine.open(region("a"));
        final AwfRegionStorage b = engine.open(region("b"));
        a.close();
        assertThrows(IOException.class, () -> engine.open(region("b")), "b is still open");
        final AwfRegionStorage a2 = engine.open(region("a"));
        assertNotNull(a2);
        a2.close();
        b.close();
        assertEquals(backend.opened.get(), backend.closed.get());
    }

    @Test
    void deletingAWorldStopsTreatingItAsManaged() throws Exception {
        final Backend backend = new Backend();
        final AwfEngine engine = engine(backend, Set.of());
        final Path folder = region("gone");
        engine.manageWorld("gone", null, true);

        final AwfRegionStorage managed = engine.open(folder);
        assertNotNull(managed, "a managed world opens through AWF without being listed in the config");
        managed.close();

        engine.releaseWorld("gone");

        assertNull(engine.open(folder), "after release the world is plain region files again");
        assertEquals(backend.opened.get(), backend.closed.get());
        // Re-managing the same name afterwards starts clean: nothing left over from the first life.
        engine.manageWorld("gone", null, false);
        final AwfRegionStorage reborn = engine.open(folder);
        assertNotNull(reborn);
        reborn.close();
    }
}
