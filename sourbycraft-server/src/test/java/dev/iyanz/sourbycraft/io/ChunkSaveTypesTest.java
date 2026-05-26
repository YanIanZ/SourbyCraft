package dev.iyanz.sourbycraft.io;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChunkSaveTypesTest {

    @Test
    void snapshotImmutable() {
        ChunkSaveSnapshot snap = new ChunkSaveSnapshot(
            "world", 1, 2,
            List.of(new ChunkSaveSnapshot.Entry(10, 20, new byte[]{1, 2, 3})));
        assertEquals("world", snap.worldId());
        assertEquals(1, snap.regionX());
        assertEquals(2, snap.regionZ());
        assertEquals(1, snap.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> snap.entries().add(null));
    }

    @Test
    void diffSuccessAndFailure() {
        ChunkSaveDiff ok = ChunkSaveDiff.success("world", 1, 2, 5);
        assertTrue(ok.success());
        assertEquals(5, ok.chunksWritten());
        assertNull(ok.failureReason());

        ChunkSaveDiff fail = ChunkSaveDiff.failure("world", 1, 2, "disk full");
        assertFalse(fail.success());
        assertEquals(0, fail.chunksWritten());
        assertEquals("disk full", fail.failureReason());
    }
}
