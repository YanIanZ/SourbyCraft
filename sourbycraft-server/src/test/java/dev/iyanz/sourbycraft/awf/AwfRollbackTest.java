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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Point-in-time rollback of a FILE store to a retained generation, as a new generation on top. */
class AwfRollbackTest {

    @TempDir Path dir;

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final ChunkKey A = new ChunkKey(0, 0);
    private static final ChunkKey B = new ChunkKey(1, 0);
    private static final ChunkKey C = new ChunkKey(2, 0);

    /** Generation 1: a, b. 2: b rewritten, c deleted. 3: a removed, c written. */
    private static AwfWorldStore history(final AwfWorldStore store) throws IOException {
        store.commit(Map.of(A, b("a1"), B, b("b1")), Set.of(), PersistenceMode.INCREMENTAL);
        store.commit(Map.of(B, b("b2")), Set.of(), Set.of(C), PersistenceMode.INCREMENTAL);
        store.commit(Map.of(C, b("c3")), Set.of(A), PersistenceMode.INCREMENTAL);
        return store;
    }

    @Test
    void aRollbackCommitsTheOldStateAsANewGenerationAndSurvivesAReopen() throws Exception {
        for (final boolean deltas : new boolean[] {false, true}) {
            final Path root = this.dir.resolve("deltas-" + deltas);
            final AwfWorldStore store = history(AwfWorldStore.open(root, WorldRole.VANILLA, 4, deltas));
            assertEquals(List.of(3L, 2L, 1L), store.rollbackPoints());

            final AwfStore.CommitResult rolled = store.rollbackTo(1);
            assertEquals(4, rolled.generation(), "history is not rewritten: the rollback is generation 4");
            assertEquals(0, rolled.objectsWritten(), "the old state's objects are still retained");
            assertTrue(store.fullIndex(), "a rollback writes a full index, whatever the delta setting");
            assertArrayEquals(b("a1"), store.read(A).orElseThrow());
            assertArrayEquals(b("b1"), store.read(B).orElseThrow());
            assertFalse(store.has(C));
            assertEquals(List.of(4L, 3L, 2L, 1L), store.rollbackPoints());

            final AwfWorldStore reopened = AwfWorldStore.open(root, WorldRole.VANILLA, 4, deltas);
            assertEquals(4, reopened.generation());
            assertEquals(Set.of(A, B), reopened.keys());
            assertArrayEquals(b("b1"), reopened.read(B).orElseThrow());

            // A rollback can itself be undone while the later generation is retained.
            reopened.rollbackTo(3);
            assertEquals(Set.of(B, C), reopened.keys());
            assertArrayEquals(b("b2"), reopened.read(B).orElseThrow());
            assertArrayEquals(b("c3"), reopened.read(C).orElseThrow());
            assertTrue(AwfWorldStore.open(root, WorldRole.VANILLA, 4, deltas).deleted().isEmpty());
        }
    }

    @Test
    void aRollbackToADeletionKeepsTheTombstone() throws Exception {
        final AwfWorldStore store = history(AwfWorldStore.open(this.dir, WorldRole.VANILLA, 3, true));
        store.rollbackTo(2);
        assertEquals(Set.of(C), store.deleted(), "the deletion shadows the base again");
        assertEquals(Set.of(A, B), store.keys());
    }

    @Test
    void onlyRetainedGenerationsAreRollbackPointsAndARefusalChangesNothing() throws Exception {
        final AwfWorldStore store = history(AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2));
        assertEquals(List.of(3L, 2L), store.rollbackPoints(), "generation 1 fell out of retention");
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> store.rollbackTo(1));
        assertTrue(refused.getMessage().contains("[3, 2]"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> store.rollbackTo(9));
        assertNull(store.rollbackTo(3), "rolling back to the current generation commits nothing");
        assertEquals(3, store.generation());
        assertEquals(3, AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2).generation());
    }

    @Test
    void aMissingObjectFailsTheRollbackBeforeItsCommitPoint() throws Exception {
        final AwfWorldStore store = history(AwfWorldStore.open(this.dir, WorldRole.VANILLA, 3));
        final String a1 = ObjectStore.name(b("a1"));
        Files.delete(this.dir.resolve("objects").resolve(a1.substring(0, 2)).resolve(a1));
        assertThrows(IOException.class, () -> store.rollbackTo(1));
        assertEquals(3, store.generation(), "nothing was committed");
        assertEquals(Set.of(B, C), store.keys());
        assertEquals(3, AwfWorldStore.open(this.dir, WorldRole.VANILLA, 3).generation());
    }

    @Test
    void readOnlyAndClosedStoresRefuseARollback() throws Exception {
        history(AwfWorldStore.open(this.dir, WorldRole.VANILLA, 3));
        final AwfWorldStore readOnly = AwfWorldStore.open(this.dir, WorldRole.READ_ONLY, 3);
        assertEquals(List.of(3L, 2L, 1L), readOnly.rollbackPoints(), "listing is a read");
        assertThrows(IllegalStateException.class, () -> readOnly.rollbackTo(1));
        final AwfWorldStore closed = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 3);
        closed.close();
        assertThrows(IllegalStateException.class, () -> closed.rollbackTo(1));
        assertEquals(3, AwfWorldStore.open(this.dir, WorldRole.VANILLA, 3).generation());
    }

    @Test
    void laterCommitsCollectOnlyWhatNoRetainedGenerationReferences() throws Exception {
        final AwfWorldStore store = history(AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, true));
        store.rollbackTo(2);                                   // state: b2, c deleted
        store.commit(Map.of(A, b("a5")), Set.of(), PersistenceMode.INCREMENTAL);
        // Retained now: 5 (a5, b2) and 4 (b2). c3 (only in 3) is gone; b2 is kept.
        assertFalse(Files.exists(objectPath("c3")), "the rolled-back generation's own object is collected");
        assertTrue(Files.exists(objectPath("b2")));
        assertTrue(Files.exists(objectPath("a5")));
        final AwfWorldStore reopened = AwfWorldStore.open(this.dir, WorldRole.VANILLA, 2, true);
        assertArrayEquals(b("b2"), reopened.read(B).orElseThrow());
        assertArrayEquals(b("a5"), reopened.read(A).orElseThrow());
    }

    private Path objectPath(final String raw) {
        final String name = ObjectStore.name(b(raw));
        return this.dir.resolve("objects").resolve(name.substring(0, 2)).resolve(name);
    }
}
