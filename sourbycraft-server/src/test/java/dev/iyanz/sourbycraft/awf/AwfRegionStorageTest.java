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
}
