package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the call sites of the region tick metrics patches, which only run in a bootstrapped server.
 * 0013 wires the region schedule handle to {@link RegionTickMetricsHolder} (five {@link RegionTickDataView}
 * windows, one start and one completion call per tick); 0079 carries the region/world lifecycle calls
 * (instance calls on the handle's holder, plus the world id and retire statics).
 * It proves the patch text still has the shape the unit tests of the helpers assume, not behaviour.
 */
class TickMetricsPatchShapeTest {

    private static String patch(final String prefix) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            for (final String base : List.of("minecraft-patches/features", "sourbycraft-server/minecraft-patches/features")) {
                final Path features = dir.resolve(base);
                if (Files.isDirectory(features)) {
                    try (Stream<Path> files = Files.list(features)) {
                        final Path found = files.filter(p -> p.getFileName().toString().startsWith(prefix))
                            .findFirst().orElse(null);
                        if (found != null) {
                            return Files.readString(found, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
        return fail("feature patch " + prefix + " not found from " + Path.of("").toAbsolutePath());
    }

    private static long count(final String text, final String needle) {
        return text.lines().filter(line -> line.startsWith("+") && line.contains(needle)).count();
    }

    @Test
    void handleRecordsEachTickOnceThroughTheHolder() throws IOException {
        final String core = patch("0013-SourbyCraft-custom-tick-metrics");
        assertEquals(5, count(core, "= this.sourbyTickMetrics.view("), "five TickData windows are holder views");
        assertEquals(1, count(core, "this.sourbyTickMetrics.tickStarted(tickStart);"));
        assertEquals(1, count(core, "this.sourbyTickMetrics.tickCompleted(time, getTimeBetweenTicks());"));
        assertEquals(5, core.lines().filter(line -> line.startsWith("-") && line.contains(".addDataFrom(time);")).count(),
            "the five per-window inserts are replaced, not kept beside the shared record");
        assertTrue(core.contains("+public class TickData {"), "TickData is opened for RegionTickDataView");
        assertFalse(core.contains("+import"), "the core hook adds no imports");
    }

    @Test
    void lifecycleCallsStayInTheirOwnHookPatch() throws IOException {
        final String lifecycle = patch("0079-SourbyCraft-region-tick-metrics-lifecycle");
        for (final String call : List.of("sourbyTickMetrics.retireDestroyed(System.nanoTime())",
                "sourbyTickMetrics.activate(data.world.sourbyTelemetryWorldId, data.id, System.nanoTime())",
                "sourbyTickMetrics.retireInactive(System.nanoTime())", "from.getData().tickHandle.sourbyTickMetrics.retireMerged(",
                "from.getData().tickHandle.sourbyTickMetrics.retireSplit(",
                "sourbyTickMetrics.rotateForMerge(data.world.sourbyTelemetryWorldId, data.id, System.nanoTime())",
                "RegionTickMetricsHolder.newWorldId()", "RegionTickMetricsHolder.retireWorld(")) {
            assertEquals(1, count(lifecycle, call), call);
        }
    }
}
