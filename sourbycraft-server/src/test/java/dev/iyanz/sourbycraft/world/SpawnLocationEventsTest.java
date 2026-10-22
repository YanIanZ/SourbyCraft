package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpawnLocationEventsTest {

    @Test
    void theLegacyEventFiresOnceAndOnlyWithListeners() {
        assertTrue(SpawnLocationEvents.shouldFireLegacy(false, 1));
        assertFalse(SpawnLocationEvents.shouldFireLegacy(true, 1), "already fired for this join");
        assertFalse(SpawnLocationEvents.shouldFireLegacy(false, 0), "no listener: no early player is created");
    }

    /** Join marks per level name, plus which levels are unloading. */
    private static final class Marks implements SpawnLocationEvents.JoinMarks<String> {
        final Set<String> marks = new HashSet<>();
        final Set<String> unloading = new HashSet<>();
        final List<String> ops = new ArrayList<>();

        @Override
        public boolean markIfLoaded(final String level, final String player) {
            this.ops.add("mark " + level);
            if (this.unloading.contains(level)) return false;
            this.marks.add(level + "/" + player);
            return true;
        }

        @Override
        public void unmark(final String level, final String player) {
            this.ops.add("unmark " + level);
            this.marks.remove(level + "/" + player);
        }
    }

    @Test
    void anUnchangedWorldTouchesNoMark() {
        final Marks marks = new Marks();
        marks.marks.add("world/Steve");
        assertSame("world", SpawnLocationEvents.moveJoinMark("world", "world", "Steve", marks, msg -> {}));
        assertEquals(List.of(), marks.ops);
    }

    @Test
    void aChangedWorldMovesTheMarkNewFirstSoTheJoinIsNeverUnmarked() {
        final Marks marks = new Marks();
        marks.marks.add("world/Steve");
        assertEquals("lobby", SpawnLocationEvents.moveJoinMark("world", "lobby", "Steve", marks, msg -> {}));
        assertEquals(List.of("mark lobby", "unmark world"), marks.ops);
        assertEquals(Set.of("lobby/Steve"), marks.marks);
    }

    @Test
    void anUnloadingTargetKeepsTheMarkedWorldAndSaysSo() {
        final Marks marks = new Marks();
        marks.marks.add("world/Steve");
        marks.unloading.add("arena");
        final List<String> warnings = new ArrayList<>();
        assertEquals("world", SpawnLocationEvents.moveJoinMark("world", "arena", "Steve", marks, warnings::add));
        assertEquals(Set.of("world/Steve"), marks.marks);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("arena") && warnings.get(0).contains("Steve"), warnings.get(0));
    }
}
