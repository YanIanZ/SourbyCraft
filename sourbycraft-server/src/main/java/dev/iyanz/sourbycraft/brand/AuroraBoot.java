package dev.iyanz.sourbycraft.brand;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Text for the engine's boot stages: one DEBUG line per finished stage, one WARN line per failed
 * stage, and the single INFO summary that closes the Engine section of the console.
 *
 * <p>Plain text only. Colour belongs to the console layout ({@code log4j2.xml} and the
 * {@code log4jPlugins} converters), which paints by level and only on a real terminal; the
 * same text therefore reads identically in {@code logs/latest.log} and on a hosted panel.
 * Nothing here claims Minecraft readiness: {@code Done (} stays the server-ready marker.
 */
public final class AuroraBoot {

    private AuroraBoot() {}

    public record Stage(String name, boolean successful, long millis) {}

    /** The verdict word of a finished bootstrap: none failed COMPLETE, some DEGRADED, all FAILED. */
    public static String verdict(final int total, final int failures) {
        return failures == 0 ? "COMPLETE" : failures >= Math.max(1, total) ? "FAILED" : "DEGRADED";
    }

    /** DEBUG detail per stage: {@code "  3/15  configuration         20 ms  ok"}. */
    public static String stageLine(final int index, final int total, final Stage stage) {
        return String.format(Locale.ROOT, "%3d/%-3d %-22s %5d ms  %s", index, total,
            ConsoleStyle.singleLine(stage.name()), Math.max(0L, stage.millis()), stage.successful() ? "ok" : "FAILED");
    }

    /** WARN line for a failed stage; the stack trace is logged separately by the bootstrap. */
    public static String failureLine(final int index, final int total, final Stage stage) {
        return "Stage " + index + "/" + total + " \"" + ConsoleStyle.singleLine(stage.name())
            + "\" FAILED after " + Math.max(0L, stage.millis()) + " ms - see the error above; the engine continues without it";
    }

    /**
     * The one line that closes the Engine section, read by {@code scripts/run_baseline.py}
     * ({@code BOOT_SUMMARY}): verdict, stages that finished without an exception, failures (named),
     * elapsed time and the slowest stage. A stage finishing without an exception is not proof
     * that the service it started is healthy, so the wording stays at "ok".
     */
    public static String summary(final List<Stage> stages, final int total, final long millis) {
        final int safeTotal = Math.max(0, total);
        final List<Stage> failed = stages.stream().filter(stage -> !stage.successful()).toList();
        final int ok = Math.max(0, Math.min(safeTotal, stages.size() - failed.size()));
        final StringBuilder line = new StringBuilder(128)
            .append("Engine ").append(verdict(safeTotal, failed.size()))
            .append(" | ").append(ok).append('/').append(safeTotal).append(" stages ok")
            .append(" | ").append(failed.size()).append(" failed");
        if (!failed.isEmpty()) {
            line.append(": ").append(failed.stream().map(stage -> ConsoleStyle.singleLine(stage.name()))
                .collect(Collectors.joining(", ")));
        }
        line.append(" | ").append(Math.max(0L, millis)).append(" ms");
        stages.stream().max((a, b) -> Long.compare(a.millis(), b.millis())).ifPresent(slowest ->
            line.append(" | slowest: ").append(ConsoleStyle.singleLine(slowest.name()))
                .append(' ').append(Math.max(0L, slowest.millis())).append(" ms"));
        return line.toString();
    }
}
