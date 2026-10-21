package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import java.util.Locale;

/**
 * Wording for a tick that was still running when the snapshot was taken.
 *
 * <p>A running tick is not a completed measurement: its elapsed time is a lower bound of its final
 * duration. {@code /perf tick} and {@code /mspt} therefore name it instead of folding it silently
 * into "Recent maximum". Ticks that have been running for less than one tick budget are the normal
 * state of a busy region at the instant of sampling and are not mentioned.</p>
 */
final class TickInProgress {

    /** Tick budget used when the snapshot carries no valid target TPS. */
    private static final double DEFAULT_BUDGET_MS = 50.0;

    private TickInProgress() {}

    /** Whether a tick has been running for longer than one tick budget. */
    static boolean notable(final WindowMetrics window, final double targetTps) {
        final double running = window.longestInProgressTickMspt();
        return window.ticksInProgress() > 0 && Double.isFinite(running) && running >= budgetMillis(targetTps);
    }

    /** One line of text for the tick-in-progress row, or null when nothing notable is running. */
    static String row(final WindowMetrics window, final double targetTps) {
        if (!notable(window, targetTps)) {
            return null;
        }
        final int count = window.ticksInProgress();
        return count + (count == 1 ? " tick" : " ticks") + " running, longest " + duration(window.longestInProgressTickMspt())
            + " so far (not finished; counted in the maximum above, see /perf scheduler)";
    }

    /** A suffix for the maximum line, or an empty string. */
    static String maximumNote(final WindowMetrics window, final double targetTps) {
        return notable(window, targetTps)
            ? " (includes a tick still running for " + duration(window.longestInProgressTickMspt()) + ")" : "";
    }

    static String duration(final double millis) {
        if (!Double.isFinite(millis)) {
            return "unavailable";
        }
        if (millis < 1_000.0) {
            return String.format(Locale.ROOT, "%.0fms", millis);
        }
        final double seconds = millis / 1_000.0;
        if (seconds < 120.0) {
            return String.format(Locale.ROOT, "%.1f s", seconds);
        }
        final long whole = (long)seconds;
        if (whole < 7_200L) {
            return (whole / 60L) + " min " + (whole % 60L) + " s";
        }
        return (whole / 3_600L) + " h " + ((whole % 3_600L) / 60L) + " min";
    }

    private static double budgetMillis(final double targetTps) {
        return Double.isFinite(targetTps) && targetTps > 0.0 ? 1_000.0 / targetTps : DEFAULT_BUDGET_MS;
    }
}
