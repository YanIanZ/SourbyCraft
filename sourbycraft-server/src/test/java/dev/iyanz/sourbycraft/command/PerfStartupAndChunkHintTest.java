package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.startup.PluginTimings;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class PerfStartupAndChunkHintTest {

    private static String plain(final List<Component> lines) {
        final StringBuilder out = new StringBuilder();
        for (final Component line : lines) out.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        return out.toString();
    }

    @Test
    void perfPluginsListsTheFiveSlowestStartups() {
        final PluginTimings timings = new PluginTimings(16);
        for (int i = 1; i <= 7; i++) timings.enable("P" + i, i * 1_000_000L);
        timings.load("P7", 500_000L);
        final List<Component> lines = new ArrayList<>();
        PerfCommand.renderSlowestStartups(lines, timings);
        final String text = plain(lines);
        assertEquals(5, lines.size(), text);
        assertTrue(text.contains("#1 P7") && text.contains("load 0.5 ms, enable 7.0 ms"), text);
        assertTrue(text.contains("#5 P3"), text);
        assertFalse(text.contains("P2"), text);
    }

    @Test
    void perfPluginsSaysWhenNothingWasRecorded() {
        final List<Component> lines = new ArrayList<>();
        PerfCommand.renderSlowestStartups(lines, new PluginTimings(4));
        assertTrue(plain(lines).contains("none recorded"));
        assertEquals("not recorded", new PluginTimings(4).of("X").describe());
    }

    @Test
    void theDisabledChunkViewNamesTheTomlSetting() {
        // No sourbycraft_config in the test working directory and no -D, so timing is off here.
        if (dev.iyanz.sourbycraft.perf.ChunkGenerationMetrics.ENABLED) return;
        final List<Component> lines = new ArrayList<>();
        PerfCommand.renderChunkGeneration(lines);
        final String text = plain(lines);
        assertTrue(text.contains("aurora.diagnostics.chunk-generation-metrics"), text);
        assertTrue(text.contains("restart"), text);
    }
}
