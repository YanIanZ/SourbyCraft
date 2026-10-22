package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two commits of one world must reach the store in the order they took their snapshots.
 * A periodic commit that snapshotted a chunk, then lost the race for the store to a flush that
 * snapshotted the chunk's newer bytes, used to land last and leave the store holding the older
 * bytes while the chunk was no longer dirty.
 */
class AwfCommitOrderTest {

    @TempDir Path dir;

    /** Holds the first commit inside the store until released. */
    private static final class GatedStore implements AwfStore {
        final AwfStore real;
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        GatedStore(final AwfStore real) { this.real = real; }

        @Override public Optional<byte[]> read(final ChunkKey key) throws IOException { return this.real.read(key); }
        @Override public Set<ChunkKey> keys() { return this.real.keys(); }
        @Override public boolean has(final ChunkKey key) { return this.real.has(key); }
        @Override public Set<ChunkKey> deleted() { return this.real.deleted(); }
        @Override public long generation() throws IOException { return this.real.generation(); }

        @Override
        public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                   final Set<ChunkKey> deleted, final PersistenceMode mode) throws IOException {
            if (this.calls.getAndIncrement() == 0) {
                this.firstEntered.countDown();
                try {
                    this.release.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return this.real.commit(changed, removed, deleted, mode);
        }
    }

    @Test
    void aLaterSnapshotIsNeverOverwrittenByAnEarlierOne() throws Exception {
        final GatedStore store = new GatedStore(AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2));
        final AwfWorld world = new AwfWorld("w", WorldRole.VANILLA, null, store);
        final ChunkKey key = new ChunkKey(3, 4);
        final byte[] older = "older".getBytes(StandardCharsets.UTF_8);
        final byte[] newer = "newer".getBytes(StandardCharsets.UTF_8);

        world.write(key, older);
        final Thread periodic = new Thread(() -> {
            try { world.saveNow(PersistenceMode.INCREMENTAL, 1); } catch (final IOException e) { throw new RuntimeException(e); }
        });
        periodic.start();
        assertTrue(store.firstEntered.await(5, TimeUnit.SECONDS), "first commit should reach the store");

        world.write(key, newer);
        final Thread flush = new Thread(() -> {
            try { world.saveNow(PersistenceMode.INCREMENTAL, 1); } catch (final IOException e) { throw new RuntimeException(e); }
        });
        flush.start();
        flush.join(2000);               // finishes here only if commits are not ordered
        store.release.countDown();
        periodic.join(10_000);
        flush.join(10_000);

        assertArrayEquals(newer, store.real.read(key).orElseThrow(),
            "the store must hold the bytes of the later snapshot");
    }
}
