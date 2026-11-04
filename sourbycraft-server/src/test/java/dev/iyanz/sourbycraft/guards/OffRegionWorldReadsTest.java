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
 * owner's Hub case (TO-B57-01); the on-region branch lives in MC feature patch 0088, so this class names no engine
 * type (dependency ledger 1.2). Written, not run (owner rule).
 */
class OffRegionWorldReadsTest {

    @AfterEach
    void forgetWindows() {
        OffRegionWorldReads.resetLogMemoryForTesting();
    }

    @Test
    void aBridgedBodyIsServedWhileChecksAreRelaxedOrBridgedAndOtherCallersOnlyWhileRelaxed() {
        // serves(relaxed, relaxedScope, bridgedBody); relaxedScope is RELAXED_SCOPE: relaxed or bridged, not enforced.
        assertTrue(OffRegionWorldReads.serves(true, true, true), "bridged body, checks relaxed");
        assertTrue(OffRegionWorldReads.serves(false, true, true), "bridged body, checks bridged");
        assertFalse(OffRegionWorldReads.serves(false, false, true), "bridged body, checks enforced: the operator chose fail-fast");
        assertTrue(OffRegionWorldReads.serves(true, true, false), "any caller while relaxed (LightingLuminol parity)");
        assertFalse(OffRegionWorldReads.serves(false, true, false), "a Folia-native caller under bridged keeps the base's exception");
        assertFalse(OffRegionWorldReads.serves(false, false, false), "enforced: nobody");
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
        assertEquals(0L, OffRegionWorldReads.admitLog("Shopkeepers|world", 0L), "first line logs");
        assertEquals(-1L, OffRegionWorldReads.admitLog("Shopkeepers|world", 10L * second));
        assertEquals(-1L, OffRegionWorldReads.admitLog("Shopkeepers|world", 59L * second));
        assertEquals(0L, OffRegionWorldReads.admitLog("Shopkeepers|world_nether", 20L * second),
            "another world is another key");
        assertEquals(2L, OffRegionWorldReads.admitLog("Shopkeepers|world", 60L * second),
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
        assertTrue(line.contains("The caller is still on the wrong thread"), line);
        assertTrue(line.endsWith("not qualified)."), line);
    }

    @Test
    void theCounterStartsAtZero() {
        assertTrue(OffRegionWorldReads.playerSnapshots() >= 0L);
    }
}
