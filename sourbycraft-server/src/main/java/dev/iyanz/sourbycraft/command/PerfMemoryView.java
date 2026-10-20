package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;
import static dev.iyanz.sourbycraft.command.PerfRows.bytes;
import static dev.iyanz.sourbycraft.command.PerfRows.percent;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf memory} and {@code /ram}: heap, non-heap, RSS, then the GC rows. */
final class PerfMemoryView implements PerfView {

    @Override public String name() { return "memory"; }

    @Override public String title() { return "Memory"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        final var runtime = snapshot.runtime();
        lines.add(UiPanel.section("Memory"));
        add(lines, "Heap used / committed / maximum", bytes(runtime.heapUsedBytes()) + " / "
            + bytes(runtime.heapCommittedBytes()) + " / " + bytes(runtime.heapMaxBytes()));
        add(lines, "Heap usage", heapUsage(runtime));
        add(lines, "Non-heap", bytes(runtime.nonHeapUsedBytes()));
        add(lines, "Process RSS", bytes(runtime.processRssBytes()));
        add(lines, "Container memory usage", percent(runtime.rssPercent()));
        add(lines, "Allocation rate", "n/a (allocation rate is not collected)");
        PerfGcView.rows(runtime, lines);
    }

    /** Heap used as a percentage of the maximum, or the unavailable sentinel. */
    private static String heapUsage(final RuntimeMetrics runtime) {
        final long used = runtime.heapUsedBytes();
        final long max = runtime.heapMaxBytes();
        if (max <= 0L || used < 0L) return "unavailable";
        return TpsCommand.value(100.0 * used / max, 1) + "% of maximum";
    }
}
