package dev.iyanz.aurora.engine.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OBS-14 engine util: {@link LockedReference}, {@link CanonicalReference} and {@link CollectingList}. */
class ReferenceHoldersTest {

    @Test
    void lockedReferenceBasics() {
        final LockedReference<String> reference = new LockedReference<>();
        assertFalse(reference.isSet());
        assertEquals(Optional.empty(), reference.asOptional());

        reference.swapValue("a");
        assertEquals("a", reference.getValue());
        reference.swapValue(previous -> previous + "b");
        assertEquals("abc", reference.swapAndGet(previous -> previous + "c"));
        assertEquals("abc", reference.getValue());
        assertEquals(Optional.of("abc"), reference.asOptional());

        reference.unset();
        assertFalse(reference.isSet());
        assertNull(reference.getValue());
        assertEquals("x", new LockedReference<>("x").getValue());
    }

    @Test
    void lockedReferenceUpdatesFromTwoWritersAreNotLost() throws Exception {
        final LockedReference<Integer> counter = new LockedReference<>(0);
        final int perWriter = 10_000;
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final List<Future<?>> writers = new ArrayList<>();
            for (int w = 0; w < 2; w++) {
                writers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWriter; i++) counter.swapValue(value -> value + 1);
                    return null;
                }));
            }
            start.countDown();
            for (final Future<?> writer : writers) writer.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2 * perWriter, counter.getValue());
    }

    @Test
    void canonicalReferenceIsSetOnce() {
        final CanonicalReference<String> reference = new CanonicalReference<>();
        assertFalse(reference.isSet());
        assertNull(reference.valueSafe());
        assertThrows(IllegalStateException.class, reference::value);

        assertEquals("a", reference.getOrSet(() -> "a"));
        assertEquals("a", reference.getOrSet(() -> "b"), "supplier not used once set");
        assertTrue(reference.isSet());
        assertThrows(IllegalStateException.class, () -> reference.setValue("c"));
        assertEquals("a", reference.value());
        assertEquals("pre", new CanonicalReference<>("pre").value());
    }

    @Test
    void collectingListOperations() {
        final CollectingList<String> list = new CollectingList<String>().add("b").add("a");
        assertSame(list, list.addAllNotPresent(List.of("a", "c")));
        assertEquals(List.of("b", "a", "c"), new ArrayList<>(list.copyOf()));
        assertEquals(3, list.size());

        list.sort(Comparator.naturalOrder());
        assertEquals("a", list.getFirst());
        assertEquals("c", list.getLast());
        assertEquals("b", list.get(1));

        final java.util.Collection<String> copy = list.copyOf();
        list.remove("b");
        assertEquals(3, copy.size(), "copyOf is detached");
        assertEquals(2, list.size());

        final CollectingList<Object> objects = new CollectingList<Object>().add("x").add(1);
        assertThrows(ClassCastException.class, () -> objects.castAllTo(String.class));
        assertEquals("x", new CollectingList<Object>().add("x").castAllTo(String.class).get(0));

        assertTrue(list.clear().isEmpty());
        assertFalse(list.iterator().hasNext());
    }
}
