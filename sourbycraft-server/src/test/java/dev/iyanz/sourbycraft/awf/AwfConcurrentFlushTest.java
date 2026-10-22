package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.execution.ExecutionLane;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * DEV-G region-file write concurrency, the "many regions flush at once" case: every region thread
 * of a world calls {@link AwfRegionStorage#flush} in the same instant (a {@code save-all} fans out
 * per region), while a foreground thread flushes too. Region-thread flushes only start a commit on
 * the storage lane; a foreground flush commits on its own thread. Both reach {@code AwfWorld}'s
 * commit lock, so the invariants are: no write is lost, the store never regresses a chunk to older
 * bytes, commits never overlap inside the store, and a foreground flush returns only once every
 * write that preceded it is in the store.
 */
class AwfConcurrentFlushTest {

    private static final int REGIONS = 8;
    private static final int ROUNDS = 40;
    private static final long WAIT_SECONDS = 60;

    /** Bytes of chunk {@code i} at write {@code seq}: the store reads the sequence back to check order. */
    private static byte[] bytes(final int i, final int seq) {
        return ("c" + i + "#" + seq).getBytes(StandardCharsets.UTF_8);
    }

    private static int seqOf(final byte[] bytes) {
        final String s = new String(bytes, StandardCharsets.UTF_8);
        return Integer.parseInt(s.substring(s.indexOf('#') + 1));
    }

    /** In-memory store that checks each commit against what it already holds. */
    private static final class OrderedStore implements AwfStore {
        final Map<ChunkKey, byte[]> chunks = new ConcurrentHashMap<>();
        final Set<ChunkKey> tombstones = ConcurrentHashMap.newKeySet();
        final List<String> violations = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger emptyCommits = new AtomicInteger();
        final AtomicBoolean inCommit = new AtomicBoolean();
        final AtomicLong generation = new AtomicLong();

        @Override public Optional<byte[]> read(final ChunkKey key) {
            final byte[] b = this.chunks.get(key);
            return b == null ? Optional.empty() : Optional.of(b.clone());
        }
        @Override public Set<ChunkKey> keys() { return new TreeSet<>(this.chunks.keySet()); }
        @Override public boolean has(final ChunkKey key) { return this.chunks.containsKey(key) || this.tombstones.contains(key); }
        @Override public Set<ChunkKey> deleted() { return new TreeSet<>(this.tombstones); }
        @Override public long generation() { return this.generation.get(); }
        @Override public void close() {}

        @Override
        public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                   final Set<ChunkKey> deleted, final PersistenceMode mode) throws IOException {
            if (!this.inCommit.compareAndSet(false, true)) {
                this.violations.add("two commits inside the store at once");
            }
            try {
                this.commits.incrementAndGet();
                if (changed.isEmpty() && deleted.isEmpty()) this.emptyCommits.incrementAndGet();
                long written = 0;
                for (final Map.Entry<ChunkKey, byte[]> e : changed.entrySet()) {
                    final byte[] previous = this.chunks.get(e.getKey());
                    if (previous != null && seqOf(previous) > seqOf(e.getValue())) {
                        this.violations.add(e.getKey() + ": seq " + seqOf(e.getValue()) + " committed over " + seqOf(previous));
                    }
                    this.chunks.put(e.getKey(), e.getValue().clone());
                    written += e.getValue().length;
                }
                for (final ChunkKey key : deleted) {
                    this.chunks.remove(key);
                    this.tombstones.add(key);
                }
                return new CommitResult(this.generation.incrementAndGet(), changed.size() + deleted.size(),
                    changed.size(), written, deleted.size());
            } finally {
                this.inCommit.set(false);
            }
        }
    }

    private static AwfRegionStorage storage(final OrderedStore store, final ExecutorService lane) {
        final AwfWorld world = new AwfWorld("world/region", WorldRole.VANILLA, null, store);
        final AwfSettings settings = new AwfSettings(Set.of("world"), PersistenceMode.INCREMENTAL, 30, 1024, 2, 2);
        return new AwfRegionStorage("world/region", world, settings, lane, 0L, () -> {});
    }

    private static ExecutorService lane() {
        return Executors.newSingleThreadExecutor(r -> new Thread(r, "AWF storage lane (test)"));
    }

    private static Thread regionThread(final int i, final Runnable body, final AtomicReference<Throwable> failure) {
        final Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (final Throwable failed) {
                failure.compareAndSet(null, failed);
            }
        }, "Region Scheduler Thread #" + i);
        assertEquals(ExecutionLane.REGION_TICK, ExecutionLane.of(t.getName()), "the writers must look like region threads");
        return t;
    }

    private static void drain(final ExecutorService lane) throws InterruptedException {
        lane.shutdown();
        assertTrue(lane.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "the storage lane drained");
    }

    @Test
    void flushesFromEveryRegionAtOnceLoseNoWriteAndNeverRegressTheStore() throws Exception {
        final OrderedStore store = new OrderedStore();
        final ExecutorService lane = lane();
        final AwfRegionStorage storage = storage(store, lane);
        final AwfWorld world = worldOf(storage);
        final CyclicBarrier flushTogether = new CyclicBarrier(REGIONS);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final List<Thread> regions = new ArrayList<>();
        for (int i = 0; i < REGIONS; i++) {
            final int region = i;
            regions.add(regionThread(region, () -> {
                try {
                    for (int round = 0; round < ROUNDS; round++) {
                        storage.write(region, 0, bytes(region, round));
                        storage.write(region, 1, bytes(REGIONS + region, round));
                        flushTogether.await(WAIT_SECONDS, TimeUnit.SECONDS);
                        storage.flush();
                    }
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            }, failure));
        }
        regions.forEach(Thread::start);
        for (final Thread t : regions) t.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        for (final Thread t : regions) assertTrue(!t.isAlive(), "region thread finished: " + t.getName());
        assertNull(failure.get(), () -> "a region thread failed: " + failure.get());
        drain(lane);

        assertEquals(List.of(), store.violations);
        assertEquals(0, world.pendingSaveCount(), "every started commit completed");
        for (int i = 0; i < REGIONS; i++) {
            assertSeq(store, new ChunkKey(i, 0), ROUNDS - 1);
            assertSeq(store, new ChunkKey(i, 1), ROUNDS - 1);
        }
        assertEquals(0, world.dirtyCount(), "the region flushes' commits left nothing dirty");
        assertTrue(store.commits.get() >= 1 && store.commits.get() <= REGIONS * ROUNDS,
            "commits bounded by flush calls: " + store.commits.get());
        // Flushes racing on the "is a save pending" check may queue a second save; it must find
        // nothing dirty and never reach the store as an empty generation.
        assertEquals(0, store.emptyCommits.get(), "no empty commit reached the store");
    }

    @Test
    void anIncrementalCommitWithNothingDirtySkipsTheStore() throws Exception {
        final OrderedStore store = new OrderedStore();
        final AwfWorld world = new AwfWorld("world/region", WorldRole.VANILLA, null, store);
        final AwfStore.CommitResult nothing = world.saveNow(PersistenceMode.INCREMENTAL, 1);
        assertEquals(0, store.commits.get(), "an empty incremental snapshot is not a store commit");
        assertEquals(0, nothing.chunks());
        assertEquals(0, nothing.objectsWritten());
        // A FULL commit rewrites the unchanged chunks too, so it still reaches the store with nothing dirty.
        world.saveNow(PersistenceMode.FULL, 1);
        assertEquals(1, store.commits.get(), "a FULL commit is the store's to run even with nothing dirty");
    }

    @Test
    void aForegroundFlushReturnsOnlyOnceEveryEarlierWriteIsInTheStore() throws Exception {
        final OrderedStore store = new OrderedStore();
        final ExecutorService lane = lane();
        final AwfRegionStorage storage = storage(store, lane);
        final AwfWorld world = worldOf(storage);
        final AtomicInteger[] lastWritten = new AtomicInteger[REGIONS];
        for (int i = 0; i < REGIONS; i++) lastWritten[i] = new AtomicInteger(-1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final List<Thread> regions = new ArrayList<>();
        for (int i = 0; i < REGIONS; i++) {
            final int region = i;
            regions.add(regionThread(region, () -> {
                try {
                    for (int round = 0; round < ROUNDS; round++) {
                        storage.write(region, 0, bytes(region, round));
                        lastWritten[region].set(round);
                        if (round % 4 == 3) storage.flush();   // region flushes interleave with the foreground ones
                    }
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            }, failure));
        }
        assertEquals(ExecutionLane.OTHER, ExecutionLane.of(Thread.currentThread().getName()),
            "the foreground flush must run off the region lane: " + Thread.currentThread().getName());
        regions.forEach(Thread::start);
        boolean someoneAlive = true;
        int foregroundFlushes = 0;
        while (someoneAlive) {
            final int[] seen = new int[REGIONS];
            for (int i = 0; i < REGIONS; i++) seen[i] = lastWritten[i].get();
            storage.flush();                                   // synchronous on this thread
            foregroundFlushes++;
            for (int i = 0; i < REGIONS; i++) {
                if (seen[i] < 0) continue;
                final byte[] stored = store.chunks.get(new ChunkKey(i, 0));
                assertNotNull(stored, "chunk " + i + " written before the flush is in the store");
                assertTrue(seqOf(stored) >= seen[i], "chunk " + i + ": store has seq " + seqOf(stored)
                    + " after a flush that followed write " + seen[i]);
            }
            someoneAlive = false;
            for (final Thread t : regions) someoneAlive |= t.isAlive();
        }
        for (final Thread t : regions) t.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertNull(failure.get(), () -> "a region thread failed: " + failure.get());
        drain(lane);
        storage.flush();

        assertEquals(List.of(), store.violations);
        assertEquals(0, world.dirtyCount());
        for (int i = 0; i < REGIONS; i++) assertSeq(store, new ChunkKey(i, 0), ROUNDS - 1);
        assertTrue(foregroundFlushes >= 1);
    }

    private static void assertSeq(final OrderedStore store, final ChunkKey key, final int seq) {
        final byte[] stored = store.chunks.get(key);
        assertNotNull(stored, key + " reached the store");
        assertEquals(seq, seqOf(stored), key + " holds its last write");
    }

    private static AwfWorld worldOf(final AwfRegionStorage storage) throws Exception {
        final java.lang.reflect.Field f = AwfRegionStorage.class.getDeclaredField("world");
        f.setAccessible(true);
        return (AwfWorld) f.get(storage);
    }
}
