package dev.iyanz.aurora.engine.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine util: {@link WeakConcurrentCollection} collection semantics with strongly-held
 * elements. Reclamation of collected referents depends on the garbage collector and is not tested
 * (a test would need {@code System.gc()} and a sleep/poll loop).
 */
class WeakConcurrentCollectionTest {

    @Test
    void addContainsRemoveAndSize() {
        final WeakConcurrentCollection<String> collection = new WeakConcurrentCollection<>();
        final String a = new String("a");
        final String b = new String("b");

        assertTrue(collection.isEmpty());
        assertTrue(collection.add(a));
        assertTrue(collection.add(b));
        assertEquals(2, collection.size());
        assertTrue(collection.contains("a"), "equality, not identity");
        assertFalse(collection.contains(null));
        assertTrue(collection.remove("a"));
        assertFalse(collection.remove("a"));
        assertFalse(collection.remove(null));
        assertEquals(List.of("b"), new ArrayList<>(collection));
        assertFalse(collection.isEmpty());
    }

    @Test
    void nullElementsAreRejected() {
        assertThrows(NullPointerException.class, () -> new WeakConcurrentCollection<String>().add(null));
    }

    @Test
    void duplicatesAreKeptAndRemovedOneAtATime() {
        final WeakConcurrentCollection<String> collection = new WeakConcurrentCollection<>();
        final String a = "a";
        collection.add(a);
        collection.add(a);

        assertEquals(2, collection.size());
        collection.remove(a);
        assertEquals(1, collection.size());
    }

    @Test
    void iteratorIsASnapshotAndSupportsRemove() {
        final WeakConcurrentCollection<String> collection = new WeakConcurrentCollection<>();
        collection.addAll(List.of("a", "b", "c"));

        final Iterator<String> iterator = collection.iterator();
        collection.add("d"); // not visible to the existing snapshot
        assertThrows(IllegalStateException.class, iterator::remove, "remove before next");
        final List<String> seen = new ArrayList<>();
        while (iterator.hasNext()) {
            final String value = iterator.next();
            seen.add(value);
            if (value.equals("b")) iterator.remove();
        }

        assertEquals(List.of("a", "b", "c"), seen);
        assertThrows(NoSuchElementException.class, iterator::next);
        assertEquals(List.of("a", "c", "d"), new ArrayList<>(collection));
    }

    @Test
    void bulkOperations() {
        final WeakConcurrentCollection<String> collection = new WeakConcurrentCollection<>();
        assertFalse(collection.addAll(List.of()));
        assertTrue(collection.addAll(List.of("a", "b", "c", "d")));
        assertTrue(collection.containsAll(List.of("a", "d")));
        assertFalse(collection.containsAll(List.of("a", "z")));

        assertTrue(collection.removeAll(List.of("a", "z")));
        assertFalse(collection.removeAll(List.of("z")));
        assertEquals(List.of("b", "c", "d"), new ArrayList<>(collection));

        assertTrue(collection.retainAll(List.of("c")));
        assertFalse(collection.retainAll(List.of("c")));
        assertArrayEquals(new Object[] {"c"}, collection.toArray());
        assertArrayEquals(new String[] {"c"}, collection.toArray(new String[0]));

        collection.clear();
        assertTrue(collection.isEmpty());
        assertEquals(0, collection.size());
        collection.compact(); // no-op on an empty collection
        assertTrue(collection.isEmpty());
    }
}
