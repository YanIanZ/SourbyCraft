package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class UndrainedQueueTest {

    private final List<String> errors = new ArrayList<>();
    private final Queue<Runnable> queue = new UndrainedQueue(this.errors::add);

    private void addFromHere() {
        this.queue.add(() -> { });
    }

    @Test
    void addingFailsLoudlyAndNamesTheCaller() {
        final UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, this::addFromHere);
        assertTrue(e.getMessage().startsWith(RegionRefusal.UNDRAINED_QUEUE.message()) && e.getMessage().contains("(added by "), e.getMessage());
        assertTrue(e.getMessage().contains(UndrainedQueueTest.class.getName() + "#addFromHere"), "the caller is the code that called add, not AbstractQueue: " + e.getMessage());
    }

    @Test
    void offerAndAddAllAreRefusedToo() {
        assertThrows(UnsupportedOperationException.class, () -> this.queue.offer(() -> { }));
        assertThrows(UnsupportedOperationException.class, () -> this.queue.addAll(List.of(() -> { })));
    }

    @Test
    void eachCallerIsLoggedOnceAtErrorEvenWhenItRepeats() {
        for (int i = 0; i < 5; i++) {
            assertThrows(UnsupportedOperationException.class, this::addFromHere);
        }
        assertEquals(1, this.errors.size(), this.errors.toString());
        assertThrows(UnsupportedOperationException.class, () -> this.queue.offer(() -> { }));
        assertEquals(2, this.errors.size(), "a different call site is a different caller");
        assertTrue(this.errors.get(0).contains("#addFromHere"));
    }

    @Test
    void readingIsHarmlessAndAlwaysEmpty() {
        assertNull(this.queue.poll());
        assertNull(this.queue.peek());
        assertTrue(this.queue.isEmpty());
        assertEquals(0, this.queue.size());
        assertFalse(this.queue.iterator().hasNext());
        assertThrows(java.util.NoSuchElementException.class, this.queue::remove);
    }

    @Test
    void theServersQueueIsOne() {
        assertInstanceOf(UndrainedQueue.class, UndrainedQueue.create());
    }
}
