package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf chunks}: loaded chunks per world, generation stage timing, chunk lifecycle counters. */
final class PerfChunksView implements PerfView {

    private static final dev.iyanz.sourbycraft.execution.region.RegionPopulation POPULATION =
        new dev.iyanz.sourbycraft.execution.region.FoliaRegionBackend();

    @Override public String name() { return "chunks"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        renderPopulation(lines, true);
        renderChunkGeneration(lines);
        PerfTelemetryRows.renderChunkLifecycle(lines);
    }

    /** Per-world chunk or entity counts, each region's own count; shared with {@code /perf entities}. */
    static void renderPopulation(final List<Component> lines, final boolean chunks) {
        final var worlds = POPULATION.population();
        if (worlds.isEmpty()) {
            add(lines, chunks ? "Loaded chunks" : "Entities", "no world loaded");
            return;
        }
        long total = 0;
        for (final var world : worlds) {
            total += chunks ? world.chunks() : world.entities();
            add(lines, world.world() + (chunks ? " chunks / regions" : " entities / players"),
                chunks ? world.chunks() + " / " + world.regions() : world.entities() + " / " + world.players());
        }
        add(lines, chunks ? "Total loaded chunks" : "Total entities", total + " (each region's own count, up to one tick old)");
    }

    static void renderChunkGeneration(final List<Component> lines) {
        lines.add(UiPanel.section("World generation stages"));
        if (!dev.iyanz.sourbycraft.perf.ChunkGenerationMetrics.ENABLED) {
            add(lines, "Generation timing", "disabled; set aurora.diagnostics.chunk-generation-metrics = true in "
                + "sourbycraft_config/aurora.yml (or -Dsourbycraft.chunk-generation-metrics.enabled=true) and restart");
            return;
        }
        final var stages = dev.iyanz.sourbycraft.perf.ChunkGenerationMetrics.GLOBAL.snapshot();
        if (stages.isEmpty()) {
            add(lines, "Generation timing", "no completed non-empty generic generation stage measured");
            return;
        }
        for (final var stage : stages) {
            add(lines, stage.name() + " queue p95 / p99",
                TpsCommand.ms(stage.queue().p95()) + " / " + TpsCommand.ms(stage.queue().p99()));
            add(lines, stage.name() + " run p95 / p99",
                TpsCommand.ms(stage.execution().p95()) + " / " + TpsCommand.ms(stage.execution().p99())
                    + " (" + stage.execution().samples() + " recent samples; " + stage.completedRuns() + " runs)");
        }
        add(lines, "Timing scope", "process-wide wall time; run includes future waits/callbacks; failed runs included");
        add(lines, "Coverage", "completed generic generation stages only; light, full integration and cancelled queued tasks excluded");
    }
}
