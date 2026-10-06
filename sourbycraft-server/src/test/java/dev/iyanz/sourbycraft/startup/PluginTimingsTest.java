package dev.iyanz.sourbycraft.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PluginTimingsTest {

    @Test
    void unknownPluginsReadAsZero() {
        final PluginTimings timings = new PluginTimings(4);
        final PluginTimings.Timing none = timings.of("Missing");
        assertEquals(0L, none.loadNanos());
        assertEquals(0L, none.enableNanos());
        assertEquals(0L, timings.of(null).totalNanos());
        assertEquals(0, timings.size(), "reading never creates an entry");
    }

    @Test
    void loadAndEnableAreKeptPerPluginAndEnableKeepsTheLatestRun() {
        final PluginTimings timings = new PluginTimings(4);
        timings.load("A", 3_000_000L);
        timings.enable("A", 7_000_000L);
        timings.load("B", 1_000_000L);
        assertEquals(3L, timings.of("A").loadMillis());
        assertEquals(7L, timings.of("A").enableMillis());
        assertEquals(1L, timings.of("B").loadMillis());
        assertEquals(0L, timings.of("B").enableMillis(), "B never enabled");
        timings.enable("A", 2_000_000L);
        assertEquals(2L, timings.of("A").enableMillis(), "re-enable replaces, does not accumulate");
        assertEquals(3L, timings.of("A").loadMillis(), "enable does not touch load");
        timings.load("C", -5L);
        assertEquals(0L, timings.of("C").loadNanos(), "a negative clock delta is clamped");
        timings.load(null, 5L);
        assertEquals(3, timings.size());
    }

    @Test
    void slowestOrdersByTotalAndHonoursTheLimit() {
        final PluginTimings timings = new PluginTimings(16);
        timings.load("Fast", 1L);
        timings.load("Slow", 50L);
        timings.enable("Slow", 50L);
        timings.enable("Mid", 60L);
        timings.load("TieA", 10L);
        timings.load("TieB", 10L);
        assertEquals(List.of("Slow", "Mid", "TieA", "TieB", "Fast"),
            timings.slowest(10).stream().map(PluginTimings.Timing::plugin).toList());
        assertEquals(2, timings.slowest(2).size());
        assertEquals(0, timings.slowest(-1).size());
    }

    @Test
    void theRegistryIsBoundedAndCountsDroppedNames() {
        final PluginTimings timings = new PluginTimings(2);
        timings.load("A", 1L);
        timings.load("B", 1L);
        timings.load("C", 1L);
        timings.enable("A", 9L);
        assertEquals(2, timings.size());
        assertEquals(1L, timings.dropped());
        assertEquals(0L, timings.of("C").loadNanos());
        assertEquals(9L, timings.of("A").enableNanos(), "known names still update when full");
    }

    @Test
    void concurrentRecordingKeepsTheBoundAndLosesNoPhase() throws Exception {
        final int capacity = 64;
        final PluginTimings timings = new PluginTimings(capacity);
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < 8; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < 200; i++) {
                        timings.load("P" + i, 5L);
                        timings.enable("P" + i, 7L);
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(capacity, timings.size());
        for (final PluginTimings.Timing timing : timings.slowest(capacity)) {
            assertEquals(5L, timing.loadNanos(), timing.plugin());
            assertEquals(7L, timing.enableNanos(), timing.plugin());
        }
        assertTrue(timings.dropped() > 0);
    }
}
