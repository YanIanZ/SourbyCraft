package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;
import static dev.iyanz.sourbycraft.command.PerfRows.count;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * {@code /perf async}: what the async-path pool has been doing, when it is on.
 *
 * <p>Saturation is visible as refused work. Periodic path recomputes are deliberately dropped
 * when the queue is full so the mob keeps its current path rather than forcing CPU-bound A*
 * back onto the region thread. The inline counter is retained only for compatibility with
 * older telemetry and should stay zero.</p>
 */
final class PerfAsyncView implements PerfView {

    @Override public String name() { return "async"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        if (!dev.iyanz.sourbycraft.perf.AsyncPathProcessor.isEnabled()) {
            add(lines, "Async pathfinding", "disabled (aurora.entity.async-pathfinding)");
            return;
        }
        final var stats = dev.iyanz.sourbycraft.perf.AsyncPathProcessor.stats();
        add(lines, "Solves admitted / outstanding",
            count(stats.admitted()) + " / " + count(stats.outstanding()));
        add(lines, "Solve time mean / slowest", Double.isNaN(stats.meanMillis()) ? "no solves yet"
            : TpsCommand.ms(stats.meanMillis()) + " / " + TpsCommand.ms(stats.slowestMillis()));
        // Queue wait separates "the A* is expensive" from "the pool is too small for the arrival
        // rate". A short solve behind a long wait still reaches the entity late.
        add(lines, "Queue wait mean / slowest", Double.isNaN(stats.meanWaitMillis())
            ? "no solves yet"
            : TpsCommand.ms(stats.meanWaitMillis()) + " / " + TpsCommand.ms(stats.slowestWaitMillis()));
        add(lines, "Queue depth / active workers / pool", stats.poolSize() < 0 ? "pool not running"
            : count(stats.queueDepth()) + " / " + count(stats.activeWorkers())
                + " / " + count(stats.poolSize()));
        add(lines, "Legacy caller-run solves", count(stats.inline())
            + (stats.inline() > 0 ? "  — unexpected on this build" : ""));
        add(lines, "Refused (saturation / shutdown)", count(stats.refused()));
    }
}
