package dev.iyanz.sourbycraft.async;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple failure-threshold circuit breaker.
 * Trips after {@code failureThreshold} consecutive failures.
 * Cools down for {@code cooldownMs} before {@link #tryReset()} succeeds.
 */
public final class CircuitBreaker {

    private final int failureThreshold;
    private final long cooldownMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong trippedAtMs = new AtomicLong(0);

    public CircuitBreaker(int failureThreshold, long cooldownMs) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold >= 1");
        if (cooldownMs < 0) throw new IllegalArgumentException("cooldownMs >= 0");
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    public boolean tripped() {
        return trippedAtMs.get() != 0;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
    }

    public void recordFailure() {
        int n = consecutiveFailures.incrementAndGet();
        if (n >= failureThreshold && trippedAtMs.get() == 0) {
            trippedAtMs.set(System.currentTimeMillis());
        }
    }

    /** Returns true and resets the breaker if cooldown has elapsed; otherwise false. */
    public boolean tryReset() {
        long t = trippedAtMs.get();
        if (t == 0) return false;
        if (System.currentTimeMillis() - t < cooldownMs) return false;
        trippedAtMs.set(0);
        consecutiveFailures.set(0);
        return true;
    }
}
