package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;

import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf tick}: tick rate and MSPT over the five-second window. */
final class PerfTickView implements PerfView {

    @Override public String name() { return "tick"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        final var tick = snapshot.window(MetricWindow.FIVE_SECONDS);
        lines.add(UiPanel.section("Ticks"));
        add(lines, "Target TPS", TpsCommand.value(snapshot.targetTps(), 2));
        add(lines, "Worst region TPS", TpsCommand.value(TpsCommand.cappedTps(tick.worstTps(), snapshot.targetTps()), 2));
        add(lines, "Worst average MSPT", TpsCommand.ms(tick.worstAverageMspt()));
        add(lines, "Estimated p95 / p99", TpsCommand.ms(tick.estimatedP95Mspt()) + " / "
            + TpsCommand.ms(tick.estimatedP99Mspt()) + " (approximate)");
        add(lines, "Recent maximum", TpsCommand.ms(tick.maximumMspt()));
    }
}
