package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;
import static dev.iyanz.sourbycraft.command.PerfRows.percent;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.execution.LanePortions;
import dev.iyanz.sourbycraft.perf.MetricsRuntime;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf lanes}: where the machine's time went, by execution lane. */
final class PerfLanesView implements PerfView {

    @Override public String name() { return "lanes"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        renderLanes(lines, MetricsRuntime.lanePortions());
    }

    /**
     * Where the machine's time went, by execution lane.
     *
     * <p>Two facts are reported rather than one number, because they answer different questions.
     * A lane holding most of what was used is the thing to fix; a machine that is actually spent
     * is the only state where moving threads between lanes is the right kind of move. A server
     * can be the first without being the second, and that is exactly when adding threads to the
     * busy lane looks obvious and measures worse.</p>
     */
    private static void renderLanes(final List<Component> lines, final LanePortions.Report report) {
        if (!report.available()) {
            add(lines, "Lane load", "unavailable — " + report.reason());
            return;
        }
        for (final LanePortions.Portion portion : report.portions()) {
            if (portion.cores() < 0.005) {
                continue;                 // Below the noise the sampler can distinguish.
            }
            add(lines, "  " + portion.lane().display(),
                TpsCommand.value(portion.cores(), 2) + " cores ("
                    + percent(portion.shareOfUsed() * 100.0) + " of used)");
        }
        add(lines, "Used / idle / cores", TpsCommand.value(report.usedCores(), 2) + " / "
            + TpsCommand.value(report.idleCores(), 2) + " / " + report.machineCores());
        add(lines, "Shape", (report.isConcentrated()
                ? "concentrated in " + report.dominant().display() : "spread across lanes")
            + (report.isSaturated() ? ", machine saturated" : ", machine has headroom"));
    }
}
