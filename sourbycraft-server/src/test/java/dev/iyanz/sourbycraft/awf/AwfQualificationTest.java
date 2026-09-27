package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The AWF qualification workloads from {@code aurora-world-fabric.md}, run in-process against the
 * same objects the engine uses ({@link AwfEngine}, {@link AwfRegionStorage}). Chunk bytes are
 * stand-ins, not NBT; no server, no players, no real disk-full or power loss. Passing these
 * qualifies the storage layer's logic, not a server running on it.
 */
class AwfQualificationTest {

    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private AwfEngine engine(final java.util.concurrent.Executor lane, final int resident) {
        return new AwfEngine(new AwfSettings(Set.of("q"), PersistenceMode.INCREMENTAL, 1, resident, 2, 3), lane,
            this.clock::get);
    }

    private Path folder(final String dimension) throws IOException {
        final Path folder = this.dir.resolve("q").resolve(dimension).resolve("region");
        Files.createDirectories(folder);
        return folder;
    }

    private static long files(final Path root) throws IOException {
        if (!Files.exists(root)) return 0;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    /** A crash at each point of the commit sequence, followed by a restart. */
    @Test
    void aCrashAtEveryCommitStageRecoversToACommittedState() throws Exception {
        for (final GenerationStore.Stage stage : GenerationStore.Stage.values()) {
            final Path root = this.dir.resolve("crash-" + stage);
            final boolean[] armed = {false};
            final AwfWorldStore store = AwfWorldStore.open(root, WorldRole.VANILLA, 2, reached -> {
                if (armed[0] && reached == stage) throw new IOException("crash at " + stage);
            });
            store.commit(Map.of(new ChunkKey(0, 0), b("before")), Set.of(), PersistenceMode.INCREMENTAL);
            armed[0] = true;
            assertThrows(IOException.class, () -> store.commit(Map.of(new ChunkKey(0, 0), b("after"),
                new ChunkKey(1, 0), b("new")), Set.of(), Set.of(new ChunkKey(2, 0)), PersistenceMode.CHECKPOINT));

            final AwfWorldStore restarted = AwfWorldStore.open(root, WorldRole.VANILLA, 2);
            final byte[] chunk = restarted.read(new ChunkKey(0, 0)).orElseThrow();
            if (stage == GenerationStore.Stage.COMMITTED) {
                // The pointer moved before the crash: the new generation is the committed one.
                assertArrayEquals(b("after"), chunk, stage.name());
                assertArrayEquals(b("new"), restarted.read(new ChunkKey(1, 0)).orElseThrow(), stage.name());
                assertTrue(restarted.deleted().contains(new ChunkKey(2, 0)), stage.name());
            } else {
                assertArrayEquals(b("before"), chunk, stage + ": the previous generation stays authoritative");
                assertTrue(restarted.read(new ChunkKey(1, 0)).isEmpty(), stage.name());
                assertTrue(restarted.deleted().isEmpty(), stage.name());
            }
            // And the store keeps working after the restart.
            restarted.commit(Map.of(new ChunkKey(3, 0), b("later")), Set.of(), PersistenceMode.INCREMENTAL);
            assertArrayEquals(b("later"), AwfWorldStore.open(root, WorldRole.VANILLA, 2)
                .read(new ChunkKey(3, 0)).orElseThrow(), stage.name());
        }
    }

    /** Repeated world load/unload with changing chunks: nothing is lost and nothing piles up. */
    @Test
    void loadUnloadLoopsKeepEveryWriteAndBoundedDisk() throws Exception {
        final AwfEngine engine = engine(Runnable::run, 8);
        final Path folder = folder("overworld");
        final Map<ChunkKey, byte[]> expected = new HashMap<>();
        long filesAfterWarmup = -1;
        for (int cycle = 0; cycle < 200; cycle++) {
            final AwfRegionStorage storage = engine.open(folder);
            for (final Map.Entry<ChunkKey, byte[]> e : expected.entrySet()) {
                assertArrayEquals(e.getValue(), storage.read(e.getKey().x(), e.getKey().z()), "cycle " + cycle);
            }
            for (int i = 0; i < 16; i++) {
                final ChunkKey key = new ChunkKey((cycle + i) % 32, 0);
                final byte[] bytes = b("c" + cycle + "-" + i);
                storage.write(key.x(), key.z(), bytes);
                expected.put(key, bytes);
            }
            storage.close();
            if (cycle == 20) filesAfterWarmup = files(AwfRegionStorage.storeFor(folder));
        }
        assertTrue(engine.storages().isEmpty(), "every unloaded storage is forgotten");
        final long filesAtEnd = files(AwfRegionStorage.storeFor(folder));
        // 32 chunks x 2 retained generations, plus generation files: bounded, not growing per cycle.
        assertTrue(filesAtEnd <= filesAfterWarmup + 8, "files " + filesAfterWarmup + " -> " + filesAtEnd);
    }

    /** Shutdown while a commit is queued on a stalled lane, then the late commit runs anyway. */
    @Test
    void shutdownWithAPendingCommitLosesNothing() throws Exception {
        final List<Runnable> stalled = new ArrayList<>();
        final AwfEngine engine = engine(stalled::add, 1024);
        final Path folder = folder("overworld");
        final AwfRegionStorage storage = engine.open(folder);
        storage.write(0, 0, b("first"));
        this.clock.addAndGet(2_000_000_000L);
        assertEquals(1, engine.maintain(), "a commit is queued behind a stalled lane");
        storage.write(0, 0, b("second"));
        storage.write(1, 0, b("other"));
        storage.close();
        stalled.forEach(Runnable::run);

        final AwfRegionStorage reopened = engine(Runnable::run, 1024).open(folder);
        assertArrayEquals(b("second"), reopened.read(0, 0), "the late commit did not roll back the shutdown commit");
        assertArrayEquals(b("other"), reopened.read(1, 0));
    }

    /** Region threads writing while the storage lane commits, then shutdown. */
    @Test
    void concurrentWritersAndCommitsEndInTheLastWrittenState() throws Exception {
        final ExecutorService lane = Executors.newSingleThreadExecutor();
        final ExecutorService writers = Executors.newFixedThreadPool(4);
        try {
            final AwfEngine engine = engine(lane, 16);
            final Path folder = folder("overworld");
            final AwfRegionStorage storage = engine.open(folder);
            final int perWriter = 500;
            final List<java.util.concurrent.Future<?>> done = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                final int writer = w;
                done.add(writers.submit(() -> {
                    for (int i = 0; i < perWriter; i++) {
                        // Each writer owns its own chunks, as regions own theirs.
                        storage.write(writer * 100 + (i % 20), 0, b("w" + writer + "-" + i));
                        if (i % 50 == 0) Thread.onSpinWait();
                    }
                    return null;
                }));
            }
            for (int i = 0; i < 200; i++) {
                this.clock.addAndGet(1_000_000_000L);
                engine.maintain();
            }
            for (final var f : done) f.get(60, TimeUnit.SECONDS);
            storage.close();

            final AwfRegionStorage reopened = engine(Runnable::run, 16).open(folder);
            for (int w = 0; w < 4; w++) {
                for (int c = 0; c < 20; c++) {
                    final int last = perWriter - 20 + c;
                    assertArrayEquals(b("w" + w + "-" + last), reopened.read(w * 100 + c, 0), "writer " + w + " chunk " + c);
                }
            }
        } finally {
            writers.shutdownNow();
            lane.shutdownNow();
        }
    }

    /** 1, 50, 250 and 1000 open storages: open, write, commit, reopen, verify. */
    @Test
    void manyWorlds() throws Exception {
        for (final int count : new int[] {1, 50, 250, 1000}) {
            final AwfEngine engine = engine(Runnable::run, 4);
            final List<AwfRegionStorage> open = new ArrayList<>();
            final List<Path> folders = new ArrayList<>();
            final long started = System.nanoTime();
            for (int i = 0; i < count; i++) {
                final Path folder = folder("n" + count + "-" + i);
                folders.add(folder);
                final AwfRegionStorage storage = engine.open(folder);
                for (int c = 0; c < 4; c++) storage.write(c, i, b(count + ":" + i + ":" + c));
                open.add(storage);
            }
            assertEquals(count, engine.storages().size());
            this.clock.addAndGet(2_000_000_000L);
            assertEquals(count, engine.maintain(), "one commit per dirty storage");
            for (final AwfRegionStorage storage : open) {
                assertEquals(0, storage.stats().world().dirtyChunks());
                storage.close();
            }
            assertTrue(engine.storages().isEmpty());
            final AwfEngine restarted = engine(Runnable::run, 4);
            for (int i = 0; i < count; i++) {
                final AwfRegionStorage storage = restarted.open(folders.get(i));
                for (int c = 0; c < 4; c++) assertArrayEquals(b(count + ":" + i + ":" + c), storage.read(c, i));
                assertNull(storage.read(9, i), "untouched chunks still fall through");
                storage.close();
            }
            System.out.printf("AWF manyWorlds: %d storages opened, written, committed and reopened in %d ms%n",
                count, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    /** A damaged object is reported, never returned as chunk data. */
    @Test
    void aCorruptObjectIsReportedNotReturned() throws Exception {
        final AwfEngine engine = engine(Runnable::run, 1);
        final Path folder = folder("overworld");
        final AwfRegionStorage storage = engine.open(folder);
        storage.write(0, 0, b("precious"));
        storage.write(1, 0, b("evicts the first"));
        storage.close();
        final Path object;
        try (Stream<Path> walk = Files.walk(AwfRegionStorage.storeFor(folder).resolve("objects"))) {
            object = walk.filter(p -> p.getFileName().toString().equals(ObjectStore.name(b("precious")))).findFirst().orElseThrow();
        }
        final byte[] raw = Files.readAllBytes(object);
        raw[raw.length - 1] ^= 0x55;
        Files.write(object, raw);
        final AwfRegionStorage reopened = engine.open(folder);
        assertThrows(IOException.class, () -> reopened.read(0, 0));
        assertArrayEquals(b("evicts the first"), reopened.read(1, 0), "other chunks are unaffected");
    }
}
