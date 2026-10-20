package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.perf.IncidentLog;
import dev.iyanz.sourbycraft.perf.IncidentLog.Incident;
import dev.iyanz.sourbycraft.perf.IncidentLog.Kind;
import java.time.ZoneOffset;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class PerfIncidentsViewTest {

    // 2023-11-14T22:13:20Z
    private static final long T0 = 1_700_000_000_000L;

    private static List<String> text(final IncidentLog.View view) {
        return PerfIncidentsView.rows(view, ZoneOffset.UTC).stream()
            .map(row -> row[0] == null ? "# " + row[1] : row[0] + ": " + row[1]).toList();
    }

    @Test
    void anEmptyHistorySaysSoAndStillNamesWhatIsNotTracked() {
        final List<String> lines = text(new IncidentLog.View(List.of(), 0, 0, 0));
        assertEquals("# Incidents (newest first)", lines.get(0));
        assertEquals("Incidents: none recorded since start", lines.get(1));
        assertEquals("Kept / closed since start / overwritten: 0 / 0 / 0", lines.get(2));
        assertTrue(lines.get(3).startsWith("Not tracked: watchdog warnings"), lines.get(3));
    }

    @Test
    void incidentsRenderNewestFirstWithStartDurationPeakAndThreshold() {
        final IncidentLog.View view = new IncidentLog.View(List.of(
            new Incident(T0 + 60_000, T0 + 60_000, Kind.GC_TIME, "all collectors", 340, 200, 1, true),
            new Incident(T0, T0 + 7_000, Kind.REGION_OVER_BUDGET, "world #1 region #7", 83.25, 50, 8, false)),
            1, 0, 0);

        final List<String> lines = text(view);
        assertEquals("[ongoing] GC time per second — all collectors: 22:14:20 seen once, peak 340 ms"
            + " (threshold 200 ms)", lines.get(1));
        assertEquals("region over tick budget — world #1 region #7: 22:13:20 for 7s (8 observations),"
            + " peak 83.3 ms (threshold 50 ms)", lines.get(2));
        assertEquals("Kept / closed since start / overwritten: 2 / 1 / 0", lines.get(3));
    }

    @Test
    void untrackedBreachesAreMentionedOnlyWhenThereAreAny() {
        final List<String> lines = text(new IncidentLog.View(List.of(), 30, 10, 4));
        assertEquals("Kept / closed since start / overwritten: 0 / 30 / 10 (4 breaches untracked: too many open)",
            lines.get(2));
    }

    @Test
    void theLiveLogRendersThroughPerfCommand() {
        final String text = CommandTestSupport.plain(PerfCommand.render(
            () -> CommandTestSupport.healthy(20.0, 8.0), "incidents"));
        assertTrue(text.contains("Performance / incidents"), text);
        assertTrue(text.contains("Incidents (newest first)"), text);
        assertTrue(text.contains("Kept / closed since start / overwritten"), text);
    }

    @Test
    void incidentsIsAcceptedAndOffered() {
        assertTrue(PerfCommand.VIEWS.contains("incidents"));
        final PerfCommand command = CommandTestSupport.quietDenial(new PerfCommand("perf"));
        final CommandSender sender = new ReplyProbe().sender(CommandSender.class, true);
        assertEquals(List.of("incidents"), command.tabComplete(sender, "perf", new String[]{"inc"}));
        assertTrue(command.getUsage().contains("|incidents|"), command.getUsage());
    }
}
