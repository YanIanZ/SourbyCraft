package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import org.junit.jupiter.api.Test;

class PerfRamViewTest {

    private static SourbyMetrics metrics() {
        return () -> CommandTestSupport.healthy(20.0, 8.0);
    }

    @Test
    void ramViewReportsHeapUsageAndMarksUncollectedFieldsAsUnavailable() {
        final String text = CommandTestSupport.plain(PerfCommand.render(metrics(), "memory"));

        assertTrue(text.contains("Heap usage: 50.0% of maximum"), text);
        assertTrue(text.contains("Allocation rate: n/a (allocation rate is not collected)"), text);
        assertTrue(text.contains("GC young / old collections: n/a (all pools are aggregated)"), text);
        assertTrue(text.contains("Last GC pause: n/a (only the mean per cycle is collected)"), text);
    }

    @Test
    void ramViewStillShowsTheMeasuredHeapFacts() {
        final String text = CommandTestSupport.plain(PerfCommand.render(metrics(), "memory"));

        assertTrue(text.contains("Heap used / committed / maximum"), text);
        assertTrue(text.contains("Non-heap"), text);
        assertTrue(text.contains("Process RSS"), text);
        assertTrue(text.contains("GC collections / cumulative collection time"), text);
    }

    private static String ram(final long used, final long committed, final long max) {
        final var snap = CommandTestSupport.healthy(20.0, 8.0).runtime(CommandTestSupport.runtime(
            "heapUsedBytes", used, "heapCommittedBytes", committed, "heapMaxBytes", max));
        return CommandTestSupport.plain(PerfCommand.render(() -> snap, "memory"));
    }

    @Test
    void anUndefinedMaximumHeapIsUnavailableNotAPercentage() {
        // MemoryMXBean reports max = -1 when the JVM defines no maximum.
        final String text = ram(512L << 20, 768L << 20, -1L);
        assertTrue(text.contains("Heap usage: unavailable"), text);
        assertTrue(text.contains("/ unavailable"), "the maximum itself is shown as unavailable: " + text);
        assertFalse(text.contains("% of maximum"), text);
    }

    @Test
    void aZeroMaximumHeapDoesNotDivideByZero() {
        final String text = ram(512L << 20, 768L << 20, 0L);
        assertTrue(text.contains("Heap usage: unavailable"), text);
        assertFalse(text.contains("Infinity") || text.contains("NaN"), text);
    }

    @Test
    void aNegativeUsedHeapIsUnavailableNotANegativePercentage() {
        final String text = ram(-1L, 768L << 20, 1024L << 20);
        assertTrue(text.contains("Heap usage: unavailable"), text);
        assertTrue(text.contains("Heap used / committed / maximum: unavailable /"), text);
        assertTrue(text.lines().filter(l -> l.contains("Heap")).noneMatch(l -> l.contains("-")),
            "no negative figure on the heap lines: " + text);
    }
}
