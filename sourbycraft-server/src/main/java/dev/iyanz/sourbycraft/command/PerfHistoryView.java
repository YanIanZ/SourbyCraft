package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;
import static dev.iyanz.sourbycraft.command.PerfRows.bytes;
import static dev.iyanz.sourbycraft.command.PerfRows.percent;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf history}: the per-minute history, newest first, at most 15 lines. */
final class PerfHistoryView implements PerfView {

    @Override public String name() { return "history"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        final var samples = dev.iyanz.sourbycraft.perf.PerformanceHistory.GLOBAL.samples();
        if (samples.isEmpty()) {
            add(lines, "History", "no minute recorded yet");
            return;
        }
        add(lines, "Minutes kept", samples.size() + " of " + dev.iyanz.sourbycraft.perf.PerformanceHistory.CAPACITY
            + " (worst region TPS / MSPT over each minute, process CPU, heap)");
        final java.time.format.DateTimeFormatter clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .withZone(java.time.ZoneId.systemDefault());
        // Newest first, at most 15 lines: chat is not a chart.
        for (int i = samples.size() - 1, shown = 0; i >= 0 && shown < 15; i--, shown++) {
            final var s = samples.get(i);
            add(lines, clock.format(java.time.Instant.ofEpochMilli(s.epochMillis())),
                TpsCommand.value(s.worstTps(), 2) + " TPS / " + TpsCommand.ms(s.worstMspt()) + " / "
                    + percent(s.processCpuPercent()) + " CPU / " + bytes(s.heapUsedBytes()) + " of "
                    + bytes(s.heapMaxBytes()));
        }
    }
}
