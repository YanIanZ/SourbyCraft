package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Copy-on-write isolation, lazy materialisation, and saves that never block the caller. */
class AwfWorldTest {

    @TempDir Path dir;

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private AwfFile template() throws Exception {
        final Path file = this.dir.resolve("template.awf");
        AwfFile.write(file, Map.of("name", "arena"), new java.util.TreeMap<>(Map.of(
            new ChunkKey(0, 0), b("floor"), new ChunkKey(1, 0), b("wall"))));
        return AwfFile.open(file);
    }

    @Test
    void instancesShareATemplateAndStayIsolated() throws Exception {
        try (AwfFile template = template()) {
            final AwfWorld a = new AwfWorld("a", WorldRole.INSTANCE, template,
                AwfWorldStore.open(this.dir.resolve("a"), WorldRole.INSTANCE, 1));
            final AwfWorld b = new AwfWorld("b", WorldRole.INSTANCE, template,
                AwfWorldStore.open(this.dir.resolve("b"), WorldRole.INSTANCE, 1));
            a.write(new ChunkKey(0, 0), b("floor-broken"));
            assertTrue(a.owns(new ChunkKey(0, 0)));
            assertFalse(b.owns(new ChunkKey(0, 0)));
            assertArrayEquals(b("floor-broken"), a.read(new ChunkKey(0, 0)).orElseThrow());
            assertArrayEquals(b("floor"), b.read(new ChunkKey(0, 0)).orElseThrow());
            assertArrayEquals(b("floor"), template.read(new ChunkKey(0, 0)).orElseThrow(), "the template is untouched");
        }
    }

    @Test
    void theCallersBufferAndTheReturnedArrayAreNeverShared() throws Exception {
        final AwfWorld world = new AwfWorld("w", WorldRole.TEMPORARY, null, null);
        final byte[] buffer = b("abc");
        world.write(new ChunkKey(0, 0), buffer);
        buffer[0] = 'X';
        final byte[] read = world.read(new ChunkKey(0, 0)).orElseThrow();
        read[1] = 'Y';
        assertArrayEquals(b("abc"), world.read(new ChunkKey(0, 0)).orElseThrow());
    }

    @Test
    void chunksMaterialiseOnlyWhenRead() throws Exception {
        try (AwfFile template = template()) {
            final AwfWorld world = new AwfWorld("v", WorldRole.READ_ONLY, template, null);
            assertEquals(2, world.keys().size());
            assertEquals(0, world.metrics().materialized());
            world.read(new ChunkKey(1, 0));
            assertEquals(1, world.metrics().materialized());
            assertThrows(IllegalStateException.class, () -> world.write(new ChunkKey(1, 0), b("x")));
        }
    }

    @Test
    void anInstanceSurvivesARestartOverItsTemplate() throws Exception {
        final ExecutorService storage = Executors.newSingleThreadExecutor();
        try (AwfFile template = template()) {
            final AwfWorld world = new AwfWorld("i", WorldRole.INSTANCE, template,
                AwfWorldStore.open(this.dir.resolve("i"), WorldRole.INSTANCE, 1));
            world.write(new ChunkKey(1, 0), b("door"));
            world.save(PersistenceMode.INCREMENTAL, storage, 1).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(0, world.metrics().dirtyChunks());

            final AwfWorldStore store = AwfWorldStore.open(this.dir.resolve("i"), WorldRole.INSTANCE, 1);
            final AwfWorld restarted = new AwfWorld("i", WorldRole.INSTANCE, new LayeredSource(store, template), store);
            assertArrayEquals(b("door"), restarted.read(new ChunkKey(1, 0)).orElseThrow());
            assertArrayEquals(b("floor"), restarted.read(new ChunkKey(0, 0)).orElseThrow());
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void aWriteDuringASaveStaysDirty() throws Exception {
        final List<Runnable> queued = new ArrayList<>();
        final Executor manual = queued::add;
        final AwfWorld world = new AwfWorld("w", WorldRole.VANILLA, null,
            AwfWorldStore.open(this.dir.resolve("w"), WorldRole.VANILLA, 1));
        world.write(new ChunkKey(0, 0), b("v1"));
        final var pending = world.save(PersistenceMode.INCREMENTAL, manual, 1).toCompletableFuture();
        assertEquals(1, world.metrics().saveQueueDepth(), "queued, and the caller was not blocked");
        world.write(new ChunkKey(0, 0), b("v2"));
        // The save snapshots when it runs: v2 is written by it and the entry is cleared.
        queued.remove(0).run();
        pending.get();
        assertEquals(0, world.metrics().dirtyChunks());
        assertEquals(0, world.metrics().saveQueueDepth());
        assertArrayEquals(b("v2"), AwfWorldStore.open(this.dir.resolve("w"), WorldRole.VANILLA, 1)
            .read(new ChunkKey(0, 0)).orElseThrow());
    }

    @Test
    void aFailingBackendIsRetriedThenReportedAndTheChunksStayDirty() throws Exception {
        final Path root = this.dir.resolve("f");
        final AwfWorldStore store = AwfWorldStore.open(root, WorldRole.VANILLA, 1);
        final AwfWorld world = new AwfWorld("f", WorldRole.VANILLA, null, store);
        world.write(new ChunkKey(0, 0), b("data"));
        // Make the object directory unwritable by replacing it with a file.
        java.nio.file.Files.walk(root.resolve("objects")).sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> p.toFile().delete());
        java.nio.file.Files.writeString(root.resolve("objects"), "not a directory");
        final var failed = world.save(PersistenceMode.INCREMENTAL, Runnable::run, 3).toCompletableFuture();
        assertThrows(java.util.concurrent.ExecutionException.class, failed::get);
        final AwfMetrics.Snapshot m = world.metrics();
        assertEquals(2, m.retries());
        assertEquals(1, m.failures());
        assertEquals(1, m.dirtyChunks());
    }

    @Test
    void worldsThatDoNotPersistRefuseToSave() {
        final AwfWorld temporary = new AwfWorld("t", WorldRole.TEMPORARY, null, null);
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> temporary.save(PersistenceMode.FULL, Runnable::run, 1).toCompletableFuture().get());
        assertThrows(IllegalArgumentException.class, () -> new AwfWorld("v", WorldRole.VANILLA, null, null));
    }

    @Test
    void cleanChunksBeyondTheLimitAreDroppedLeastRecentlyUsedFirstAndReadBackFromTheStore() throws Exception {
        final AwfWorld world = new AwfWorld("lru", WorldRole.VANILLA, null,
            AwfWorldStore.open(this.dir.resolve("lru"), WorldRole.VANILLA, 1), 2);
        for (int i = 0; i < 4; i++) world.write(new ChunkKey(i, 0), b("c" + i));
        world.read(new ChunkKey(0, 0)); // touch 0: now most recently used
        world.save(PersistenceMode.INCREMENTAL, Runnable::run, 1).toCompletableFuture().get();

        assertEquals(2, world.evicted());
        assertEquals(2, world.metrics().residentChunks());
        assertTrue(world.resident(new ChunkKey(0, 0)), "recently read, kept");
        assertTrue(world.resident(new ChunkKey(3, 0)), "most recently written, kept");
        assertFalse(world.resident(new ChunkKey(1, 0)));
        assertTrue(world.owns(new ChunkKey(1, 0)), "dropped from memory, still this world's chunk");
        assertArrayEquals(b("c1"), world.read(new ChunkKey(1, 0)).orElseThrow(), "read back from the store");
    }

    @Test
    void dirtyChunksAreNeverDropped() throws Exception {
        final List<Runnable> queued = new ArrayList<>();
        final AwfWorld world = new AwfWorld("d", WorldRole.VANILLA, null,
            AwfWorldStore.open(this.dir.resolve("d"), WorldRole.VANILLA, 1), 1);
        world.write(new ChunkKey(0, 0), b("a"));
        world.write(new ChunkKey(1, 0), b("b"));
        final var pending = world.save(PersistenceMode.INCREMENTAL, queued::add, 1).toCompletableFuture();
        // Rewritten after the save was queued but before it ran: the save commits it, and
        // eviction must still not drop anything a later write dirtied.
        queued.remove(0).run();
        pending.get();
        world.write(new ChunkKey(2, 0), b("c"));
        world.write(new ChunkKey(3, 0), b("d"));
        assertEquals(3, world.metrics().residentChunks(), "1 clean kept by the limit + 2 dirty");
        assertTrue(world.resident(new ChunkKey(2, 0)));
        assertTrue(world.resident(new ChunkKey(3, 0)));
    }

    @Test
    void latencyPercentilesAreNearestRank() {
        final LatencyRecorder r = new LatencyRecorder();
        for (int i = 1; i <= 100; i++) r.record(i * 1_000_000L);
        final LatencyRecorder.Percentiles p = r.percentiles();
        assertEquals(50.0, p.p50());
        assertEquals(95.0, p.p95());
        assertEquals(99.0, p.p99());
        for (int i = 0; i < 5000; i++) r.record(1L);
        assertEquals(LatencyRecorder.CAPACITY, r.percentiles().samples(), "bounded");
    }

    @Test
    void theRegistryRefusesDuplicates() {
        final AwfRegistry registry = new AwfRegistry();
        registry.register(new AwfWorld("x", WorldRole.TEMPORARY, null, null));
        assertThrows(IllegalStateException.class, () -> registry.register(new AwfWorld("x", WorldRole.TEMPORARY, null, null)));
        assertEquals(1, registry.loaded());
        assertTrue(registry.unregister("x"));
    }

    @SuppressWarnings("unused")
    private static void unused(final CompletionException e) {}
}
