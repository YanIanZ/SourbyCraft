package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;

import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf health}: the tick health grade against the target TPS budget. */
final class PerfHealthView implements PerfView {

    @Override public String name() { return "health"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        lines.add(UiPanel.section("Health"));
        add(lines, "Tick health", health(snapshot));
        add(lines, "Diagnostics", "settings are operator-owned; /spark profiler start for samples");
    }

    static String health(final PerformanceSnapshot snapshot) {
        if (snapshot.freshness().state() != MetricState.AVAILABLE) return snapshot.freshness().state().name();
        final var tick = snapshot.window(MetricWindow.FIVE_SECONDS);
        final double target = snapshot.targetTps();
        final double mspt = tick.worstAverageMspt();
        final double tps = tick.worstTps();
        if (!Double.isFinite(target) || target <= 0 || !Double.isFinite(tps) || !Double.isFinite(mspt)) {
            return "UNAVAILABLE";
        }
        final double budget = 1000.0 / target;
        if (tps < target * 0.85 || mspt >= budget) return "CRITICAL";
        if (tps < target * 0.95 || mspt >= budget * 0.8) return "WARNING";
        if (mspt >= budget * 0.5) return "GOOD";
        return "EXCELLENT";
    }
}
