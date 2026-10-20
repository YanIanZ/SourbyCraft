package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChunkGenerationMetricsTest {

    @Test
    void V37StageSamplesKeepQueueAndExecutionSeparate() {
        final var metrics = new ChunkGenerationMetrics();
        metrics.record("minecraft:noise", 2_000_000L, 40_000_000L);
        metrics.record("features", 30_000_000L, 1_000_000L);
        final var stages = metrics.snapshot();
        assertEquals(2, stages.size());
        assertEquals("noise", stages.get(0).name());
        assertEquals(2.0, stages.get(0).queue().p99());
        assertEquals(40.0, stages.get(0).execution().p99());
        assertEquals(30.0, stages.get(1).queue().p99());
        assertEquals(1.0, stages.get(1).execution().p99());
    }

    @Test
    void V37StageSamplesAreBoundedAndIgnoreUnknownLabels() {
        final var metrics = new ChunkGenerationMetrics();
        assertTrue(metrics.snapshot().isEmpty());
        metrics.record("plugin/world/location", 1, 1);
        metrics.record("plugin:noise", 1, 1);
        metrics.record(null, 1, 1);
        assertTrue(metrics.snapshot().isEmpty());
        for (int i = 0; i < 2048; i++) metrics.record("noise", i, i);
        final var stage = metrics.snapshot().getFirst();
        assertEquals(2048, stage.completedRuns());
        assertEquals(1024, stage.queue().samples());
        assertEquals(1024, stage.execution().samples());
        // Nearest-rank p50 of the last 1024 values (1024..2047).
        assertEquals(1535 / 1_000_000.0, stage.execution().p50());
    }

    // Hooks of Minecraft feature patch 0026 (ChunkUpgradeGenericStatusTask): queue = run start -
    // schedule stamp, execution = run end - run start, from the three stamps the task takes.
    @Test
    void aRunIsSplitIntoQueueAndExecutionFromItsThreeStamps() {
        final var metrics = new ChunkGenerationMetrics();
        metrics.recordRun("minecraft:noise", 1_000_000L, 4_000_000L, 9_000_000L);
        final var stage = metrics.snapshot().getFirst();
        assertEquals(1, stage.completedRuns());
        assertEquals(3.0, stage.queue().p99());
        assertEquals(5.0, stage.execution().p99());
    }

    @Test
    void withTimingOffTheHooksStampNothingAndRecordNothing() {
        // ENABLED is a restart-time constant; tests run with it off (no property, no TOML).
        assertEquals(false, ChunkGenerationMetrics.ENABLED);
        assertEquals(0L, ChunkGenerationMetrics.stampQueued());
        assertEquals(0L, ChunkGenerationMetrics.stampRunStart());
        final int before = ChunkGenerationMetrics.GLOBAL.snapshot().size();
        ChunkGenerationMetrics.runFinished(true, "noise", 0L, 0L);
        assertEquals(before, ChunkGenerationMetrics.GLOBAL.snapshot().size());
    }
}
