package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import org.junit.jupiter.api.Test;

class PerfTickViewTest {

    private static SourbyMetrics withWindow(final double maximum, final double running, final int count) {
        final var window = CommandTestSupport.window("worstTps", 20.0, "worstAverageMspt", 2.8,
            "estimatedP95Mspt", 5.0, "estimatedP99Mspt", 7.0, "maximumMspt", maximum,
            "longestInProgressTickMspt", running, "ticksInProgress", count);
        final var snap = CommandTestSupport.healthy(20.0, 2.8).window(MetricWindow.FIVE_SECONDS, window);
        return () -> snap;
    }

    @Test
    void aCompletedMaximumIsPrintedWithoutAnInProgressRow() {
        final String text = CommandTestSupport.plain(PerfCommand.render(withWindow(12.4, Double.NaN, 0), "tick"));

        assertTrue(text.contains("Recent maximum: 12.4ms\n"), text);
        assertFalse(text.contains("Tick in progress"), text);
        assertFalse(text.contains("still running"), text);
    }

    @Test
    void aTickRunningForSecondsIsNamedAndNeverPassesForAMeasurement() {
        final String text = CommandTestSupport.plain(PerfCommand.render(withWindow(12_000.0, 12_000.0, 1), "tick"));

        assertTrue(text.contains("Recent maximum: 12000.0ms (includes a tick still running for 12.0 s)"), text);
        assertTrue(text.contains("Tick in progress: 1 tick running, longest 12.0 s so far (not finished"), text);
        assertTrue(text.contains("/perf scheduler"), text);
    }

    @Test
    void severalRunningTicksArePluralised() {
        final String text = CommandTestSupport.plain(PerfCommand.render(withWindow(900.0, 900.0, 3), "tick"));

        assertTrue(text.contains("3 ticks running, longest 900ms so far"), text);
    }

    @Test
    void aTickShorterThanOneBudgetIsTheNormalStateOfABusyRegionAndIsNotMentioned() {
        final String text = CommandTestSupport.plain(PerfCommand.render(withWindow(12.4, 3.0, 1), "tick"));

        assertFalse(text.contains("Tick in progress"), text);
        assertFalse(text.contains("still running"), text);
    }

    @Test
    void durationsAreReadable() {
        assertEquals("812ms", TickInProgress.duration(812.4));
        assertEquals("5.0 s", TickInProgress.duration(5_000.0));
        assertEquals("119.0 s", TickInProgress.duration(119_000.0));
        assertEquals("5 min 3 s", TickInProgress.duration(303_000.0));
        assertEquals("2 h 5 min", TickInProgress.duration(7_500_000.0));
        assertEquals("unavailable", TickInProgress.duration(Double.NaN));
    }

    @Test
    void msptRepeatsTheSameNoteOnItsMaximumLine() {
        final String text = CommandTestSupport.plain(MsptCommand.render(withWindow(12_000.0, 12_000.0, 1)));

        assertTrue(text.contains("Exact recent max 12000.0ms (includes a tick still running for 12.0 s)"), text);
    }
}
