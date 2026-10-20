package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf entities}: entities and players per world, then entity tick counters. */
final class PerfEntitiesView implements PerfView {

    @Override public String name() { return "entities"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        PerfChunksView.renderPopulation(lines, false);
        PerfTelemetryRows.renderEntityTicks(lines);
    }
}
