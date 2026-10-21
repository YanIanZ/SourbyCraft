package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EndPlatformEventsTest {

    @Test
    void firesOnlyWhenAnEntityCausedItAndThisThreadOwnsThePlatform() {
        assertTrue(EndPlatformEvents.shouldFire(true, () -> true));
        assertFalse(EndPlatformEvents.shouldFire(true, () -> false), "another region's platform: build silently");
        assertFalse(EndPlatformEvents.shouldFire(false, () -> true), "world generation passes no entity");
    }

    @Test
    void ownershipIsNotReadWhenThereIsNoEntity() {
        final AtomicInteger reads = new AtomicInteger();
        assertFalse(EndPlatformEvents.shouldFire(false, () -> {
            reads.incrementAndGet();
            return true;
        }));
        assertTrue(reads.get() == 0);
    }
}
