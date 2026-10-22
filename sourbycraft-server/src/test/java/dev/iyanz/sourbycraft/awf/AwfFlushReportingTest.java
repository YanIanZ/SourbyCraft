package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Audit D4: {@code save-all flush} says "Saved the game" only once every AWF storage confirmed a
 * commit of what the save wrote; before that it says the save is pending, and a storage that could
 * not commit is named with its dirty count instead of a success.
 */
class AwfFlushReportingTest {

    @TempDir Path dir;
    private static final Executor DIRECT = Runnable::run;

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Lines the command would send, in order: "SAVED", "INFO: ...", "FAIL: ...". */
    private static final class Lines implements AwfFlushReporting.Feedback {
        final List<String> lines = new ArrayList<>();

        @Override public void saved() { this.lines.add("SAVED"); }
        @Override public void info(final String message) { this.lines.add("INFO: " + message); }
        @Override public void failure(final String message) { this.lines.add("FAIL: " + message); }
    }

    private AwfRegionStorage fileStorage(final String world) throws IOException {
        final Path folder = this.dir.resolve(world).resolve("region");
        Files.createDirectories(folder);
        final AwfEngine engine = new AwfEngine(new AwfSettings(Set.of(world), PersistenceMode.INCREMENTAL, 30, 1024, 2, 1),
            DIRECT, System::nanoTime);
        return engine.open(folder);
    }

    private static AwfRegionStorage failingStorage(final String name) {
        final AwfStore failing = new AwfStore() {
            @Override public Optional<byte[]> read(final ChunkKey key) { return Optional.empty(); }
            @Override public Set<ChunkKey> keys() { return Set.of(); }
            @Override public boolean has(final ChunkKey key) { return false; }
            @Override public Set<ChunkKey> deleted() { return Set.of(); }
            @Override public long generation() { return 0; }
            @Override
            public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                       final Set<ChunkKey> deleted, final PersistenceMode mode) throws IOException {
                throw new IOException("backend down");
            }
        };
        return new AwfRegionStorage(name, new AwfWorld(name, WorldRole.VANILLA, null, failing),
            new AwfSettings(Set.of(), PersistenceMode.INCREMENTAL, 30, 1024, 2, 1), DIRECT, 0L, () -> { });
    }

    private static CompletableFuture<List<AwfRegionStorage.FlushResult>> report(final boolean flush,
            final List<AwfRegionStorage> storages, final Executor waiter, final Lines lines) {
        return AwfFlushReporting.afterRegionSaves(flush, storages, () -> true, waiter, System::nanoTime,
            AwfFlushReporting.IO_WAIT_NANOS, () -> { }, lines);
    }

    @Test
    void withoutAwfTheCommandIsUnchanged() {
        final Lines lines = new Lines();
        report(true, List.of(), DIRECT, lines);
        assertEquals(List.of("SAVED"), lines.lines);
    }

    @Test
    void flushSaysPendingFirstAndSavedOnlyOnceEveryStorageIsDurable() throws Exception {
        final AwfRegionStorage a = fileStorage("a");
        final AwfRegionStorage b = fileStorage("b");
        a.write(0, 0, b("a0"));
        a.write(1, 0, b("a1"));
        b.write(0, 0, b("b0"));
        final List<Runnable> waiting = new ArrayList<>();
        final Lines lines = new Lines();
        final CompletableFuture<List<AwfRegionStorage.FlushResult>> done = report(true, List.of(a, b), waiting::add, lines);

        // The caller (a region thread) is not blocked: only the pending line so far.
        assertFalse(done.isDone());
        assertEquals(1, lines.lines.size());
        assertTrue(lines.lines.getFirst().startsWith("INFO: Aurora World Fabric: waiting for 2 storage(s)"), lines.lines.toString());
        assertTrue(lines.lines.getFirst().contains("pending"));
        assertFalse(lines.lines.getFirst().contains("Saved the game"), "a script waiting for that text must not proceed yet");

        waiting.forEach(Runnable::run);
        done.get();
        assertEquals(List.of("SAVED", "INFO: Aurora World Fabric: 2 storage(s) durable, 3 chunk(s) committed"),
            lines.lines.subList(1, lines.lines.size()));
        assertEquals(0, a.dirtyCount());
        assertEquals(0, b.dirtyCount());
        assertArrayEquals(b("a1"), a.read(1, 0));
    }

    @Test
    void aStorageThatCannotCommitIsNamedWithItsDirtyCountAndNoSuccessIsSent() throws Exception {
        final AwfRegionStorage ok = fileStorage("ok");
        final AwfRegionStorage down = failingStorage("down/region");
        ok.write(0, 0, b("x"));
        down.write(4, 4, b("y"));
        down.write(5, 5, b("z"));
        final Lines lines = new Lines();
        final List<AwfRegionStorage.FlushResult> results = report(true, List.of(ok, down), DIRECT, lines).get();

        assertFalse(lines.lines.contains("SAVED"), lines.lines.toString());
        final String failure = lines.lines.getLast();
        assertTrue(failure.startsWith("FAIL: Save NOT durable: Aurora World Fabric could not commit 1 of 2 storage(s),"
            + " 2 chunk(s) only in memory: down/region (2 dirty: backend down)"), failure);
        assertEquals(AwfRegionStorage.FlushStatus.DURABLE, results.get(0).status());
        assertEquals(AwfRegionStorage.FlushStatus.FAILED, results.get(1).status());
        assertEquals(2, down.dirtyCount(), "the chunks stay in memory for the next commit");
    }

    @Test
    void chunkIoThatNeverGoesIdleIsReportedAsUnconfirmed() throws Exception {
        final AwfRegionStorage a = fileStorage("busy");
        a.write(0, 0, b("x"));
        final AtomicLong now = new AtomicLong();
        final Lines lines = new Lines();
        AwfFlushReporting.afterRegionSaves(true, List.of(a), () -> false, DIRECT, now::get, 1_000L,
            () -> now.addAndGet(400L), lines).get();
        assertFalse(lines.lines.contains("SAVED"));
        assertTrue(lines.lines.getLast().startsWith("FAIL: Save NOT durable: chunk I/O did not go idle within"),
            lines.lines.toString());
    }

    @Test
    void theBarrierWaitsForChunkIoBeforeItSnapshots() throws Exception {
        final AwfRegionStorage a = fileStorage("late");
        final AtomicLong now = new AtomicLong();
        final int[] checks = {0};
        final Lines lines = new Lines();
        // The save's chunk reaches AWF from the I/O queue only after two idle checks.
        AwfFlushReporting.afterRegionSaves(true, List.of(a), () -> {
            if (++checks[0] == 3) {
                try {
                    a.write(7, 7, b("from the I/O queue"));
                } catch (final IOException failed) {
                    throw new IllegalStateException(failed);
                }
                return true;
            }
            return false;
        }, DIRECT, now::get, AwfFlushReporting.IO_WAIT_NANOS, () -> now.incrementAndGet(), lines).get();
        assertTrue(lines.lines.contains("SAVED"), lines.lines.toString());
        assertEquals(0, a.dirtyCount(), "the write that arrived during the wait was committed by the barrier");
    }

    @Test
    void plainSaveAllDoesNotWaitAndSaysWhatIsStillInMemory() throws Exception {
        final AwfRegionStorage a = fileStorage("plain");
        a.write(0, 0, b("x"));
        final Lines lines = new Lines();
        report(false, List.of(a), task -> { throw new AssertionError("nothing is waited for"); }, lines);
        assertEquals(List.of("SAVED", "INFO: Aurora World Fabric: 1 chunk(s) are in memory and are committed by the next"
            + " periodic commit; use save-all flush to wait for a confirmed commit"), lines.lines);
        assertEquals(1, a.dirtyCount());
    }

    @Test
    void closedStoragesAreLeftOut() throws Exception {
        final AwfRegionStorage a = fileStorage("gone");
        a.write(0, 0, b("x"));
        a.close();
        final Lines lines = new Lines();
        report(true, List.of(a), DIRECT, lines);
        assertEquals(List.of("SAVED"), lines.lines);
        assertEquals(AwfRegionStorage.FlushStatus.SKIPPED, a.flushBarrier().get().status());
    }
}
