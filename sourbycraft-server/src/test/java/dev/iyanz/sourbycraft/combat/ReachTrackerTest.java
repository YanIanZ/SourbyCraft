package dev.iyanz.sourbycraft.combat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReachTrackerTest {

    @Test
    void recordsLastHit() {
        ReachTracker.record("PlayerA", "PlayerB", 3.42, 85, 150);
        var last = ReachTracker.last();
        assertNotNull(last);
        assertEquals("PlayerA", last.attacker());
        assertEquals("PlayerB", last.target());
        assertEquals(3.42, last.distance(), 0.01);
        assertEquals(85, last.latencyMs());
    }

    @Test
    void overwritesPreviousHit() {
        ReachTracker.record("X", "Y", 1.0, 10, 100);
        ReachTracker.record("PlayerA", "PlayerB", 3.42, 85, 150);
        var last = ReachTracker.last();
        assertEquals("PlayerA", last.attacker());
        assertEquals(85, last.latencyMs());
    }
}
