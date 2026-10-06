package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Close and discard against writes and commits that are in flight: a write admitted before close
 * is committed by it, a commit queued before a discard commits nothing, and a store is never
 * closed under a running commit.
 */
class AwfLifecycleFenceTest {

    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private AwfEngine engine(final java.util.concurrent.Executor lane) {
        return new AwfEngine(new AwfSettings(Set.of("world"), PersistenceMode.INCREMENTAL, 30, 1024, 2, 2), lane,
            this.clock::get);
    }

    private Path region() throws IOException {
        final Path folder = this.dir.resolve("world").resolve("region");
        Files.createDirectories(folder);
        return folder;
    }

    private static void awaitTrue(final java.util.function.BooleanSupplier condition, final String what)
        throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting: " + what);
            Thread.sleep(1);
        }
    }

    @Test
    void aWriteAdmittedJustBeforeCloseIsCommittedByIt() throws Exception {
        final Path folder = region();
        final AwfRegionStorage storage = engine(Runnable::run).open(folder);
        final CountDownLatch admitted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
        storage.admittedHook(() -> {
            if (!first.getAndSet(false)) return;
            admitted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        final AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        final Thread writer = new Thread(() -> {
            try {
                storage.write(0, 0, b("written as close began"));
            } catch (final Throwable failed) {
                writeFailure.set(failed);
            }
        });
        writer.start();
        assertTrue(admitted.await(10, TimeUnit.SECONDS), "the write passed the closed check");

        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        final Thread closer = new Thread(() -> {
            try {
                storage.close();
            } catch (final Throwable failed) {
                closeFailure.set(failed);
            }
        });
        closer.start();
        // Deterministic: close is parked on the transition lock behind the admitted write.
        awaitTrue(storage::transitionWaiting, "close waits for the admitted write");
        assertFalse(storage.closed(), "close has not flipped its state under an admitted write");
        release.countDown();
        writer.join(10_000);
        closer.join(10_000);
        assertNull(writeFailure.get());
        assertNull(closeFailure.get());

        assertThrows(IOException.class, () -> storage.write(1, 0, b("after close")),
            "a write after close fails loudly instead of vanishing");
        final AwfRegionStorage reopened = engine(Runnable::run).open(folder);
        assertArrayEquals(b("written as close began"), reopened.read(0, 0),
            "the admitted write was in the world when close took its final snapshot");
        assertNull(reopened.read(1, 0));
    }

    @Test
    void aCommitQueuedBeforeADiscardCommitsNothingWhenItRunsAfterIt() throws Exception {
        final Path folder = region();
        final List<Runnable> queued = new ArrayList<>();
        final AwfEngine engine = engine(queued::add);
        final AwfRegionStorage storage = engine.open(folder);
        storage.write(0, 0, b("committed"));
        this.clock.addAndGet(31_000_000_000L);
        assertEquals(1, engine.maintain());
        queued.remove(0).run();                            // the first periodic commit lands
        final long before = AwfWorldStore.open(AwfRegionStorage.storeFor(folder), WorldRole.READ_ONLY, 2).generation();

        storage.write(0, 0, b("dirty when the world was unloaded without saving"));
        storage.write(1, 0, b("new chunk, never saved"));
        this.clock.addAndGet(31_000_000_000L);
        assertEquals(1, engine.maintain(), "a periodic commit is queued on the storage lane");
        assertEquals(1, queued.size());
        assertEquals(1, before, "only the first commit has landed");

        storage.discard();
        // The lane gets to it only now, while the store is still open: only the world's fence
        // stands between this commit and the disk.
        queued.get(0).run();
        assertEquals(1, AwfWorldStore.open(AwfRegionStorage.storeFor(folder), WorldRole.READ_ONLY, 2).generation());
        storage.close();

        final AwfWorldStore store = AwfWorldStore.open(AwfRegionStorage.storeFor(folder), WorldRole.READ_ONLY, 2);
        assertEquals(before, store.generation(), "no generation was committed after the discard");
        final AwfRegionStorage reopened = engine(Runnable::run).open(folder);
        assertArrayEquals(b("committed"), reopened.read(0, 0));
        assertNull(reopened.read(1, 0));
    }

    @Test
    void aFencedWorldRefusesQueuedCommitsWithoutTouchingTheStore() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir.resolve("w"), WorldRole.VANILLA, 2);
        final AwfWorld world = new AwfWorld("w", WorldRole.VANILLA, null, store);
        world.write(new ChunkKey(0, 0), b("queued"));
        final List<Runnable> queued = new ArrayList<>();
        final CompletableFuture<AwfStore.CommitResult> save =
            world.save(PersistenceMode.INCREMENTAL, queued::add, 2).toCompletableFuture();
        world.fence();
        queued.forEach(Runnable::run);

        final ExecutionException failed = assertThrows(ExecutionException.class, save::get);
        assertInstanceOf(AwfWorld.FencedException.class, failed.getCause());
        assertEquals(0, store.generation(), "the fenced commit reached nothing");
        assertEquals(1, world.dirtyCount(), "nothing pretended to be committed");
        assertEquals(0, world.pendingSaveCount());
        assertThrows(AwfWorld.FencedException.class, () -> world.saveNow(PersistenceMode.INCREMENTAL, 1));
    }

    @Test
    void aClosedFileStoreRefusesCommits() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir.resolve("w"), WorldRole.VANILLA, 2);
        store.commit(Map.of(new ChunkKey(0, 0), b("a")), Set.of(), PersistenceMode.INCREMENTAL);
        store.close();
        assertThrows(IllegalStateException.class,
            () -> store.commit(Map.of(new ChunkKey(0, 0), b("b")), Set.of(), PersistenceMode.INCREMENTAL));
        assertArrayEquals(b("a"), store.read(new ChunkKey(0, 0)).orElseThrow(), "reads still work");
        assertEquals(1, AwfWorldStore.open(this.dir.resolve("w"), WorldRole.READ_ONLY, 2).generation());
    }

    /** Holds the first commit inside the store and records whether close came while it ran. */
    private static final class GatedStore implements AwfStore {
        final AwfStore real;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger committing = new AtomicInteger();
        final AtomicBoolean closedDuringCommit = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();

        GatedStore(final AwfStore real) { this.real = real; }

        @Override public Optional<byte[]> read(final ChunkKey key) throws IOException { return this.real.read(key); }
        @Override public Set<ChunkKey> keys() { return this.real.keys(); }
        @Override public boolean has(final ChunkKey key) { return this.real.has(key); }
        @Override public Set<ChunkKey> deleted() { return this.real.deleted(); }
        @Override public long generation() throws IOException { return this.real.generation(); }

        @Override
        public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                   final Set<ChunkKey> deleted, final PersistenceMode mode) throws IOException {
            this.committing.incrementAndGet();
            try {
                this.entered.countDown();
                try {
                    this.release.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return this.real.commit(changed, removed, deleted, mode);
            } finally {
                this.committing.decrementAndGet();
            }
        }

        @Override
        public void close() throws IOException {
            if (this.committing.get() > 0) this.closedDuringCommit.set(true);
            this.closed.set(true);
            this.real.close();
        }
    }

    @Test
    void aRunningCommitCompletesAndTheStoreClosesOnlyAfterIt() throws Exception {
        final GatedStore store = new GatedStore(AwfWorldStore.open(this.dir.resolve("w"), WorldRole.VANILLA, 2));
        final AwfWorld world = new AwfWorld("w", WorldRole.VANILLA, null, store);
        world.write(new ChunkKey(0, 0), b("in flight"));
        final AtomicReference<Throwable> commitFailure = new AtomicReference<>();
        final Thread committer = new Thread(() -> {
            try {
                world.saveNow(PersistenceMode.INCREMENTAL, 1);
            } catch (final Throwable failed) {
                commitFailure.set(failed);
            }
        });
        committer.start();
        assertTrue(store.entered.await(10, TimeUnit.SECONDS), "the commit is inside the store");

        world.fence();                                     // discard: never blocks
        final Thread closer = new Thread(() -> {
            try {
                world.closeStore();
            } catch (final IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        });
        closer.start();
        closer.join(500);                                  // a close that does not wait finishes here
        store.release.countDown();
        committer.join(10_000);
        closer.join(10_000);

        assertNull(commitFailure.get(), "a commit that started before the fence completes");
        assertTrue(store.closed.get());
        assertFalse(store.closedDuringCommit.get(), "the store was never closed under the running commit");
        assertEquals(1, store.real.generation());
        assertThrows(AwfWorld.FencedException.class, () -> world.saveNow(PersistenceMode.INCREMENTAL, 1),
            "nothing commits after close");
    }
}
