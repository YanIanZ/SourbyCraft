package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The contract {@code RegionFileStorage} relies on: which chunks AWF answers for, which it leaves
 * to the region file, what survives a flush, a close and a restart, and what does not.
 */
class AwfRegionStorageTest {

    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private static final Executor DIRECT = Runnable::run;

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private AwfSettings settings(final Set<String> worlds, final int resident) {
        return new AwfSettings(worlds, PersistenceMode.INCREMENTAL, 30, resident, 2, 2);
    }

    private AwfEngine engine(final Set<String> worlds) {
        return new AwfEngine(settings(worlds, 1024), DIRECT, this.clock::get);
    }

    private Path region(final String world) throws IOException {
        final Path folder = this.dir.resolve(world).resolve("region");
        Files.createDirectories(folder);
        return folder;
    }

    @Test
    void aWorldThatIsNotListedAndHasNoStoreIsLeftToRegionFiles() throws Exception {
        assertNull(engine(Set.of("other")).open(region("world")));
        assertNull(engine(Set.of()).open(region("world")));
        assertTrue(Files.notExists(AwfRegionStorage.storeFor(region("world"))), "nothing is created");
    }

    /** Runs {@code task} on a thread named like a region thread, where store I/O is refused. */
    private static <T> T onRegionThread(final java.util.concurrent.Callable<T> task) throws Exception {
        final java.util.concurrent.FutureTask<T> future = new java.util.concurrent.FutureTask<>(task);
        final Thread thread = new Thread(future, "Folia Region Scheduler Thread #9");
        thread.start();
        thread.join();
        try {
            return future.get();
        } catch (final java.util.concurrent.ExecutionException failed) {
            throw (Exception) failed.getCause();
        }
    }

    @Test
    void storesPreparedOffTheRegionThreadsLetAWorldOpenOnOne() throws Exception {
        final Path dimension = this.dir.resolve("isl");
        Files.createDirectories(dimension.resolve("region"));
        final AwfEngine unprepared = engine(Set.of("isl"));
        assertThrows(IllegalStateException.class, () -> onRegionThread(() -> unprepared.open(dimension.resolve("region"))),
            "the global tick is a region thread: opening a store there is refused");

        final AwfEngine engine = engine(Set.of("isl"));
        engine.prepareStores(dimension);
        final AwfRegionStorage storage = onRegionThread(() -> engine.open(dimension.resolve("region")));
        assertNotNull(storage);
        storage.write(0, 0, b("a"));
        storage.close();
        assertArrayEquals(b("a"), engine(Set.of("isl")).open(dimension.resolve("region")).read(0, 0),
            "the prepared store is the world's real store");
    }

    @Test
    void anInstanceReadsItsTemplateUntilItWritesAndNeverWritesTheTemplate() throws Exception {
        // The engine looks for templates at awf-templates/<t>/region.awf under the server
        // directory, which for a test run is the working directory; a unique name keeps runs apart.
        final String template = "sky-" + System.nanoTime();
        final Path templateStore = AwfRegionStorage.storeFor(AwfEngine.TEMPLATES.resolve(template).resolve("region"));
        try {
            AwfWorldStore.open(templateStore, WorldRole.VANILLA, 1).commit(
                java.util.Map.of(new ChunkKey(0, 0), b("t0"), new ChunkKey(1, 0), b("t1")), Set.of(),
                PersistenceMode.INCREMENTAL);
            final Path dimension = this.dir.resolve("inst");
            Files.createDirectories(dimension.resolve("region"));
            final AwfEngine engine = engine(Set.of());
            engine.manageWorld("inst", template);
            engine.prepareStores(dimension, template);

            final AwfRegionStorage storage = onRegionThread(() -> engine.open(dimension.resolve("region")));
            assertArrayEquals(b("t0"), storage.read(0, 0), "an untouched chunk comes from the template");
            storage.write(1, 0, b("mine"));
            storage.close();

            final AwfEngine restarted = engine(Set.of());
            restarted.manageWorld("inst", template);
            final AwfRegionStorage reopened = restarted.open(dimension.resolve("region"));
            assertArrayEquals(b("mine"), reopened.read(1, 0), "the instance keeps its own change");
            assertArrayEquals(b("t0"), reopened.read(0, 0));
            assertArrayEquals(b("t1"), AwfWorldStore.open(templateStore, WorldRole.READ_ONLY, 1)
                .read(new ChunkKey(1, 0)).orElseThrow(), "the template is never written");
        } finally {
            dev.iyanz.sourbycraft.awf.world.AuroraTemplates.deleteRecursively(templateStore.getParent());
            Files.deleteIfExists(AwfEngine.TEMPLATES.toAbsolutePath());
        }
    }

    @Test
    void aDiscardedStorageKeepsItsLastCommitAndWritesNothing() throws Exception {
        final Path folder = region("world");
        final AwfRegionStorage first = engine(Set.of("world")).open(folder);
        first.write(0, 0, b("committed"));
        first.flush();
        first.write(0, 0, b("dirty, never committed"));
        first.discard();
        first.write(0, 0, b("written during the unload"));
        first.write(1, 0, b("new chunk during the unload"));
        first.flush();
        first.close();

        final AwfRegionStorage reopened = engine(Set.of("world")).open(folder);
        assertArrayEquals(b("committed"), reopened.read(0, 0));
        assertNull(reopened.read(1, 0), "nothing written after the discard reached the store");
    }

    @Test
    void listingMatchesAWholeFolderNameNotAPrefix() throws Exception {
        assertNull(engine(Set.of("world")).open(region("world_nether")));
        assertNotNull(engine(Set.of("world_nether")).open(region("world_nether")));
    }

    @Test
    void untouchedChunksFallThroughToTheRegionFile() throws Exception {
        final AwfRegionStorage storage = engine(Set.of("world")).open(region("world"));
        assertNull(storage.read(0, 0), "never written: the region file decides");
        storage.write(0, 0, b("new"));
        assertArrayEquals(b("new"), storage.read(0, 0));
        assertNull(storage.read(1, 0));
        assertEquals(2, storage.stats().baseFallthroughs());
    }

    @Test
    void aDeletionShadowsTheRegionFileAcrossARestart() throws Exception {
        final Path folder = region("world");
        final AwfRegionStorage first = engine(Set.of("world")).open(folder);
        first.write(3, 4, null);
        assertSame(AwfRegionStorage.DELETED, first.read(3, 4));
        first.close();

        final AwfRegionStorage second = engine(Set.of("world")).open(folder);
        assertSame(AwfRegionStorage.DELETED, second.read(3, 4),
            "a deleted chunk must not reappear from the region file underneath");
    }

    @Test
    void flushMakesWritesDurable() throws Exception {
        final Path folder = region("world");
        final AwfEngine engine = engine(Set.of("world"));
        final AwfRegionStorage storage = engine.open(folder);
        storage.write(0, 0, b("a"));
        storage.flush();
        // Simulate a crash: no close, a fresh engine opens the same folder.
        final AwfRegionStorage reopened = engine(Set.of("world")).open(folder);
        assertArrayEquals(b("a"), reopened.read(0, 0));
    }

    @Test
    void writesSinceTheLastCommitAreLostOnACrash() throws Exception {
        // The documented durability window: this is the trade AWF makes, and the test keeps it
        // from being described as anything else.
        final Path folder = region("world");
        final AwfRegionStorage storage = engine(Set.of("world")).open(folder);
        storage.write(0, 0, b("committed"));
        storage.flush();
        storage.write(0, 0, b("uncommitted"));
        final AwfRegionStorage afterCrash = engine(Set.of("world")).open(folder);
        assertArrayEquals(b("committed"), afterCrash.read(0, 0));
    }

    @Test
    void maintainCommitsOnlyOnceTheIntervalHasPassed() throws Exception {
        final AwfEngine engine = engine(Set.of("world"));
        final AwfRegionStorage storage = engine.open(region("world"));
        assertEquals(0, engine.maintain(), "nothing dirty");
        storage.write(0, 0, b("a"));
        this.clock.addAndGet(29_000_000_000L);
        assertEquals(0, engine.maintain(), "29s < 30s");
        this.clock.addAndGet(1_000_000_000L);
        assertEquals(1, engine.maintain());
        assertEquals(0, storage.stats().world().dirtyChunks());
        this.clock.addAndGet(60_000_000_000L);
        assertEquals(0, engine.maintain(), "clean again: no empty commits");
    }

    @Test
    void aFailedLaneSubmissionKeepsTheChunksDirty() throws Exception {
        final AwfEngine engine = new AwfEngine(settings(Set.of("world"), 1024),
            task -> { throw new java.util.concurrent.RejectedExecutionException("full"); }, this.clock::get);
        final AwfRegionStorage storage = engine.open(region("world"));
        storage.write(0, 0, b("a"));
        this.clock.addAndGet(31_000_000_000L);
        engine.maintain();
        assertEquals(1, storage.stats().world().dirtyChunks());
        assertEquals(1, storage.stats().world().failures());
    }

    @Test
    void closeCommitsAndForgetsTheStorage() throws Exception {
        final Path folder = region("world");
        final AwfEngine engine = engine(Set.of("world"));
        final AwfRegionStorage storage = engine.open(folder);
        storage.write(0, 0, b("a"));
        storage.close();
        assertTrue(engine.storages().isEmpty());
        assertThrows(IOException.class, () -> storage.write(1, 1, b("late")));
        assertArrayEquals(b("a"), engine.open(folder).read(0, 0), "reopening after close is allowed");
    }

    @Test
    void theSameFolderCannotBeOpenedTwiceAtOnce() throws Exception {
        final AwfEngine engine = engine(Set.of("world"));
        engine.open(region("world"));
        assertThrows(IOException.class, () -> engine.open(region("world")),
            "two writers on one store would each commit an index missing the other's chunks");
    }

    @Test
    void anExistingStoreStaysAttachedWhenItsWorldIsUnlisted() throws Exception {
        final Path folder = region("world");
        final AwfRegionStorage storage = engine(Set.of("world")).open(folder);
        storage.write(0, 0, b("a"));
        storage.close();
        final AwfRegionStorage again = engine(Set.of()).open(folder);
        assertNotNull(again, "the region files under the store are older than what it holds");
        assertArrayEquals(b("a"), again.read(0, 0));
    }

    @Test
    void theResidentLimitBoundsMemoryAndEvictedChunksReadBack() throws Exception {
        final AwfEngine engine = new AwfEngine(settings(Set.of("world"), 3), DIRECT, this.clock::get);
        final AwfRegionStorage storage = engine.open(region("world"));
        for (int x = 0; x < 10; x++) storage.write(x, 0, b("chunk" + x));
        storage.flush();
        assertTrue(storage.stats().world().residentChunks() <= 3, "resident " + storage.stats().world().residentChunks());
        assertEquals(7, storage.stats().evicted());
        for (int x = 0; x < 10; x++) assertArrayEquals(b("chunk" + x), storage.read(x, 0));
    }

    @Test
    void aFlushOnARegionThreadDoesNotBlockOnDisk() throws Exception {
        final List<Runnable> queued = new ArrayList<>();
        final AwfEngine engine = new AwfEngine(settings(Set.of("world"), 1024), queued::add, this.clock::get);
        final AwfRegionStorage storage = engine.open(region("world"));
        storage.write(0, 0, b("a"));
        final Throwable[] failure = new Throwable[1];
        final Thread region = new Thread(() -> {
            try {
                storage.flush();
            } catch (final Throwable t) {
                failure[0] = t;
            }
        }, "Folia Region Scheduler Thread #0");
        region.start();
        region.join();
        assertNull(failure[0]);
        assertEquals(1, queued.size(), "the commit went to the storage lane");
        assertEquals(1, storage.stats().world().dirtyChunks(), "and has not run yet");
        queued.get(0).run();
        assertEquals(0, storage.stats().world().dirtyChunks());
    }

    @Test
    void anOversizedChunkIsRefusedWithoutTouchingTheWorld() throws Exception {
        final AwfRegionStorage storage = engine(Set.of("world")).open(region("world"));
        assertThrows(IOException.class, () -> storage.write(0, 0, new byte[AwfFile.MAX_CHUNK_BYTES + 1]));
        assertNull(storage.read(0, 0));
    }

    /** A region file stand-in: what an export wrote, and whether it was flushed. */
    private static final class RecordingSink implements AwfEngine.RegionSink {
        final java.util.Map<ChunkKey, byte[]> written = new java.util.HashMap<>();
        final java.util.Set<ChunkKey> deleted = new java.util.HashSet<>();
        boolean flushed;
        int failAfter = Integer.MAX_VALUE;

        @Override
        public void write(final int chunkX, final int chunkZ, final byte[] nbt) throws IOException {
            if (this.written.size() + this.deleted.size() >= this.failAfter) throw new IOException("disk full");
            if (nbt == null) this.deleted.add(new ChunkKey(chunkX, chunkZ));
            else this.written.put(new ChunkKey(chunkX, chunkZ), nbt);
        }

        @Override
        public void flush() {
            this.flushed = true;
        }
    }

    private AwfEngine exporting(final Set<String> worlds, final Set<String> export) {
        return new AwfEngine(new AwfSettings(worlds, export, PersistenceMode.INCREMENTAL, 30, 1024, 2, 2),
            DIRECT, this.clock::get);
    }

    @Test
    void exportWritesChunksAndDeletionsBackAndMovesTheStoreAside() throws Exception {
        final Path folder = region("world");
        final AwfRegionStorage storage = engine(Set.of("world")).open(folder);
        storage.write(0, 0, b("a"));
        storage.write(1, 0, b("b"));
        storage.write(2, 0, null);
        storage.close();

        final RecordingSink sink = new RecordingSink();
        assertNull(exporting(Set.of(), Set.of("world")).open(folder, sink), "region files are authoritative again");
        assertEquals(2, sink.written.size());
        assertArrayEquals(b("a"), sink.written.get(new ChunkKey(0, 0)));
        assertEquals(Set.of(new ChunkKey(2, 0)), sink.deleted);
        assertTrue(sink.flushed);
        assertTrue(Files.notExists(AwfRegionStorage.storeFor(folder)));
        try (var siblings = Files.list(folder.getParent())) {
            assertTrue(siblings.anyMatch(p -> p.getFileName().toString().startsWith("region.awf.exported-")),
                "the store is kept as a backup, not deleted");
        }
        assertNull(engine(Set.of()).open(folder), "the next load no longer attaches AWF");
    }

    @Test
    void aFailedExportLeavesTheStoreInPlaceForTheNextLoad() throws Exception {
        final Path folder = region("world");
        final AwfRegionStorage storage = engine(Set.of("world")).open(folder);
        for (int x = 0; x < 5; x++) storage.write(x, 0, b("c" + x));
        storage.close();

        final RecordingSink failing = new RecordingSink();
        failing.failAfter = 2;
        assertThrows(IOException.class, () -> exporting(Set.of(), Set.of("world")).open(folder, failing),
            "the world load fails rather than running on half-exported region files");
        assertTrue(Files.isDirectory(AwfRegionStorage.storeFor(folder)));

        final RecordingSink retry = new RecordingSink();
        exporting(Set.of(), Set.of("world")).open(folder, retry);
        assertEquals(5, retry.written.size(), "the export starts over and completes");
    }

    @Test
    void aWorldInBothListsStaysInAwf() throws Exception {
        final Path folder = region("world");
        final AwfRegionStorage storage = engine(Set.of("world")).open(folder);
        storage.write(0, 0, b("a"));
        storage.close();
        final RecordingSink sink = new RecordingSink();
        assertNotNull(exporting(Set.of("world"), Set.of("world")).open(folder, sink));
        assertTrue(sink.written.isEmpty());
    }

    @Test
    void exportWithoutASinkRefusesToLoad() throws Exception {
        final Path folder = region("world");
        final AwfRegionStorage storage = engine(Set.of("world")).open(folder);
        storage.write(0, 0, b("a"));
        storage.close();
        assertThrows(IOException.class, () -> exporting(Set.of(), Set.of("world")).open(folder, null));
    }

    @Test
    void exportOfAWorldWithNoStoreDoesNothing() throws Exception {
        final RecordingSink sink = new RecordingSink();
        assertNull(exporting(Set.of(), Set.of("world")).open(region("world"), sink));
        assertTrue(sink.written.isEmpty() && !sink.flushed);
    }

    @Test
    void closeEveryCommitsAllOpenStoragesAtShutdown() throws Exception {
        final AwfEngine engine = engine(Set.of("a", "b"));
        final AwfRegionStorage a = engine.open(region("a"));
        final AwfRegionStorage bStorage = engine.open(region("b"));
        a.write(0, 0, b("a"));
        bStorage.write(0, 0, b("b"));
        assertEquals(0, engine.closeEvery());
        assertTrue(engine.storages().isEmpty());
        assertTrue(a.closed() && bStorage.closed());
        assertArrayEquals(b("a"), engine(Set.of("a")).open(region("a")).read(0, 0));
        assertArrayEquals(b("b"), engine(Set.of("b")).open(region("b")).read(0, 0));
    }
}
