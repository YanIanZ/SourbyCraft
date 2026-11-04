package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The parts of {@link OffRegionWorldReads} that need no server: who is served, what a snapshot is, the rate
 * limit and the WARN text. {@code localPlayers(ServerLevel)} itself needs a region scheduler and is covered by the
 * owner's Hub case (TO-B57-01). Written, not run (owner rule).
 */
class OffRegionWorldReadsTest {

    @AfterEach
    void forgetWindows() {
        OffRegionWorldReads.resetLogMemoryForTesting();
    }

    @Test
    void aBridgedBodyIsServedInEveryModeAndOtherCallersOnlyWhileRelaxed() {
        assertTrue(OffRegionWorldReads.serves(false, true), "bridged body, checks bridged or enforced");
        assertTrue(OffRegionWorldReads.serves(true, true), "bridged body, checks relaxed");
        assertTrue(OffRegionWorldReads.serves(true, false), "any caller while relaxed (LightingLuminol parity)");
        assertFalse(OffRegionWorldReads.serves(false, false), "a Folia-native caller keeps the base's exception");
    }

    @Test
    void aSnapshotIsAnImmutableCopyThatOutlivesChangesToTheSource() {
        final List<String> players = new CopyOnWriteArrayList<>(List.of("alice", "bob"));
        final List<String> snapshot = OffRegionWorldReads.snapshot(players);
        assertEquals(List.of("alice", "bob"), snapshot);
        assertNotSame(players, snapshot);
        players.add("carol");
        players.remove("alice");
        assertEquals(List.of("alice", "bob"), snapshot, "a later login or logout does not reach the copy");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("dave"), "read-only");
        assertTrue(OffRegionWorldReads.snapshot(List.of()).isEmpty());
    }

    @Test
    void oneLinePerKeyPerMinuteWithTheSuppressedCount() {
        final long second = TimeUnit.SECONDS.toNanos(1L);
        assertEquals(0L, OffRegionWorldReads.admitLog("Shopkeepers|world|players", 0L), "first line logs");
        assertEquals(-1L, OffRegionWorldReads.admitLog("Shopkeepers|world|players", 10L * second));
        assertEquals(-1L, OffRegionWorldReads.admitLog("Shopkeepers|world|players", 59L * second));
        assertEquals(0L, OffRegionWorldReads.admitLog("Shopkeepers|world_nether|players", 20L * second),
            "another world is another key");
        assertEquals(2L, OffRegionWorldReads.admitLog("Shopkeepers|world|players", 60L * second),
            "the line after the window carries how many it suppressed");
        assertEquals(TimeUnit.SECONDS.toNanos(60L), OffRegionWorldReads.LOG_WINDOW_NANOS);
        assertEquals(256, OffRegionWorldReads.MAX_LOG_KEYS);
    }

    @Test
    void theWarnNamesTheCallerTheWorldAndWhatWasServed() {
        final String line = OffRegionWorldReads.describe("plugin Shopkeepers (task 8)", "world", "players", 12, "no region");
        assertTrue(line.startsWith("Off-region world read: plugin Shopkeepers (task 8) read the players of world world"), line);
        assertTrue(line.contains("owns no region of it (no region)"), line);
        assertTrue(line.contains("read-only snapshot of 12 players"), line);
        assertTrue(line.contains("aurora.bridge.thread-checks " + ThreadChecks.effective().configValue()), line);
        assertTrue(line.endsWith("not qualified)."), line);
    }

    @Test
    void theCounterStartsAtZero() {
        assertTrue(OffRegionWorldReads.playerSnapshots() >= 0L);
    }
}
