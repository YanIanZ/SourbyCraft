package dev.iyanz.sourbycraft.async;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchdogTest {

    @Test
    void deadlineFromMultiplier() {
        Watchdog wd = new Watchdog(5.0);
        wd.recordCompletion(20);
        wd.recordCompletion(40);
        long d = wd.deadlineMs();
        assertTrue(d >= 100, "Deadline at least the min floor of 100ms");
        assertEquals(150L, d);
    }

    @Test
    void deadlineMinFloor() {
        Watchdog wd = new Watchdog(5.0);
        wd.recordCompletion(1);
        wd.recordCompletion(1);
        assertEquals(100L, wd.deadlineMs());
    }

    @Test
    void deadlineWhenNoSamplesYet() {
        Watchdog wd = new Watchdog(5.0);
        assertEquals(100L, wd.deadlineMs());
    }
}
