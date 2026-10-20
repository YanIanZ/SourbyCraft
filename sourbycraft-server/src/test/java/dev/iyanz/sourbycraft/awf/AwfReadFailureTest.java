package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Backend failure D1 (2026-10-13): a chunk whose stored bytes could not be read must never be
 * replaced in the store by a chunk the engine generated in its place.
 */
class AwfReadFailureTest {

    private static final ChunkKey KEY = new ChunkKey(106, 106);

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** An in-memory store whose reads fail while {@link #down} is set (Redis gone). */
    private static class FlakyStore implements AwfStore {
        final Map<ChunkKey, byte[]> chunks = new HashMap<>();
        final Set<ChunkKey> tombstones = new HashSet<>();
        final AtomicBoolean down = new AtomicBoolean();
        final AtomicInteger failuresLeft = new AtomicInteger(Integer.MAX_VALUE);
        final AtomicInteger readCalls = new AtomicInteger();
        long generation;
        final AtomicBoolean failRelease = new AtomicBoolean();

        @Override
        public void close() throws IOException {
            if (this.failRelease.get()) throw new java.net.ConnectException("Connection refused");
        }

        @Override
        public Optional<byte[]> read(final ChunkKey key) throws IOException {
            this.readCalls.incrementAndGet();
            if (this.down.get() && this.failuresLeft.getAndDecrement() > 0) {
                throw new java.net.ConnectException("Connection refused");
            }
            final byte[] bytes = this.chunks.get(key);
            return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
        }

        @Override
        public Set<ChunkKey> keys() {
            return new TreeSet<>(this.chunks.keySet());
        }

        @Override
        public boolean has(final ChunkKey key) {
            return this.chunks.containsKey(key) || this.tombstones.contains(key);
        }

        @Override
        public Set<ChunkKey> deleted() {
            return new TreeSet<>(this.tombstones);
        }

        @Override
        public long generation() {
            return this.generation;
        }

        @Override
        public synchronized CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                                final Set<ChunkKey> deleted, final PersistenceMode mode)
            throws IOException {
            changed.forEach((key, bytes) -> {
                this.chunks.put(key, bytes.clone());
                this.tombstones.remove(key);
            });
            deleted.forEach(key -> {
                this.chunks.remove(key);
                this.tombstones.add(key);
            });
            return new CommitResult(++this.generation, this.chunks.size(), changed.size(), 0, 0);
        }
    }

    private final FlakyStore store = new FlakyStore();

    @BeforeEach
    void noFailFastWindow() {
        AwfRegionStorage.resetFailFast();
    }

    private AwfRegionStorage storage() {
        // The marker chunk was committed and then evicted: it lives only in the store.
        this.store.chunks.put(KEY, b("stored with the marker block"));
        final AwfWorld world = new AwfWorld("world/region", WorldRole.VANILLA, null, this.store, 1);
        final AwfSettings settings = new AwfSettings(Set.of("world"), PersistenceMode.INCREMENTAL, 30, 1, 2, 2);
        return new AwfRegionStorage("world/region", world, settings, Runnable::run, 0L, () -> {});
    }

    @Test
    void aFailedReadIsRetriedAndATransientFailureIsInvisible() throws Exception {
        final AwfRegionStorage storage = storage();
        this.store.down.set(true);
        this.store.failuresLeft.set(AwfRegionStorage.READ_ATTEMPTS - 1);
        assertArrayEquals(b("stored with the marker block"), storage.read(KEY.x(), KEY.z()));
        assertEquals(AwfRegionStorage.READ_ATTEMPTS, this.store.readCalls.get());
        assertTrue(storage.unreadable().isEmpty());
        assertEquals(AwfRegionStorage.READ_ATTEMPTS - 1, storage.stats().readRetries());
    }

    @Test
    void aChunkThatCouldNotBeReadIsNeverReplacedByOneGeneratedInItsPlace() throws Exception {
        final AwfRegionStorage storage = storage();
        storage.loadPath(() -> true);
        this.store.down.set(true);

        final IOException failed = assertThrows(IOException.class, () -> storage.read(KEY.x(), KEY.z()),
            "a read failure is reported, never answered as \"no data\"");
        assertTrue(failed.getMessage().contains("stored chunk is kept"), failed.getMessage());
        assertEquals(AwfRegionStorage.READ_ATTEMPTS, this.store.readCalls.get(), "retried before giving up");
        assertEquals(Set.of(KEY), storage.unreadable());

        // The engine generated the chunk again and saves it; the backend comes back; commits run.
        storage.write(KEY.x(), KEY.z(), b("regenerated, no marker"));
        storage.write(KEY.x(), KEY.z(), null);
        storage.write(1, 1, b("an ordinary chunk"));
        this.store.down.set(false);
        storage.flush();
        assertArrayEquals(b("stored with the marker block"), this.store.chunks.get(KEY),
            "the stored chunk survives the commit");
        assertArrayEquals(b("an ordinary chunk"), this.store.chunks.get(new ChunkKey(1, 1)),
            "other chunks commit as usual");
        assertEquals(2, storage.stats().refusedWrites());
        assertEquals(1, storage.stats().unreadable());

        // The chunk unloads and loads again: the read now succeeds, the stored chunk is loaded, and
        // writes derived from it are committed again.
        assertArrayEquals(b("stored with the marker block"), storage.read(KEY.x(), KEY.z()));
        assertTrue(storage.unreadable().isEmpty());
        storage.write(KEY.x(), KEY.z(), b("stored with the marker block, edited"));
        storage.close();
        assertArrayEquals(b("stored with the marker block, edited"), this.store.chunks.get(KEY));
    }

    @Test
    void aSuccessfulReadOffTheLoadPathKeepsTheFence() throws Exception {
        final AwfRegionStorage storage = storage();
        storage.loadPath(() -> false);   // e.g. a structure scan while the regenerated chunk is loaded
        this.store.down.set(true);
        assertThrows(IOException.class, () -> storage.read(KEY.x(), KEY.z()));
        this.store.down.set(false);
        assertArrayEquals(b("stored with the marker block"), storage.read(KEY.x(), KEY.z()),
            "the stored bytes are returned");
        assertFalse(storage.unreadable().isEmpty(), "but the regenerated chunk may still be loaded");
        storage.write(KEY.x(), KEY.z(), b("regenerated, saved after the scan"));
        storage.close();
        assertArrayEquals(b("stored with the marker block"), this.store.chunks.get(KEY));
    }

    @Test
    void afterAReadGaveUpOtherReadsFailFastForAWhile() throws Exception {
        final AwfRegionStorage storage = storage();
        this.store.chunks.put(new ChunkKey(0, 0), b("another stored chunk"));
        this.store.down.set(true);
        assertThrows(IOException.class, () -> storage.read(KEY.x(), KEY.z()));
        this.store.readCalls.set(0);
        assertThrows(IOException.class, () -> storage.read(0, 0));
        assertEquals(1, this.store.readCalls.get(), "a down backend does not cost every queued read its retries");
        assertEquals(Set.of(KEY, new ChunkKey(0, 0)), storage.unreadable(), "but every failed position is fenced");
    }

    /** Backend failure D5: a close that committed everything but could not release is not a loss. */
    @Test
    void aCloseThatOnlyFailedToReleaseIsNotReportedAsLostChunks() throws Exception {
        final AwfRegionStorage storage = storage();
        storage.write(1, 1, b("committed at close"));
        this.store.failRelease.set(true);
        storage.close();
        assertTrue(storage.releaseFailed());
        assertArrayEquals(b("committed at close"), this.store.chunks.get(new ChunkKey(1, 1)));

        // A commit that fails at close is the case that loses chunks, and says how many.
        final AwfWorld world = new AwfWorld("w/region", WorldRole.VANILLA, null, new FlakyStore() {
            @Override
            public synchronized CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                                    final Set<ChunkKey> deleted, final PersistenceMode mode)
                throws IOException {
                throw new java.net.SocketTimeoutException("Read timed out");
            }
        }, 1);
        final AwfRegionStorage lost = new AwfRegionStorage("w/region", world,
            new AwfSettings(Set.of("w"), PersistenceMode.INCREMENTAL, 30, 1, 2, 2), Runnable::run, 0L, () -> {});
        lost.write(3, 3, b("x"));
        final AwfRegionStorage.CloseFailure failure = assertThrows(AwfRegionStorage.CloseFailure.class, lost::close);
        assertEquals(1, failure.uncommitted());
        assertFalse(lost.releaseFailed());
    }

    @Test
    void aReadThatFailsAfterCloseIsNotRetried() throws Exception {
        final AwfRegionStorage storage = storage();
        storage.close();
        this.store.down.set(true);
        assertThrows(IOException.class, () -> storage.read(KEY.x(), KEY.z()));
        assertEquals(1, this.store.readCalls.get(), "a stopping server does not wait on retries");
    }

    /**
     * TODO.md:58 (server-w1-redis-1, 2026-10-20): a forceload ticket loads its 27x27 chunk border
     * (ticket level 31, chunks loaded up to level 44), and with Redis down every stored one fails.
     * The log keeps the first failure of the window with its stack and one summary; every position
     * is still fenced and its writes refused, exactly as before.
     */
    @Test
    void aBurstOfFailedReadsLogsAtMostTwoRecordsAndFencesEveryPosition() throws Exception {
        final AwfRegionStorage storage = storage();
        storage.loadPath(() -> true);
        final java.util.List<String> logged = new java.util.ArrayList<>();
        final java.util.List<Throwable> stacks = new java.util.ArrayList<>();
        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(5L);
        storage.failureLog(new ReadFailureLog("world/region", ReadFailureLog.WINDOW_NANOS, clock::get,
            (message, stack) -> {
                logged.add(message);
                stacks.add(stack);
            }));
        final Set<ChunkKey> keys = new HashSet<>();
        for (int x = 87; x <= 113; x++) {
            for (int z = 87; z <= 113; z++) {
                final ChunkKey key = new ChunkKey(x, z);
                keys.add(key);
                this.store.chunks.put(key, b("stored " + key));
            }
        }
        this.store.down.set(true);
        final java.util.List<IOException> thrown = new java.util.ArrayList<>();
        for (final ChunkKey key : keys) {
            thrown.add(assertThrows(IOException.class, () -> storage.read(key.x(), key.z())));
        }
        assertEquals(729, thrown.size());
        assertEquals(1, logged.size(), "only the first failure is logged while the window is open");
        assertTrue(stacks.get(0) != null, "with its stack");

        // The first exception keeps its cause and stack; the repeats carry the same message, no stack.
        assertTrue(thrown.get(0).getCause() instanceof java.net.ConnectException);
        for (final IOException repeat : thrown.subList(1, thrown.size())) {
            assertTrue(repeat.getMessage().startsWith("AWF could not read chunk "), repeat.getMessage());
            assertTrue(repeat.getMessage().contains("stored chunk is kept"), repeat.getMessage());
            assertEquals(0, repeat.getStackTrace().length, "no stack trace for the chunk system to print again");
        }

        // Data safety is unchanged: every position is fenced and a regenerated chunk is never saved.
        assertEquals(keys, storage.unreadable());
        for (final ChunkKey key : keys) storage.write(key.x(), key.z(), b("regenerated"));
        assertEquals(729, storage.stats().refusedWrites());

        clock.addAndGet(ReadFailureLog.WINDOW_NANOS);
        storage.maintain(clock.get());
        assertEquals(2, logged.size(), "one summary once the window ends");
        assertTrue(logged.get(1).contains("729 time(s)"), logged.get(1));

        this.store.down.set(false);
        storage.close();
        for (final ChunkKey key : keys) {
            assertArrayEquals(b("stored " + key), this.store.chunks.get(key), "stored chunk kept: " + key);
        }
    }
}
