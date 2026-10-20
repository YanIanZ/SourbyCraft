package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.perf.ConnectionQueueHealth;
import dev.iyanz.sourbycraft.perf.RegionFileSaveLatency;
import dev.iyanz.sourbycraft.perf.RegionTaskWaitLatency;
import dev.iyanz.sourbycraft.perf.RegionTaskWaitLatency.Kind;
import java.util.List;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class PerfTelemetryRowsTest {

    private static String text(final List<PerfSchedulerView.Line> lines) {
        return lines.stream().map(PerfSchedulerView.Line::plain).collect(Collectors.joining("\n"));
    }

    private static RegionTaskWaitLatency.Snapshot wait(final Kind kind, final long executed, final double mean,
                                                       final double p50, final double p99, final double max) {
        return new RegionTaskWaitLatency.Snapshot(kind, executed, mean, p50, p99, max,
            new long[RegionTaskWaitLatency.BUCKETS]);
    }

    @Test
    void taskWaitRowsShowEachKindWithAtMostPercentiles() {
        final String out = text(PerfTelemetryRows.taskWaitLines(
            wait(Kind.TICK, 1234L, 0.25, 0.512, 8.192, 40.5),
            wait(Kind.CHUNK, 7L, 1.5, 2.048, 4.096, 3.25)));
        assertTrue(out.startsWith("Region task queue wait since boot"), out);
        assertTrue(out.contains("Tick tasks run: 1234"), out);
        assertTrue(out.contains("Tick task wait mean / p50 / p99 / max: 0.250 / <= 0.512 / <= 8.192 / 40.500 ms"), out);
        assertTrue(out.contains("Chunk tasks run: 7"), out);
        assertTrue(out.contains("Chunk task wait mean / p50 / p99 / max: 1.500 / <= 2.048 / <= 4.096 / 3.250 ms"), out);
        assertTrue(out.contains("power-of-two buckets"), out);
    }

    @Test
    void taskWaitRowsSayNothingRanInsteadOfZeroLatency() {
        final String out = text(PerfTelemetryRows.taskWaitLines(
            wait(Kind.TICK, 0L, 0, 0, 0, 0), wait(Kind.CHUNK, 0L, 0, 0, 0, 0)));
        assertTrue(out.contains("Tick tasks run: 0 (no wait recorded yet)"), out);
        assertTrue(out.contains("Chunk tasks run: 0 (no wait recorded yet)"), out);
        assertFalse(out.contains("mean / p50"), out);
    }

    @Test
    void loadRowsUseLoadWordingAndTheWindow() {
        final String out = text(PerfTelemetryRows.loadLines(List.of(
            new RegionFileSaveLatency.Snapshot("world", "minecraft:overworld", "chunk", 42L, 1L, 10, 0.5, 3.25, 4.0, 9.5),
            new RegionFileSaveLatency.Snapshot("world", "minecraft:overworld", "poi", 3L, 0L, 0, 0, 0, 0, 1.0))));
        assertTrue(out.contains("world chunk region-file loads / failed: 42 / 1 since start"), out);
        assertTrue(out.contains("world chunk load p50 / p99 / max: 0.50 / 3.25 / 4.00 ms (10 loads, last "
            + RegionFileSaveLatency.WINDOW_SECONDS + "s; max ever 9.50 ms)"), out);
        assertTrue(out.contains("world poi load p50 / p99 / max: no load in the last "
            + RegionFileSaveLatency.WINDOW_SECONDS + "s"), out);
        assertFalse(out.contains("save"), out);
    }

    @Test
    void loadRowsWhenNothingTimed() {
        final String out = text(PerfTelemetryRows.loadLines(List.of()));
        assertEquals("Region-file loads: none timed yet (reads that found no chunk and AWF worlds are not timed)", out);
    }

    @Test
    void connectionQueueRows() {
        final String out = text(PerfTelemetryRows.queueLines(
            new ConnectionQueueHealth.Snapshot(5, 1, 2, 3L * 1024 * 1024, 2L * 1024 * 1024)));
        assertTrue(out.contains("Connections sampled / closed: 5 / 1"), out);
        assertTrue(out.contains("Over write high-water mark: 2 of 4 open"), out);
        assertTrue(out.contains("Outbound bytes pending in Netty total / largest: 3.0 MiB / 2.0 MiB"), out);
        assertTrue(out.contains("point sample"), out);
    }

    @Test
    void connectionQueueRowsWithNoConnection() {
        final String out = text(PerfTelemetryRows.queueLines(ConnectionQueueHealth.Snapshot.EMPTY));
        assertTrue(out.contains("Connections sampled / closed: 0 / 0"), out);
        assertTrue(out.contains("Over write high-water mark: 0 of 0 open"), out);
    }

    @Test
    void componentsKeepThePlainText() {
        final var lines = PerfTelemetryRows.queueLines(ConnectionQueueHealth.Snapshot.EMPTY);
        final var components = PerfTelemetryRows.components(lines);
        assertEquals(lines.size(), components.size());
        for (int i = 0; i < lines.size(); i++) {
            final String plain = PlainTextComponentSerializer.plainText().serialize(components.get(i));
            assertTrue(plain.contains(lines.get(i).isSection() ? lines.get(i).section() : lines.get(i).value()), plain);
        }
    }
}
