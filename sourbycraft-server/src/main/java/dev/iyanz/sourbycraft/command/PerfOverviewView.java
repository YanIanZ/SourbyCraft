package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/** Bare {@code /perf}: the listed parts' bodies, in order. Not itself listed. */
final class PerfOverviewView implements PerfView {

    private final List<PerfView> parts;

    PerfOverviewView(final List<PerfView> parts) {
        this.parts = List.copyOf(parts);
    }

    @Override public String name() { return "overview"; }

    @Override public boolean listed() { return false; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        for (final PerfView part : this.parts) part.render(snapshot, lines);
    }
}
