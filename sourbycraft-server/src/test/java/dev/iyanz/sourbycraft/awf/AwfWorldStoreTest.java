package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every mode commits a complete world atomically; they differ only in what they write and verify. */
class AwfWorldStoreTest {

    @TempDir Path dir;

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static long objectFiles(final Path root) throws Exception {
        try (var walk = Files.walk(root.resolve("objects"))) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void incrementalWritesOnlyNewObjectsButCommitsTheWholeWorld() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2);
        final var first = store.commit(Map.of(new ChunkKey(0, 0), b("a"), new ChunkKey(1, 0), b("b")), Set.of(),
            PersistenceMode.INCREMENTAL);
        assertEquals(2, first.objectsWritten());
        final var second = store.commit(Map.of(new ChunkKey(1, 0), b("b2")), Set.of(), PersistenceMode.INCREMENTAL);
        assertEquals(1, second.objectsWritten());
        assertEquals(2, second.chunks());

        final AwfWorldStore reopened = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2);
        assertArrayEquals(b("a"), reopened.read(new ChunkKey(0, 0)).orElseThrow());
        assertArrayEquals(b("b2"), reopened.read(new ChunkKey(1, 0)).orElseThrow());
    }

    @Test
    void identicalChunksShareOneObject() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1);
        final var r = store.commit(Map.of(new ChunkKey(0, 0), b("same"), new ChunkKey(5, 5), b("same")), Set.of(),
            PersistenceMode.INCREMENTAL);
        assertEquals(1, r.objectsWritten());
        assertEquals(1, objectFiles(this.dir));
    }

    @Test
    void fullRewritesEveryChunk() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1);
        store.commit(Map.of(new ChunkKey(0, 0), b("a"), new ChunkKey(1, 1), b("b")), Set.of(), PersistenceMode.INCREMENTAL);
        final var full = store.commit(Map.of(new ChunkKey(0, 0), b("a2")), Set.of(), PersistenceMode.FULL);
        assertEquals(2, full.objectsWritten());
    }

    @Test
    void objectsOnlyAnOldGenerationUsedAreCollectedOnceItIsNoLongerRetained() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2);
        store.commit(Map.of(new ChunkKey(0, 0), b("v1")), Set.of(), PersistenceMode.INCREMENTAL);
        store.commit(Map.of(new ChunkKey(0, 0), b("v2")), Set.of(), PersistenceMode.INCREMENTAL);
        assertEquals(2, objectFiles(this.dir), "v1 is still referenced by the retained generation");
        final var third = store.commit(Map.of(new ChunkKey(0, 0), b("v3")), Set.of(), PersistenceMode.INCREMENTAL);
        assertEquals(1, third.objectsRemoved());
        assertEquals(2, objectFiles(this.dir));
    }

    @Test
    void checkpointVerifiesEveryReferencedObjectBeforeCommitting() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1);
        store.commit(Map.of(new ChunkKey(0, 0), b("keep"), new ChunkKey(1, 1), b("rot")), Set.of(), PersistenceMode.INCREMENTAL);
        final long before = store.generation();
        // Damage the object behind (1,1).
        final String name = ObjectStore.name(b("rot"));
        Files.write(this.dir.resolve("objects").resolve(name.substring(0, 2)).resolve(name), new byte[] {0, 0, 0, 3, 1, 2, 3});
        // INCREMENTAL does not look at an unchanged object...
        assertEquals(before + 1, store.commit(Map.of(new ChunkKey(2, 2), b("new")), Set.of(), PersistenceMode.INCREMENTAL).generation());
        // ...CHECKPOINT does, and refuses to commit a generation that references it.
        assertThrows(java.io.IOException.class,
            () -> store.commit(Map.of(new ChunkKey(3, 3), b("x")), Set.of(), PersistenceMode.CHECKPOINT));
        assertEquals(before + 1, store.generation());
    }

    @Test
    void removedChunksLeaveTheWorld() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1);
        store.commit(Map.of(new ChunkKey(0, 0), b("a"), new ChunkKey(1, 1), b("b")), Set.of(), PersistenceMode.INCREMENTAL);
        store.commit(Map.of(), Set.of(new ChunkKey(1, 1)), PersistenceMode.INCREMENTAL);
        assertEquals(Set.of(new ChunkKey(0, 0)), AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1).keys());
    }

    @Test
    void readOnlyAndTemplateWorldsRefuseCommits() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1);
        assertThrows(IllegalStateException.class, () -> store.commit(Map.of(), Set.of(), PersistenceMode.READ_ONLY));
        final AwfWorldStore template = AwfWorldStore.open(this.dir.resolve("t"), WorldRole.TEMPLATE, 1);
        assertThrows(IllegalStateException.class, () -> template.commit(Map.of(), Set.of(), PersistenceMode.FULL));
    }

    @Test
    void unreferencedObjectsFromACrashedCommitDoNotSurviveTheNextOne() throws Exception {
        final AwfWorldStore store = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1);
        store.commit(Map.of(new ChunkKey(0, 0), b("a")), Set.of(), PersistenceMode.INCREMENTAL);
        // An object written by a commit that never reached its generation swap.
        new ObjectStore(this.dir.resolve("objects")).put(b("orphan"), false);
        assertEquals(2, objectFiles(this.dir));
        final AwfWorldStore reopened = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 1);
        assertEquals(Set.of(new ChunkKey(0, 0)), reopened.keys(), "the orphan is not part of the world");
        reopened.commit(Map.of(new ChunkKey(1, 1), b("b")), Set.of(), PersistenceMode.INCREMENTAL);
        assertTrue(!new ObjectStore(this.dir.resolve("objects")).contains(ObjectStore.name(b("orphan"))));
    }
}
