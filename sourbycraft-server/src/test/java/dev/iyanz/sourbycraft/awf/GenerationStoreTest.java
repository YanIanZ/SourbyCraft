package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A crash before the commit point must leave the previous generation authoritative. */
class GenerationStoreTest {

    @TempDir Path root;

    private static Map<String, byte[]> snapshot(final String value) {
        return Map.of("level.dat", value.getBytes(StandardCharsets.UTF_8),
            "region-0-0", ("chunks:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static String level(final GenerationStore.Generation generation) {
        return new String(generation.blobs().get("level.dat"), StandardCharsets.UTF_8);
    }

    @Test
    void anEmptyStoreHasNoGeneration() throws IOException {
        final GenerationStore store = GenerationStore.open(this.root, WorldRole.VANILLA, 2);
        assertEquals(0L, store.currentGeneration());
        assertNull(store.read());
    }

    @Test
    void aCommitIsReadBackVerified() throws IOException {
        final GenerationStore store = GenerationStore.open(this.root, WorldRole.VANILLA, 2);
        assertEquals(1L, store.commit(snapshot("one")));
        assertEquals(2L, store.commit(snapshot("two")));
        final GenerationStore.Generation read = GenerationStore.open(this.root, WorldRole.VANILLA, 2).read();
        assertEquals(2L, read.number());
        assertEquals("two", level(read));
        assertArrayEquals("chunks:two".getBytes(StandardCharsets.UTF_8), read.blobs().get("region-0-0"));
    }

    @Test
    void aCrashAtAnyStageBeforeTheCommitLeavesThePreviousGeneration() throws IOException {
        for (final GenerationStore.Stage crashAt : new GenerationStore.Stage[] {
            GenerationStore.Stage.SNAPSHOT_WRITTEN, GenerationStore.Stage.VERIFIED,
            GenerationStore.Stage.GENERATION_PUBLISHED}) {
            final Path world = this.root.resolve(crashAt.name());
            GenerationStore.open(world, WorldRole.VANILLA, 2).commit(snapshot("stable"));
            final GenerationStore crashing = GenerationStore.open(world, WorldRole.VANILLA, 2, stage -> {
                if (stage == crashAt) throw new IOException("simulated crash at " + stage);
            });
            assertThrows(IOException.class, () -> crashing.commit(snapshot("lost")));

            // Restart: reopening recovers, and the old generation is still the truth.
            final GenerationStore reopened = GenerationStore.open(world, WorldRole.VANILLA, 2);
            assertEquals(1L, reopened.currentGeneration(), crashAt.name());
            assertEquals("stable", level(reopened.read()), crashAt.name());
            try (var dirs = Files.list(world.resolve(GenerationStore.GENERATIONS))) {
                assertEquals(1L, dirs.count(), "leftovers removed after " + crashAt);
            }
            // And the next commit proceeds normally.
            assertEquals(2L, reopened.commit(snapshot("next")));
            assertEquals("next", level(reopened.read()));
        }
    }

    @Test
    void aCrashAfterTheCommitPointKeepsTheNewGeneration() throws IOException {
        GenerationStore.open(this.root, WorldRole.VANILLA, 2).commit(snapshot("old"));
        final GenerationStore crashing = GenerationStore.open(this.root, WorldRole.VANILLA, 2, stage -> {
            if (stage == GenerationStore.Stage.COMMITTED) throw new IOException("crash after commit");
        });
        assertThrows(IOException.class, () -> crashing.commit(snapshot("new")));
        assertEquals("new", level(GenerationStore.open(this.root, WorldRole.VANILLA, 2).read()));
    }

    @Test
    void aDamagedBlobIsReportedNotReturned() throws IOException {
        final GenerationStore store = GenerationStore.open(this.root, WorldRole.VANILLA, 2);
        store.commit(snapshot("intact"));
        Files.writeString(this.root.resolve(GenerationStore.GENERATIONS).resolve("1").resolve("level.dat"), "tampere");
        assertThrows(GenerationStore.CorruptGenerationException.class, store::read);
    }

    @Test
    void onlyTheRetainedNumberOfGenerationsIsKept() throws IOException {
        final GenerationStore store = GenerationStore.open(this.root, WorldRole.VANILLA, 2);
        for (int i = 0; i < 5; i++) store.commit(snapshot("v" + i));
        try (var dirs = Files.list(this.root.resolve(GenerationStore.GENERATIONS))) {
            assertEquals(java.util.Set.of("4", "5"),
                dirs.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void theSnapshotIsCopiedSoLaterMutationCannotLeakIn() throws Exception {
        final byte[] buffer = "before".getBytes(StandardCharsets.UTF_8);
        final GenerationStore store = GenerationStore.open(this.root, WorldRole.VANILLA, 2);
        final ExecutorService storage = Executors.newSingleThreadExecutor();
        try {
            final var pending = store.commitAsync(Map.of("level.dat", buffer), storage);
            buffer[0] = 'X';
            assertEquals(1L, pending.toCompletableFuture().get(10, TimeUnit.SECONDS));
        } finally {
            storage.shutdown();
        }
        assertEquals("before", level(store.read()));
    }

    @Test
    void rolesThatDoNotAcceptCommitsRefuse() throws IOException {
        for (final WorldRole role : WorldRole.values()) {
            final GenerationStore store = GenerationStore.open(this.root.resolve(role.name()), role, 1);
            if (role.acceptsCommits()) {
                assertEquals(1L, store.commit(snapshot("ok")));
            } else {
                assertThrows(IllegalStateException.class, () -> store.commit(snapshot("no")), role.name());
            }
        }
        assertFalse(WorldRole.TEMPLATE.acceptsCommits());
        assertFalse(WorldRole.TEMPORARY.acceptsCommits());
    }

    @Test
    void unsafeBlobNamesAreRejected() throws IOException {
        final GenerationStore store = GenerationStore.open(this.root, WorldRole.VANILLA, 1);
        for (final String name : new String[] {"../escape", "manifest", "a/b", "", "x.tmp"}) {
            assertThrows(IllegalArgumentException.class, () -> store.commit(Map.of(name, new byte[0])), name);
        }
    }

    @Test
    void aRegionThreadMayNotBlockOnStorage() throws Exception {
        final GenerationStore store = GenerationStore.open(this.root, WorldRole.VANILLA, 1);
        final AtomicReference<Throwable> seen = new AtomicReference<>();
        final Thread region = new Thread(() -> {
            try {
                store.commit(snapshot("blocked"));
            } catch (final Throwable t) {
                seen.set(t);
            }
        }, "Folia Region Scheduler Thread #0");
        region.start();
        region.join();
        assertTrue(seen.get() instanceof IllegalStateException, String.valueOf(seen.get()));
        assertEquals(0L, store.currentGeneration());
    }
}
