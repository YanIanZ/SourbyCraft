package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** The slowest region is the highest five-second average, not the highest single tick. */
class SlowRegionTest {

    @Test
    void keepsTheHigherAverage() {
        final SlowRegion steady = new SlowRegion(1, 10, 100, 40.0, 45.0, 100);
        final SlowRegion spiky = new SlowRegion(1, 11, 101, 20.0, 400.0, 100);
        assertSame(steady, SlowRegion.slower(steady, spiky));
        assertSame(steady, SlowRegion.slower(spiky, steady));
    }

    @Test
    void nullIsNoCandidate() {
        final SlowRegion only = new SlowRegion(1, 10, 100, 5.0, 6.0, 10);
        assertSame(only, SlowRegion.slower(null, only));
        assertSame(only, SlowRegion.slower(only, null));
        assertNull(SlowRegion.slower(null, null));
    }
}
