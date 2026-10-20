package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DEV-G "restart/crash persistence test for any async save changes". The one asynchronous save
 * path SourbyCraft adds is the AWF commit a region thread starts without waiting for it: a
 * {@code save-all flush} (or the periodic maintenance commit) queues the commit on the storage
 * lane and returns. These tests pin what a restart sees around that commit:
 * <ul>
 *   <li>crash before the lane ran it: the previous commit, and nothing else (the documented window);</li>
 *   <li>crash after it ran: everything it carried;</li>
 *   <li>crash at any single fsync inside it: a whole previous or a whole new generation, never a
 *       torn one, no leftover temporary file, and the chunks stay dirty so the next commit retries;</li>
 *   <li>shutdown while it is still running, with a newer write racing it: the last write, and the
 *       late lane commit never rolls the shutdown commit back.</li>
 * </ul>
 * A "crash" is a fresh engine opening the same folder with no {@code close} on the first one.
 * The FILE backend is exercised through the real {@link AwfWorldStore}; only the fsync seam
 * ({@link Durability}) is instrumented.
 */
class AwfAsyncSaveRestartTest {

    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** One commit attempt per save, so one injected crash fails the commit instead of being retried. */
    private static AwfSettings settings(final int commitAttempts) {
        return new AwfSettings(Set.of("world"), PersistenceMode.INCREMENTAL, 30, 1024, 2, commitAttempts);
    }

    private AwfEngine engine(final Executor lane, final Durability durability, final int commitAttempts) {
        final AwfBackend backend = new InstrumentedFileBackend(durability);
        return new AwfEngine(settings(commitAttempts), lane, this.clock::get, name -> backend);
    }

    /** A plain engine, as the next boot would create: real fsync, direct lane. */
    private AwfEngine restarted() {
        return new AwfEngine(settings(2), Runnable::run, this.clock::get);
    }

    private Path region() throws IOException {
        final Path folder = this.dir.resolve("world").resolve("region");
        Files.createDirectories(folder);
        return folder;
    }

    private static void onRegionThread(final ThrowingRunnable task) throws Exception {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (final Throwable t) {
                failure.set(t);
            }
        }, "Folia Region Scheduler Thread #3");
        thread.start();
        thread.join();
        if (failure.get() instanceof Exception e) throw e;
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static long count(final Path root, final String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(suffix)).count();
        }
    }

    /** The FILE backend with the fsync seam replaced; full indexes, as the default settings write. */
    private static final class InstrumentedFileBackend implements AwfBackend {
        private final AwfBackend.FileBackend plain = new AwfBackend.FileBackend(Path.of(""));
        private final Durability durability;

        InstrumentedFileBackend(final Durability durability) {
            this.durability = durability;
        }

        @Override
        public String name() {
            return "file";
        }

        @Override
        public boolean exists(final String storageId) {
            return this.plain.exists(storageId);
        }

        @Override
        public AwfStore open(final String storageId, final WorldRole role, final int retainedGenerations) throws IOException {
            return AwfWorldStore.open(this.plain.directory(storageId), role, retainedGenerations, stage -> {},
                this.durability, AwfWorldStore.Compaction.FULL_ONLY);
        }

        @Override
        public String retire(final String storageId) throws IOException {
            return this.plain.retire(storageId);
        }
    }

    /** One durability call, in order. */
    private record Force(boolean file, Path path) {}

    /** Records every force, forwards it to disk, and can throw at the n-th one (a crash of the lane commit). */
    private static final class Recording implements Durability {
        final List<Force> forces = new ArrayList<>();
        int crashAt = -1;

        @Override
        public void forceFile(final Path file) throws IOException {
            record(new Force(true, file));
            Durability.DISK.forceFile(file);
        }

        @Override
        public void forceDirectory(final Path directory) throws IOException {
            record(new Force(false, directory));
            Durability.DISK.forceDirectory(directory);
        }

        private void record(final Force force) throws IOException {
            this.forces.add(force);
            if (this.forces.size() - 1 == this.crashAt) {
                throw new IOException("simulated crash at force #" + this.crashAt + " " + force);
            }
        }

        void reset() {
            this.forces.clear();
            this.crashAt = -1;
        }
    }

    /** Blocks the first file force after it is armed until released; every other force goes to disk. */
    private static final class Gate implements Durability {
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void forceFile(final Path file) throws IOException {
            if (this.armed.compareAndSet(true, false)) {
                this.reached.countDown();
                try {
                    if (!this.release.await(30, TimeUnit.SECONDS)) throw new IOException("gate never released");
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            Durability.DISK.forceFile(file);
        }

        @Override
        public void forceDirectory(final Path directory) throws IOException {
            Durability.DISK.forceDirectory(directory);
        }
    }

    @Test
    void aCrashBeforeTheLaneRunsARegionThreadFlushKeepsOnlyTheLastCommit() throws Exception {
        final Path folder = region();
        final List<Runnable> lane = new ArrayList<>();
        final AwfRegionStorage storage = engine(lane::add, Durability.DISK, 2).open(folder);
        storage.write(0, 0, b("v1"));
        storage.write(1, 0, b("w1"));
        storage.flush(); // main thread: synchronous, durable
        storage.write(0, 0, b("v2"));
        storage.write(2, 0, b("x2"));
        onRegionThread(storage::flush);
        assertEquals(1, lane.size(), "a region-thread flush only queues the commit");
        assertEquals(2, storage.stats().world().dirtyChunks(), "nothing is durable until the lane runs");

        final AwfRegionStorage afterCrash = restarted().open(folder);
        assertArrayEquals(b("v1"), afterCrash.read(0, 0), "the queued commit never ran: the last commit is what survives");
        assertArrayEquals(b("w1"), afterCrash.read(1, 0));
        assertNull(afterCrash.read(2, 0), "a chunk first written after the last commit falls through to the region file");
        assertEquals(0, count(AwfRegionStorage.storeFor(folder), ".tmp"), "no temporary file survives the reopen");
    }

    @Test
    void aCrashAfterTheLaneRanARegionThreadFlushKeepsEverythingItCarried() throws Exception {
        final Path folder = region();
        final List<Runnable> lane = new ArrayList<>();
        final AwfRegionStorage storage = engine(lane::add, Durability.DISK, 2).open(folder);
        storage.write(0, 0, b("v1"));
        storage.write(1, 0, b("w1"));
        storage.flush();
        storage.write(0, 0, b("v2"));
        storage.write(1, 0, null);
        storage.write(2, 0, b("x2"));
        onRegionThread(storage::flush);
        lane.remove(0).run();
        assertEquals(0, storage.stats().world().dirtyChunks());

        final AwfRegionStorage afterCrash = restarted().open(folder);
        assertArrayEquals(b("v2"), afterCrash.read(0, 0));
        assertSame(AwfRegionStorage.DELETED, afterCrash.read(1, 0), "the deletion the lane committed shadows the region file");
        assertArrayEquals(b("x2"), afterCrash.read(2, 0));
        assertEquals(0, count(AwfRegionStorage.storeFor(folder), ".tmp"));
    }

    /**
     * The lane commit dies at each of its fsyncs in turn. Before the generation pointer is durable a
     * restart sees the previous generation whole; after it, the new one whole. Either way the
     * crashed commit left no temporary file, and in the still-running engine the chunks stay dirty
     * so the next commit carries them.
     */
    @Test
    void aCrashAtEveryForceOfALaneCommitLeavesAWholeGenerationAndKeepsTheChunksDirty() throws Exception {
        int crashes = 0;
        for (int at = 0; ; at++) {
            final Path folder = this.dir.resolve("crash-" + at).resolve("world").resolve("region");
            Files.createDirectories(folder);
            final Path store = AwfRegionStorage.storeFor(folder);
            final List<Runnable> lane = new ArrayList<>();
            final Recording recording = new Recording();
            final AwfRegionStorage storage = engine(lane::add, recording, 1).open(folder);
            for (int x = 0; x < 6; x++) storage.write(x, 0, b("base" + x));
            storage.flush();
            recording.reset();

            storage.write(0, 0, b("modified"));
            storage.write(5, 0, null);
            storage.write(6, 0, b("added6"));
            storage.write(7, 0, b("added7"));
            onRegionThread(storage::flush);
            assertEquals(1, lane.size(), "crash point " + at + ": the region-thread flush queued one commit");
            recording.crashAt = at;
            lane.remove(0).run(); // the storage lane: the commit runs here and may die at force #at
            if (at >= recording.forces.size()) {
                // Past the last force: the commit went through, every crash point has been tried.
                assertEquals(0, storage.stats().world().dirtyChunks());
                final AwfRegionStorage afterCrash = restarted().open(folder);
                assertArrayEquals(b("modified"), afterCrash.read(0, 0));
                assertArrayEquals(b("added7"), afterCrash.read(7, 0));
                break;
            }
            crashes++;
            final Force crashed = recording.forces.get(at);
            final String where = "crash at force #" + at + " " + crashed;
            assertEquals(4, storage.stats().world().dirtyChunks(), where + ": a failed lane commit keeps every chunk dirty");
            assertEquals(1, storage.stats().world().failures(), where);
            // The root directory force after the pointer rename is the only one past the commit point.
            final boolean pastCommitPoint = !crashed.file() && crashed.path().equals(store.resolve("generations-root"));

            final AwfRegionStorage afterCrash = restarted().open(folder);
            assertEquals(0, count(store, ".tmp"), where + ": no temporary file survives a reopen");
            if (pastCommitPoint) {
                assertArrayEquals(b("modified"), afterCrash.read(0, 0), where);
                assertSame(AwfRegionStorage.DELETED, afterCrash.read(5, 0), where);
                assertArrayEquals(b("added6"), afterCrash.read(6, 0), where);
                assertArrayEquals(b("added7"), afterCrash.read(7, 0), where);
                continue;
            }
            for (int x = 0; x < 6; x++) assertArrayEquals(b("base" + x), afterCrash.read(x, 0), where);
            assertNull(afterCrash.read(6, 0), where + ": a chunk of the torn commit must not appear");
            assertNull(afterCrash.read(7, 0), where);
        }
        // 3 new objects + their shards + the generation's files and directories: at least this many points.
        assertTrue(crashes >= 3 + 3 + 3, "crashed at each of " + crashes + " forces");
    }

    @Test
    void aLaneCommitThatFailedIsCarriedByTheNextRegionThreadFlushAndSurvivesACrash() throws Exception {
        final Path folder = region();
        final List<Runnable> lane = new ArrayList<>();
        final Recording recording = new Recording();
        final AwfRegionStorage storage = engine(lane::add, recording, 1).open(folder);
        storage.write(0, 0, b("v1"));
        storage.flush();
        recording.reset();

        storage.write(0, 0, b("v2"));
        storage.write(1, 0, b("w2"));
        onRegionThread(storage::flush);
        recording.crashAt = 0;
        lane.remove(0).run();
        assertEquals(2, storage.stats().world().dirtyChunks(), "the failed commit left both chunks dirty");
        recording.reset();

        onRegionThread(storage::flush); // the next save-all flush from a region thread
        assertEquals(1, lane.size(), "the retry is queued, not run on the region thread");
        lane.remove(0).run();
        assertEquals(0, storage.stats().world().dirtyChunks());

        final AwfRegionStorage afterCrash = restarted().open(folder);
        assertArrayEquals(b("v2"), afterCrash.read(0, 0));
        assertArrayEquals(b("w2"), afterCrash.read(1, 0));
        assertEquals(0, count(AwfRegionStorage.storeFor(folder), ".tmp"));
    }

    /**
     * Shutdown while a region-thread flush's commit is mid-fsync on the storage lane and a region
     * thread has written the same chunk again since: close waits its turn behind the running
     * commit, commits the newer bytes, and the restart sees them.
     */
    @Test
    void aCloseDuringAnInFlightLaneCommitWithANewerWriteRestartsToTheLastWrite() throws Exception {
        final Path folder = region();
        final ExecutorService lane = Executors.newSingleThreadExecutor(r -> new Thread(r, "awf-test-storage-lane"));
        final ExecutorService closer = Executors.newSingleThreadExecutor(r -> new Thread(r, "awf-test-shutdown"));
        try {
            final Gate gate = new Gate();
            final AwfRegionStorage storage = engine(lane, gate, 2).open(folder);
            storage.write(0, 0, b("v1"));
            storage.write(1, 0, b("w1"));
            storage.flush();

            storage.write(0, 0, b("v2"));
            storage.write(2, 0, b("x2"));
            gate.armed.set(true);
            onRegionThread(storage::flush);
            assertTrue(gate.reached.await(30, TimeUnit.SECONDS), "the lane commit is inside its first fsync");
            onRegionThread(() -> storage.write(0, 0, b("v3"))); // newer than what the running commit snapshotted

            final var closing = closer.submit(() -> {
                storage.close();
                return null;
            });
            gate.release.countDown();
            closing.get(30, TimeUnit.SECONDS);
            assertEquals(0, storage.stats().world().dirtyChunks(), "close committed the write that raced the lane");

            final AwfRegionStorage reopened = restarted().open(folder);
            assertArrayEquals(b("v3"), reopened.read(0, 0), "the shutdown commit carried the newest bytes");
            assertArrayEquals(b("w1"), reopened.read(1, 0));
            assertArrayEquals(b("x2"), reopened.read(2, 0));
            assertEquals(3L, AwfWorldStore.open(AwfRegionStorage.storeFor(folder), WorldRole.READ_ONLY, 1).generation(),
                "first flush, the lane commit, the close commit: nothing was rolled back or skipped");
            assertEquals(0, count(AwfRegionStorage.storeFor(folder), ".tmp"));
        } finally {
            closer.shutdownNow();
            lane.shutdownNow();
        }
    }
}
