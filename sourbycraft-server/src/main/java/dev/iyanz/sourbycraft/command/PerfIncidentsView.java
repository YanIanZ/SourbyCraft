package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.perf.IncidentLog;
import dev.iyanz.sourbycraft.perf.IncidentLog.Incident;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/** {@code /perf incidents}: the bounded incident history, newest first. Pure rendering of a view. */
public final class PerfIncidentsView {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private PerfIncidentsView() {}

    static void render(final List<Component> out) {
        for (final String[] row : rows(IncidentLog.GLOBAL.view(), ZoneId.systemDefault())) {
            out.add(row[0] == null ? UiPanel.section(row[1]) : UiPanel.row(row[0], row[1]));
        }
    }

    /** Rows as {label, value}; a null label marks a section heading in the value slot. */
    static List<String[]> rows(final IncidentLog.View view, final ZoneId zone) {
        final List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{null, "Incidents (newest first)"});
        if (view.incidents().isEmpty()) {
            rows.add(new String[]{"Incidents", "none recorded since start"});
        }
        for (final Incident incident : view.incidents()) {
            rows.add(new String[]{
                (incident.ongoing() ? "[ongoing] " : "") + incident.kind().display() + " — " + incident.subject(),
                describe(incident, zone)});
        }
        rows.add(new String[]{"Kept / closed since start / overwritten", view.incidents().size() + " / "
            + view.closedTotal() + " / " + view.overwritten()
            + (view.untrackedBreaches() > 0 ? " (" + view.untrackedBreaches() + " breaches untracked: too many open)" : "")});
        rows.add(new String[]{"Not tracked", "watchdog warnings, network queue depth, stop-the-world GC pause"
            + " times, per-tick overrun streaks (no published source)"});
        return rows;
    }

    static String describe(final Incident incident, final ZoneId zone) {
        final String start = TIME.format(Instant.ofEpochMilli(incident.startEpochMillis()).atZone(zone));
        final String span = incident.observations() <= 1 ? "seen once"
            : "for " + seconds(incident.durationMillis()) + " (" + incident.observations() + " observations)";
        final String unit = incident.kind().unit();
        return start + " " + span + ", peak " + number(incident.peak()) + " " + unit
            + " (threshold " + number(incident.threshold()) + " " + unit + ")";
    }

    private static String seconds(final long millis) {
        return String.format(Locale.ROOT, "%.0fs", millis / 1000.0);
    }

    private static String number(final double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
            ? Long.toString((long)value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
