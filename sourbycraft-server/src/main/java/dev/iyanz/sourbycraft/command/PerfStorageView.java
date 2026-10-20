package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf storage}: chunk-system I/O counts, region-file save/load timing, pending I/O. */
final class PerfStorageView implements PerfView {

    @Override public String name() { return "storage"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        final var totals = dev.iyanz.sourbycraft.perf.RegionIoCounters.GLOBAL.totals();
        add(lines, "Chunk-system reads / writes / deletes", totals.reads() + " / " + totals.writes() + " / "
            + totals.deletes() + " since start");
        PerfTelemetryRows.renderSaves(lines);
        PerfTelemetryRows.renderLoads(lines);
        final var queues = dev.iyanz.sourbycraft.perf.RegionIoQueue.INSTANCE.sample();
        if (queues.isEmpty()) {
            add(lines, "Pending storage I/O", "no world loaded");
            return;
        }
        for (final var queue : queues) {
            add(lines, queue.world() + " pending I/O (chunk / poi / entity)",
                queue.chunk() + " / " + queue.poi() + " / " + queue.entity());
        }
    }
}
