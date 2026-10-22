package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code aurora.awf.commit-dirty-bytes}: a commit may start before the interval once the unsaved
 * chunks reach a byte threshold. Off at 0 (the default), where only the interval applies. Clocks and
 * executors are injected; nothing sleeps.
 */
class AwfDirtyBytesCommitTest {

    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private static final Executor DIRECT = Runnable::run;

    private static byte[] bytes(final int length) {
        return new byte[length];
    }

    private AwfEngine engine(final int dirtyBytes) {
        final AwfSettings settings = new AwfSettings(Set.of("world"), Set.of(), "file", PersistenceMode.INCREMENTAL,
            30, 1024, 2, 2, false, dirtyBytes);
        return new AwfEngine(settings, DIRECT, this.clock::get);
    }

    private Path region() throws IOException {
        final Path folder = this.dir.resolve("world").resolve("region");
        Files.createDirectories(folder);
        return folder;
    }

    @Test
    void offByDefaultTheIntervalAloneDecides() throws Exception {
        assertEquals(0, AwfSettings.DEFAULT.commitDirtyBytes());
        final AwfEngine engine = engine(0);
        final AwfRegionStorage storage = engine.open(region());
        // A first commit pins the interval clock, so the next write waits for it.
        storage.write(0, 0, bytes(1));
        this.clock.addAndGet(31_000_000_000L);
        assertEquals(1, engine.maintain());
        storage.write(1, 0, bytes(10_000_000));
        this.clock.addAndGet(1_000_000_000L);
        assertEquals(0, engine.maintain(), "10 MB dirty but no byte trigger and 1 s < 30 s");
        assertEquals(1, storage.stats().world().dirtyChunks());
    }

    @Test
    void reachingTheThresholdCommitsBeforeTheInterval() throws Exception {
        final AwfEngine engine = engine(1000);
        final AwfRegionStorage storage = engine.open(region());
        storage.write(0, 0, bytes(1));
        this.clock.addAndGet(31_000_000_000L);
        assertEquals(1, engine.maintain(), "the interval commit still works");

        storage.write(1, 0, bytes(600));
        this.clock.addAndGet(1_000_000_000L);
        assertEquals(0, engine.maintain(), "600 < 1000 bytes and 1 s < 30 s");
        storage.write(2, 0, bytes(400));
        this.clock.addAndGet(1_000_000_000L);
        assertEquals(1, engine.maintain(), "600 + 400 reaches the threshold, 2 s into the interval");
        assertEquals(0, storage.stats().world().dirtyChunks(), "the chunks were committed");
    }

    @Test
    void aRewrittenChunkCountsItsCurrentBytesOnlyOnce() throws Exception {
        final AwfEngine engine = engine(1000);
        final AwfRegionStorage storage = engine.open(region());
        storage.write(0, 0, bytes(1));
        this.clock.addAndGet(31_000_000_000L);
        engine.maintain();

        for (int i = 0; i < 5; i++) storage.write(1, 0, bytes(600));
        this.clock.addAndGet(1_000_000_000L);
        assertEquals(0, engine.maintain(), "one dirty chunk of 600 bytes, however often it was rewritten");
    }

    @Test
    void aDeletionAddsNoBytesAndTheSizeCheckStopsAtTheLimit() throws Exception {
        final AwfEngine engine = engine(10);
        final AwfRegionStorage storage = engine.open(region());
        storage.write(0, 0, bytes(1));
        this.clock.addAndGet(31_000_000_000L);
        engine.maintain();

        storage.write(1, 0, null);
        this.clock.addAndGet(1_000_000_000L);
        assertEquals(0, engine.maintain(), "a deletion is dirty but holds no bytes");
        assertEquals(1, storage.stats().world().dirtyChunks());
    }

    @Test
    void theByteTriggerStillWaitsForACommitInFlight() throws Exception {
        // A lane that never runs the commit: it stays pending, so no second one may be queued.
        final java.util.List<Runnable> queued = new java.util.ArrayList<>();
        final AwfSettings settings = new AwfSettings(Set.of("world"), Set.of(), "file", PersistenceMode.INCREMENTAL,
            30, 1024, 2, 2, false, 100);
        final AwfEngine engine = new AwfEngine(settings, queued::add, this.clock::get);
        final AwfRegionStorage storage = engine.open(region());
        storage.write(0, 0, bytes(500));
        this.clock.addAndGet(1_000_000_000L);
        assertEquals(1, engine.maintain(), "500 >= 100 bytes: a commit is queued");
        storage.write(1, 0, bytes(500));
        this.clock.addAndGet(1_000_000_000L);
        assertEquals(0, engine.maintain(), "the first commit has not run: nothing piles up behind it");
        assertEquals(1, queued.size());
    }

    @Test
    void dirtyBytesAtLeastCountsOwnedDirtyBytes() throws Exception {
        final AwfRegionStorage storage = engine(0).open(region());
        storage.write(0, 0, bytes(30));
        storage.write(1, 0, bytes(70));
        // The world is reached through stats' snapshot count only; the byte check is on the world.
        assertEquals(2, storage.stats().world().dirtyChunks());
        assertTrue(storageWorld(storage).dirtyBytesAtLeast(100));
        assertFalse(storageWorld(storage).dirtyBytesAtLeast(101));
        assertTrue(storageWorld(storage).dirtyBytesAtLeast(0), "a zero limit is always met");
    }

    private static AwfWorld storageWorld(final AwfRegionStorage storage) throws Exception {
        final java.lang.reflect.Field field = AwfRegionStorage.class.getDeclaredField("world");
        field.setAccessible(true);
        return (AwfWorld) field.get(storage);
    }

    @Test
    void settingsParseTheKeyAndRefuseNegativeOrWrongValues() {
        assertEquals(4096, AwfSettings.parse(Map.of(AwfSettings.COMMIT_DIRTY_BYTES_KEY, 4096)).settings()
            .commitDirtyBytes());
        for (final Object wrong : List.of(-1, "100", 1.5, 3_000_000_000L)) {
            final AwfSettings.Parsed parsed = AwfSettings.parse(Map.of(AwfSettings.COMMIT_DIRTY_BYTES_KEY, wrong));
            assertEquals(List.of(AwfSettings.COMMIT_DIRTY_BYTES_KEY), parsed.invalidKeys(), String.valueOf(wrong));
            assertEquals(0, parsed.settings().commitDirtyBytes(), "an unusable value falls back to off: " + wrong);
        }
        assertTrue(AwfSettings.KEYS.contains(AwfSettings.COMMIT_DIRTY_BYTES_KEY), "read from the TOML like the others");
        assertThrows(IllegalArgumentException.class, () -> new AwfSettings(Set.of(), Set.of(), "file",
            PersistenceMode.INCREMENTAL, 30, 1024, 2, 2, false, -1));
    }
}
