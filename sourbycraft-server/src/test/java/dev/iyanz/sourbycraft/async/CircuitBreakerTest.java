package dev.iyanz.sourbycraft.async;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void initiallyClosed() {
        CircuitBreaker cb = new CircuitBreaker(3, 1000);
        assertFalse(cb.tripped());
    }

    @Test
    void tripsAfterThreshold() {
        CircuitBreaker cb = new CircuitBreaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        assertFalse(cb.tripped(), "Not yet at threshold");
        cb.recordFailure();
        assertTrue(cb.tripped(), "Trips on threshold-th failure");
    }

    @Test
    void successResetsCounter() {
        CircuitBreaker cb = new CircuitBreaker(3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordFailure();
        assertFalse(cb.tripped(), "Counter reset by success, not at threshold yet");
    }

    @Test
    void tryResetReturnsTrueAfterCooldown() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(1, 50);
        cb.recordFailure();
        assertTrue(cb.tripped());
        assertFalse(cb.tryReset(), "Too early");
        Thread.sleep(80);
        assertTrue(cb.tryReset(), "Cooldown elapsed");
        assertFalse(cb.tripped(), "Reset by tryReset");
    }
}
