package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.command.PerfSchedulerView.Line;
import dev.iyanz.sourbycraft.perf.ChunkLifecycleCounters;
import dev.iyanz.sourbycraft.perf.ConnectionQueueHealth;
import dev.iyanz.sourbycraft.perf.EntityTickCounters;
import dev.iyanz.sourbycraft.perf.RegionFileLoadLatency;
import dev.iyanz.sourbycraft.perf.RegionFileSaveLatency;
import dev.iyanz.sourbycraft.perf.RegionTaskWaitLatency;
import dev.iyanz.sourbycraft.perf.RegionTaskWaitLatency.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/**
 * {@code /perf} rows for metrics that are recorded elsewhere: region task queue wait
 * ({@link RegionTaskWaitLatency}, patch 0049) under {@code /perf scheduler}, region-file load latency
 * ({@link RegionFileLoadLatency}, patch 0050) under {@code /perf storage}, and Netty outbound queue
 * health ({@link ConnectionQueueHealth}) under {@code /perf network}, entity ticks per world
 * ({@link EntityTickCounters}, patch 0052) under {@code /perf entities} and chunk lifecycle
 * transitions ({@link ChunkLifecycleCounters}, patch 0051) under {@code /perf chunks}.
 *
 * <p>Each row builder is a pure function of a snapshot so it is tested without a server; the
 * {@code render*} methods read the live recorders. Percentiles of the task wait are bucket upper
 * bounds and are printed as "&lt;=", never as exact values.</p>
 */
public final class PerfTelemetryRows {

    private PerfTelemetryRows() {}

    public static void renderTaskWait(final List<Component> out) {
        out.addAll(components(taskWaitLines(RegionTaskWaitLatency.GLOBAL.snapshot(Kind.TICK),
            RegionTaskWaitLatency.GLOBAL.snapshot(Kind.CHUNK))));
    }

    public static void renderLoads(final List<Component> out) {
        out.addAll(components(loadLines(RegionFileLoadLatency.GLOBAL.snapshot())));
    }

    public static void renderConnectionQueues(final List<Component> out) {
        out.addAll(components(queueLines(ConnectionQueueHealth.sampleServer())));
    }

    public static void renderEntityTicks(final List<Component> out) {
        out.addAll(components(entityTickLines(EntityTickCounters.GLOBAL.snapshot())));
    }

    public static void renderChunkLifecycle(final List<Component> out) {
        out.addAll(components(chunkLifecycleLines(ChunkLifecycleCounters.GLOBAL.snapshot())));
    }

    public static List<Component> components(final List<Line> lines) {
        final List<Component> out = new ArrayList<>(lines.size());
        for (final Line line : lines) {
            out.add(line.isSection() ? UiPanel.section(line.section()) : UiPanel.row(line.label(), line.value()));
        }
        return out;
    }

    public static List<Line> taskWaitLines(final RegionTaskWaitLatency.Snapshot tick,
                                           final RegionTaskWaitLatency.Snapshot chunk) {
        final List<Line> lines = new ArrayList<>();
        lines.add(Line.section("Region task queue wait since boot"));
        waitRows(lines, "Tick", tick);
        waitRows(lines, "Chunk", chunk);
        lines.add(Line.row("Wait scope", "first push to run, region queues only; p50/p99 are power-of-two buckets"
            + " (upper bound); global and entity-scheduler tasks are not timed"));
        return List.copyOf(lines);
    }

    private static void waitRows(final List<Line> lines, final String name, final RegionTaskWaitLatency.Snapshot s) {
        if (s.executed() == 0L) {
            lines.add(Line.row(name + " tasks run", "0 (no wait recorded yet)"));
            return;
        }
        lines.add(Line.row(name + " tasks run", Long.toString(s.executed())));
        lines.add(Line.row(name + " task wait mean / p50 / p99 / max", String.format(Locale.ROOT,
            "%.3f / <= %.3f / <= %.3f / %.3f ms", s.meanMillis(), s.p50AtMostMillis(), s.p99AtMostMillis(), s.maxMillis())));
    }

    public static List<Line> loadLines(final List<RegionFileSaveLatency.Snapshot> loads) {
        final List<Line> lines = new ArrayList<>();
        if (loads.isEmpty()) {
            lines.add(Line.row("Region-file loads", "none timed yet (reads that found no chunk and AWF worlds are not timed)"));
            return List.copyOf(lines);
        }
        for (final RegionFileSaveLatency.Snapshot load : loads) {
            final String name = load.world() + " " + load.type();
            // In a RegionFileLoadLatency snapshot, saves() counts loads and failures() loads that threw.
            lines.add(Line.row(name + " region-file loads / failed", load.saves() + " / " + load.failures() + " since start"));
            lines.add(Line.row(name + " load p50 / p99 / max", load.windowSamples() == 0
                ? "no load in the last " + RegionFileSaveLatency.WINDOW_SECONDS + "s"
                : String.format(Locale.ROOT, "%.2f / %.2f / %.2f ms (%d loads, last %ds; max ever %.2f ms)",
                    load.p50Millis(), load.p99Millis(), load.maxMillis(), load.windowSamples(),
                    RegionFileSaveLatency.WINDOW_SECONDS, load.maxEverMillis())));
        }
        return List.copyOf(lines);
    }

    public static List<Line> queueLines(final ConnectionQueueHealth.Snapshot s) {
        final int open = s.connections() - s.closed();
        return List.of(
            Line.section("Outbound connection queues (point sample)"),
            Line.row("Connections sampled / closed", s.connections() + " / " + s.closed()),
            Line.row("Over write high-water mark", s.unwritable() + " of " + open + " open"),
            Line.row("Outbound bytes pending in Netty total / largest", bytes(s.pendingBytes()) + " / " + bytes(s.maxPendingBytes())),
            Line.row("Queue scope", "Netty buffer only, incl. per-message overhead; packets the game has not yet handed to Netty are not visible"));
    }

    public static List<Line> entityTickLines(final List<EntityTickCounters.Snapshot> worlds) {
        final List<Line> lines = new ArrayList<>();
        lines.add(Line.section("Entity ticks since boot"));
        if (worlds.isEmpty()) {
            lines.add(Line.row("Entity ticks", "no region entity loop has run yet"));
            return List.copyOf(lines);
        }
        for (final EntityTickCounters.Snapshot w : worlds) {
            lines.add(Line.row(w.world() + " entity ticks / region loops", w.entityTicks() + " / " + w.loops()));
            lines.add(Line.row(w.world() + " loop mean / max", String.format(Locale.ROOT,
                "%.3f / %.3f ms; %.1f entities per loop, max %d in one region",
                w.meanLoopMillis(), w.maxLoopMillis(), w.meanEntitiesPerLoop(), w.maxEntitiesInOneLoop())));
        }
        lines.add(Line.row("Entity tick scope", "non-passenger entities handed to tick (activation-range inactive ticks"
            + " included; frozen entities and passengers not counted); loop time includes despawn checks and"
            + " passengers; recording overhead not benchmarked"));
        return List.copyOf(lines);
    }

    public static List<Line> chunkLifecycleLines(final ChunkLifecycleCounters.Snapshot s) {
        return List.of(
            Line.section("Chunk lifecycle since boot (all worlds)"),
            Line.row("Chunk holders created / deleted / now", s.holdersCreated() + " / " + s.holdersDeleted() + " / " + s.holders()),
            Line.row("FULL loads / unloads / now", s.fullLoads() + " / " + s.fullUnloads() + " / " + s.fullChunks()),
            Line.row("Ticking starts / stops / now", s.tickingStarts() + " / " + s.tickingStops() + " / " + s.tickingChunks()),
            Line.row("Entity-ticking starts / stops / now", s.entityTickingStarts() + " / " + s.entityTickingStops()
                + " / " + s.entityTickingChunks()),
            Line.row("Lifecycle scope", "counted at the chunk system's callbacks; 'now' is in minus out, read counter"
                + " by counter while chunks move, not an atomic census"));
    }

    static String bytes(final long value) {
        if (value < 1024L) return value + " B";
        if (value < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024.0));
    }
}
