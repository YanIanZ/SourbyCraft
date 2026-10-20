package dev.iyanz.sourbycraft.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Persisted release policy: revisions only rise, yanks only accumulate, corrupt state is refused. */
class ReleasePolicyStateTest {

    @TempDir Path dir;

    @Test
    void aMissingFileIsEmptyAndARoundTripKeepsEverything() throws Exception {
        ReleasePolicyState s = ReleasePolicyState.load(dir);
        assertEquals(0, s.revision("v26.2-r48"));
        assertTrue(s.yanked().isEmpty());
        s.acceptRevision("v26.2-r48", 7);
        s.addYanks(Set.of("v26.2-r47"));
        s.save(dir);
        ReleasePolicyState back = ReleasePolicyState.load(dir);
        assertEquals(7, back.revision("v26.2-r48"));
        assertEquals(Set.of("v26.2-r47"), back.yanked());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temporary file left behind");
        }
    }

    @Test
    void revisionsNeverDropAndConcurrentSavesMerge() throws Exception {
        ReleasePolicyState a = ReleasePolicyState.load(dir);
        ReleasePolicyState b = ReleasePolicyState.load(dir);
        a.acceptRevision("v26.2-r48", 9);
        a.addYanks(Set.of("v26.2-r46"));
        a.save(dir);
        b.acceptRevision("v26.2-r48", 3);          // stale copy loaded before a's save
        b.acceptRevision("v26.2-r49", 4);
        b.addYanks(Set.of("v26.2-r47"));
        b.save(dir);
        ReleasePolicyState merged = ReleasePolicyState.load(dir);
        assertEquals(9, merged.revision("v26.2-r48"));
        assertEquals(4, merged.revision("v26.2-r49"));
        assertEquals(Set.of("v26.2-r46", "v26.2-r47"), merged.yanked());
        merged.acceptRevision("v26.2-r48", 2);
        assertEquals(9, merged.revision("v26.2-r48"));
    }

    @Test
    void everyCorruptShapeIsRefused() throws Exception {
        for (String bad : new String[] {
            "{not json", "[]", "{}", "{\"schema\":2,\"revisions\":{},\"yanked\":[]}",
            "{\"schema\":1,\"yanked\":[]}", "{\"schema\":1,\"revisions\":{},\"yanked\":{}}",
            "{\"schema\":1,\"revisions\":{\"t\":0},\"yanked\":[]}", "{\"schema\":1,\"revisions\":{\"t\":\"x\"},\"yanked\":[]}",
            "{\"schema\":1,\"revisions\":{},\"yanked\":[1]}"}) {
            Files.writeString(dir.resolve(ReleasePolicyState.FILE), bad);
            assertThrows(ReleasePolicyState.CorruptStateException.class, () -> ReleasePolicyState.load(dir), bad);
            assertThrows(ReleasePolicyState.CorruptStateException.class, () -> ReleasePolicyState.empty().save(dir),
                "a save never overwrites corrupt state silently: " + bad);
        }
    }
}
