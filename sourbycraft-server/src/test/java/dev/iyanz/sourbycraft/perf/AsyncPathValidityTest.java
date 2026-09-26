package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A stale async path result is never applied. */
class AsyncPathValidityTest {

    @Test
    void aReplacedEntityIsADifferentSubjectEvenIfEqual() {
        final Object mob = new Object();
        assertTrue(AsyncPathValidity.sameSubject(mob, mob));
        // Equality is not enough: a transferred entity may compare equal by id.
        final String a = new String("entity-7");
        final String b = new String("entity-7");
        assertFalse(AsyncPathValidity.sameSubject(a, b));
        assertFalse(AsyncPathValidity.sameSubject(null, mob));
    }

    @Test
    void aPathReplacedOrClearedDuringTheSolveMakesTheResultStale() {
        final Object requested = new Object();
        assertTrue(AsyncPathValidity.stillRequested(requested, requested));
        assertFalse(AsyncPathValidity.stillRequested(new Object(), requested), "recomputed or retargeted");
        assertFalse(AsyncPathValidity.stillRequested(null, requested), "stopped");
        assertTrue(AsyncPathValidity.stillRequested(null, null), "no path before, none now: still the same request");
    }
}
