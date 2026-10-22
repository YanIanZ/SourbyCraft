package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;
import static dev.iyanz.sourbycraft.command.PerfRows.count;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf gc}: collection counts and cumulative time; also part of the memory view. */
final class PerfGcView implements PerfView {

    @Override public String name() { return "gc"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        rows(snapshot.runtime(), lines);
    }

    static void rows(final RuntimeMetrics runtime, final List<Component> lines) {
        lines.add(UiPanel.section("Garbage collection"));
        add(lines, "GC collections / cumulative collection time", count(runtime.gcCollectionCount()) + " / "
            + (runtime.gcCollectionTimeMillis() < 0 ? "unavailable" : runtime.gcCollectionTimeMillis() + "ms"));
        add(lines, "GC collections/min", TpsCommand.value(runtime.gcCollectionsPerMinute(), 1));
        add(lines, "GC young / old collections", "n/a (all pools are aggregated)");
        add(lines, "Last GC pause", "n/a (only the mean per cycle is collected)");
        add(lines, "Pause distribution / allocation samples", "use JFR or /spark profiler start");
        add(lines, "GC scope", "MXBean collection time is not stop-the-world pause time");
    }
}
