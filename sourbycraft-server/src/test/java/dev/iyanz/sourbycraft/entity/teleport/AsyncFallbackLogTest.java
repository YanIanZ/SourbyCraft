package dev.iyanz.sourbycraft.entity.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AsyncFallbackLogTest {

    private long now = 1_000L;
    private final List<String> lines = new ArrayList<>();

    private AsyncFallbackLog log(final int maxKeys) {
        return new AsyncFallbackLog(TimeUnit.MINUTES.toNanos(10), maxKeys, () -> this.now, this.lines::add);
    }

    @Test
    void thePluginsFirstFallbackIsLoggedAndRepeatsInsideTheWindowAreNot() {
        final AsyncFallbackLog log = this.log(16);
        assertTrue(log.record("Essentials", "cross-region"));
        this.now += TimeUnit.MINUTES.toNanos(9);
        assertFalse(log.record("Essentials", "cross-region"));
        assertFalse(log.record("Essentials", "cross-region"));
        assertEquals(1, this.lines.size());
        assertTrue(this.lines.get(0).contains("Essentials"), this.lines.get(0));
        assertTrue(this.lines.get(0).contains("teleportAsync"), this.lines.get(0));
        assertTrue(this.lines.get(0).contains("false"), this.lines.get(0));
    }

    @Test
    void afterTheWindowTheNextOneIsLoggedWithTheSuppressedCount() {
        final AsyncFallbackLog log = this.log(16);
        log.record("Lobby", "unloaded destination");
        log.record("Lobby", "unloaded destination");
        log.record("Lobby", "unloaded destination");
        this.now += TimeUnit.MINUTES.toNanos(10);
        assertTrue(log.record("Lobby", "other world"));
        assertEquals(2, this.lines.size());
        assertTrue(this.lines.get(1).contains("2 more"), this.lines.get(1));
    }

    @Test
    void pluginsAreLimitedIndependently() {
        final AsyncFallbackLog log = this.log(16);
        assertTrue(log.record("A", "x"));
        assertTrue(log.record("B", "x"));
        assertFalse(log.record("A", "x"));
        assertEquals(2, this.lines.size());
    }

    @Test
    void anUnknownCallerSharesOneKey() {
        final AsyncFallbackLog log = this.log(16);
        assertTrue(log.record(null, "x"));
        assertFalse(log.record(null, "x"));
        assertTrue(this.lines.get(0).contains(AsyncFallbackLog.UNKNOWN_CALLER), this.lines.get(0));
    }

    @Test
    void keysAreBoundedSoAPluginNamingSpreeCannotGrowTheMap() {
        final AsyncFallbackLog log = this.log(2);
        assertTrue(log.record("A", "x"));
        assertTrue(log.record("B", "x"));
        assertTrue(log.record("C", "x"), "the first plugin past the cap logs under the shared overflow key");
        assertFalse(log.record("D", "x"), "later ones share the overflow key's window");
        assertEquals(3, log.keys());
        assertEquals(4L, log.fallbacks());
    }

    @Test
    void theKeyCapHoldsUnderConcurrentAdmission() throws Exception {
        final AsyncFallbackLog log = new AsyncFallbackLog(TimeUnit.MINUTES.toNanos(10), 4, () -> 5L, line -> {});
        final int threads = 16;
        final java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(threads);
        final java.util.List<Thread> all = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            final Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
                for (int i = 0; i < 200; i++) log.record("p" + id + "-" + i, "x");
            });
            all.add(thread);
            thread.start();
        }
        for (final Thread thread : all) thread.join();
        assertTrue(log.keys() <= 5, "cap 4 plus the overflow key, got " + log.keys());
        assertEquals(threads * 200L, log.fallbacks());
    }
}
