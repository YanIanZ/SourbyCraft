package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf governor}: the thread ledger, then each used Resource Governor lane. */
final class PerfGovernorView implements PerfView {

    @Override public String name() { return "governor"; }

    /** Budgets and counters for each Resource Governor lane that has been used. */
    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        renderThreadLedger(lines);
        final var stats = dev.iyanz.sourbycraft.execution.ResourceGovernor.GLOBAL.stats();
        if (stats.isEmpty()) {
            add(lines, "Resource Governor", "no governed lane has been used yet");
            return;
        }
        for (final var lane : stats) {
            add(lines, lane.name() + " budget", lane.threads() + " threads, queue " + lane.queueCapacity());
            add(lines, lane.name() + " active / queued / peak queued",
                lane.active() + " / " + lane.queued() + " / " + lane.peakQueued());
            add(lines, lane.name() + " submitted / completed / failed / rejected", lane.submitted() + " / "
                + lane.completed() + " / " + lane.failed() + " / " + lane.rejected());
        }
    }

    /** Configured platform threads per pool against the processors; ceilings, not load. */
    private static void renderThreadLedger(final List<Component> lines) {
        final var pools = dev.iyanz.sourbycraft.execution.ThreadLedger.collect();
        final int processors = Runtime.getRuntime().availableProcessors();
        lines.add(UiPanel.section("Thread ledger"));
        add(lines, "Summary", dev.iyanz.sourbycraft.execution.ThreadLedger.format(pools, processors).get(0)
            .replaceFirst("^Thread ledger: ", ""));
        for (final var pool : pools) {
            add(lines, pool.name(), (pool.threads() < 0 ? "?" : pool.threads() == 0 ? "off" : pool.threads())
                + " (" + pool.source() + ")");
        }
        // RESTART_REQUIRED: a reload never changes the running budgets above; what it read is shown here.
        for (final String pending : dev.iyanz.sourbycraft.execution.ResourceGovernor.GLOBAL.pendingRestart()) {
            add(lines, "Pending restart", pending);
        }
        lines.add(UiPanel.section("Resource Governor lanes"));
    }
}
