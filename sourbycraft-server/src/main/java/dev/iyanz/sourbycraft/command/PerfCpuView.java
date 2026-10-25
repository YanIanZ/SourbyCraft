package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;
import static dev.iyanz.sourbycraft.command.PerfRows.count;
import static dev.iyanz.sourbycraft.command.PerfRows.percent;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf cpu}: process and system CPU, processors and threads, uptime. */
final class PerfCpuView implements PerfView {

    @Override public String name() { return "cpu"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        final var runtime = snapshot.runtime();
        lines.add(UiPanel.section("CPU"));
        add(lines, "Process CPU / system CPU", percent(runtime.processCpuPercent()) + " / "
            + percent(runtime.systemCpuPercent()));
        // ThreadMXBean.getThreadCount: Java threads only, not JVM-internal threads such as GC and
        // compiler threads, so below the OS thread count (/proc/self/status Threads).
        add(lines, "Available processors / live Java threads", count(runtime.availableProcessors())
            + " / " + count(runtime.liveThreadCount()));
        add(lines, "Uptime", runtime.uptimeMillis() < 0 ? "unavailable" : runtime.uptimeMillis() / 1000 + "s");
    }
}
