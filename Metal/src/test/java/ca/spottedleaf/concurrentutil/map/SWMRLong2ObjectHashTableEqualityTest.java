package ca.spottedleaf.concurrentutil.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the upstream LeafPile equality fix (same value, distinct reference).
 *
 * <p>Do not run these tests with concurrent mutations; SWMR equality explicitly
 * does not promise a consistent comparison when a writer is modifying the table.</p>
 */
final class SWMRLong2ObjectHashTableEqualityTest {

    @Test
    void equalValuesInDifferentObjectsCompareEqual() {
        final SWMRLong2ObjectHashTable<String> first = new SWMRLong2ObjectHashTable<>();
        final SWMRLong2ObjectHashTable<String> second = new SWMRLong2ObjectHashTable<>();

        first.put(-1L, new String(new char[] {'a', 'l', 'p', 'h', 'a'}));
        second.put(-1L, new String(new char[] {'a', 'l', 'p', 'h', 'a'}));
        first.put(99L, "beta");
        second.put(99L, "beta");

        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void sameKeyAndDifferentValueCompareUnequal() {
        final SWMRLong2ObjectHashTable<String> first = new SWMRLong2ObjectHashTable<>();
        final SWMRLong2ObjectHashTable<String> second = new SWMRLong2ObjectHashTable<>();

        first.put(42L, "first");
        second.put(42L, "second");

        assertNotEquals(first, second);
        assertNotEquals(second, first);
    }

    @Test
    void differentKeysAndEmptyMapsCompareAsExpected() {
        final SWMRLong2ObjectHashTable<String> first = new SWMRLong2ObjectHashTable<>();
        final SWMRLong2ObjectHashTable<String> second = new SWMRLong2ObjectHashTable<>();

        assertEquals(first, second);
        assertTrue(first.equals(first));

        first.put(-2L, "same");
        second.put(2L, "same");
        assertFalse(first.equals(second));
        assertFalse(second.equals(first));
        assertNotEquals(first, null);
        assertNotEquals(first, "not a hash table");
    }
}
