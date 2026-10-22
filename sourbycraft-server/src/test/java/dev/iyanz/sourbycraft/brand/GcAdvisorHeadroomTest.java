package dev.iyanz.sourbycraft.brand;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** A heap sized against a container limit that leaves no room for the rest of the JVM. */
public class GcAdvisorHeadroomTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void aHeapFillingItsContainerIsWarnedAbout() {
        // The shape a Pterodactyl allocation produces: 10 GiB limit, MaxRAMPercentage=95, and a
        // heap that leaves nothing for metaspace, code cache, thread stacks or direct buffers.
        final var warnings = GcAdvisor.headroom(10 * GIB, (long) (10.5 * GIB));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("swap"), "the consequence must be named");
        assertTrue(warnings.get(0).contains("MaxRAMPercentage"), "and the flag that caused it");
    }

    @Test
    void aHeapWithRoomToSpareIsNotWarnedAbout() {
        assertTrue(GcAdvisor.headroom(8 * GIB, 10 * GIB).isEmpty());
        assertTrue(GcAdvisor.headroom(4 * GIB, 10 * GIB).isEmpty());
    }

    @Test
    void theBoundaryIsNotWarnedAbout() {
        // Exactly at the limit is acceptable; past it is not.
        final long limit = 10 * GIB;
        assertTrue(GcAdvisor.headroom((long) (limit * GcAdvisor.HEAP_SHARE_LIMIT), limit).isEmpty());
        assertFalse(GcAdvisor.headroom((long) (limit * GcAdvisor.HEAP_SHARE_LIMIT) + GIB, limit).isEmpty());
    }

    @Test
    void anUnknownLimitIsNotGuessedAt() {
        // Outside a container, or where the cgroup files cannot be read, there is nothing to
        // compare against -- and inventing a warning from a missing number is worse than silence.
        assertTrue(GcAdvisor.headroom(8 * GIB, -1L).isEmpty());
        assertTrue(GcAdvisor.headroom(8 * GIB, 0L).isEmpty());
        assertTrue(GcAdvisor.headroom(-1L, 10 * GIB).isEmpty());
    }
}
