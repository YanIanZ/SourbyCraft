package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;
import static dev.iyanz.sourbycraft.command.PerfRows.count;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * {@code /perf async}: what the async-path pool has been doing, when it is on.
 *
 * <p>Saturation is visible as declined offloads: the navigation asks the pool for room before it
 * builds a snapshot, and a full queue or a stopping pool declines there. Refused counts the rarer
 * submission the pool still turned away after that check (another region filled the queue in
 * between, or a shutdown raced it). Neither drops the recompute: the region thread runs the
 * vanilla synchronous recompute for that slot, so a rising count means the pool is at capacity
 * for this workload and the region thread is doing that A* itself. Applied and discarded say
 * what became of finished solves on the owner; retired counts finished solves that never reached
 * it (the mob was removed or unloaded first, or the handoff was refused). The inline counter is
 * retained only for compatibility with older telemetry and should stay zero.</p>
 */
final class PerfAsyncView implements PerfView {

    private static final int TOP_TYPES = 8;

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
        // Both fall back to the synchronous recompute on the region thread; see the class javadoc.
        add(lines, "Declined, pool full / stopping (ran sync)", count(stats.declined()));
        add(lines, "Refused at submit (ran sync)", count(stats.refused()));
        // Finished solves as the owner found them, then those that never reached it.
        add(lines, "Results applied", count(stats.applied()));
        add(lines, "Results discarded (entity / path / generation / no path)",
            count(stats.staleSubject()) + " / " + count(stats.stalePath()) + " / "
                + count(stats.staleGeneration()) + " / " + count(stats.noPath()));
        add(lines, "Results retired before delivery (mob removed / unloaded, or handoff refused)",
            count(stats.retired()));
        final var types = dev.iyanz.sourbycraft.perf.AsyncPathTypeStats.top(TOP_TYPES);
        if (types.isEmpty()) {
            add(lines, "Top mob types", "none yet");
        } else {
            // Busiest first by admitted + refused + declined; "other" folds every type past the
            // tracking cap.
            for (final var type : types) {
                add(lines, "  " + type.type(), "admitted " + count(type.admitted())
                    + " / solved " + count(type.solved()) + " / refused " + count(type.refused())
                    + " / declined " + count(type.declined()));
            }
        }
    }
}
