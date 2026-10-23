package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.awf.LatencyRecorder;
import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/** Bounded, process-wide samples of non-empty generic world-generation stages. */
public final class ChunkGenerationMetrics {

    static final String PROPERTY = "sourbycraft.chunk-generation-metrics.enabled";
    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.toml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.toml");

    /**
     * RESTART_REQUIRED: on when the JVM property or {@code aurora.diagnostics.chunk-generation-metrics}
     * is true. Read once at class init, which the chunk system triggers before configuration loads,
     * so the TOML is read here directly, the way {@code AuroraBridge} reads its mode.
     */
    public static final boolean ENABLED = Boolean.getBoolean(PROPERTY) || readEarly(AURORA_FILE, UNIFIED_FILE);
    // Fixed keys: never retain worlds, coordinates, tasks, or plugin-defined labels.
    private static final List<String> STAGES = List.of("structure_starts", "structure_references",
        "biomes", "noise", "surface", "carvers", "features", "initialize_light", "spawn");

    public static final ChunkGenerationMetrics GLOBAL = new ChunkGenerationMetrics();

    public record Stage(String name, long completedRuns, LatencyRecorder.Percentiles queue,
                        LatencyRecorder.Percentiles execution) {}

    private static final class Samples {
        final LongAdder runs = new LongAdder();
        final LatencyRecorder queue = new LatencyRecorder();
        final LatencyRecorder execution = new LatencyRecorder();
    }

    private final Samples[] samples = new Samples[STAGES.size()];

    public ChunkGenerationMetrics() {
        for (int i = 0; i < this.samples.length; i++) this.samples[i] = new Samples();
    }

    /** Wall time, including future waits and completion callbacks; failed runs also count. */
    public void record(final String stage, final long queueNanos, final long executionNanos) {
        final String name = stage != null && stage.startsWith("minecraft:") ? stage.substring(10) : stage;
        final int index = name == null ? -1 : STAGES.indexOf(name);
        if (index < 0) return;
        final Samples sample = this.samples[index];
        sample.queue.record(queueNanos);
        sample.execution.record(executionNanos);
        sample.runs.increment();
    }

    /**
     * One run from the three stamps of a generation task: queue = {@code started - queuedAt},
     * execution = {@code finished - started}.
     */
    void recordRun(final String stage, final long queuedAt, final long started, final long finished) {
        this.record(stage, started - queuedAt, finished - started);
    }

    // Hooks for Minecraft feature patch 0026 (ChunkUpgradeGenericStatusTask). The patch keeps only
    // the call sites and two fields; with ENABLED off (the default) each hook is a constant branch.

    /** The schedule-time stamp; {@code 0} when timing is off. */
    public static long stampQueued() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** The run-start stamp; {@code 0} when timing is off. */
    public static long stampRunStart() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /**
     * Records a finished run (also a failed one) when timing is on and the task really executed a
     * generation step: {@code generated} is false for loads and for empty generation statuses.
     */
    public static void runFinished(final boolean generated, final String stage, final long queuedAt, final long started) {
        if (ENABLED && generated) {
            GLOBAL.recordRun(stage, queuedAt, started, System.nanoTime());
        }
    }

    /** Queue/run rings are individually consistent, not an atomic process-wide snapshot. */
    public List<Stage> snapshot() {
        final List<Stage> result = new ArrayList<>();
        for (int i = 0; i < this.samples.length; i++) {
            final Samples sample = this.samples[i];
            final long runs = sample.runs.sum();
            if (runs != 0) result.add(new Stage(STAGES.get(i), runs,
                sample.queue.percentiles(), sample.execution.percentiles()));
        }
        return List.copyOf(result);
    }

    /**
     * The chunk-generation switch without the config system: Aurora's file over the unified file,
     * the precedence {@code SourbyCraftConfig} applies. Missing, unreadable or invalid means off --
     * this is an opt-in measurement, so failure never turns it on. Never writes.
     */
    static boolean readEarly(final Path auroraFile, final Path unifiedFile) {
        final Map<String, Object> values = new HashMap<>();
        for (final Path file : List.of(unifiedFile, auroraFile)) {
            if (!Files.isRegularFile(file)) continue;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                final Object value = new com.electronwill.nightconfig.toml.TomlParser().parse(reader)
                    .get(AuroraConfig.CHUNK_GENERATION_METRICS_KEY);
                if (value != null) values.put(AuroraConfig.CHUNK_GENERATION_METRICS_KEY, value);
            } catch (final Exception | LinkageError unreadable) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("chunk-generation metrics could not read "
                    + dev.iyanz.sourbycraft.config.ConfigParseErrors.describe(file, unreadable)
                    + "; the measurement stays off this run");
                return false;
            }
        }
        return AuroraConfig.parse(new ConfigSnapshot(values)).config().diagnostics().chunkGenerationMetrics();
    }
}
