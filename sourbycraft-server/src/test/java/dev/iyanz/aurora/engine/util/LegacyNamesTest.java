package dev.iyanz.aurora.engine.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canvas-era JVM flags renamed to {@code Aurora.*} (owner decision 2026-10-21): the new name wins, the old name is
 * still read until Build 50 with a single deprecation line.
 */
@ResourceLock("dev.iyanz.aurora.engine.util.LegacyNames")
class LegacyNamesTest {

    private final Map<String, String> properties = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void install() {
        LegacyNames.installForTesting(this.properties::get, this.warnings::add);
    }

    @AfterEach
    void restore() {
        LegacyNames.resetForTesting();
    }

    @Test
    void newNameIsReadWithoutAWarning() {
        this.properties.put("Aurora.AggressiveChunkSystem", "");
        assertTrue(LegacyNames.flag("Aurora.AggressiveChunkSystem", "Canvas.AggressiveChunkSystem"));
        assertEquals(List.of(), this.warnings);
    }

    @Test
    void oldNameStillWorksAndWarnsOnceNamingTheReplacementAndTheRemovalBuild() {
        this.properties.put("Canvas.AggressiveChunkSystem", "true");

        assertTrue(LegacyNames.flag("Aurora.AggressiveChunkSystem", "Canvas.AggressiveChunkSystem"));
        assertTrue(LegacyNames.flag("Aurora.AggressiveChunkSystem", "Canvas.AggressiveChunkSystem"));

        assertEquals(1, this.warnings.size(), "one line per old name, not per read");
        final String line = this.warnings.getFirst();
        assertTrue(line.contains("-DCanvas.AggressiveChunkSystem"), line);
        assertTrue(line.contains("-DAurora.AggressiveChunkSystem"), line);
        assertTrue(line.contains("Build 50"), line);
    }

    @Test
    void newNameWinsOverTheOldOneAndTheOldOneIsNotReported() {
        this.properties.put("Aurora.DisableRegionProfiler", "false");
        this.properties.put("Canvas.DisableRegionProfiler", "true");

        assertFalse(LegacyNames.strictFlag("Aurora.DisableRegionProfiler", "Canvas.DisableRegionProfiler"));
        assertEquals(List.of(), this.warnings);
    }

    @Test
    void lenientAndStrictParsingMatchTheCallSitesTheyReplace() {
        this.properties.put("Canvas.isRunningInIDE", "");
        assertTrue(LegacyNames.flag("Aurora.isRunningInIDE", "Canvas.isRunningInIDE"), "-Dname alone is true, like Util.isFlagEnabled");

        this.properties.put("Canvas.DisableRegionProfiler", "");
        assertFalse(LegacyNames.strictFlag("Aurora.DisableRegionProfiler", "Canvas.DisableRegionProfiler"), "only =true counts, like Boolean.getBoolean");
        this.properties.put("Canvas.DisableRegionProfiler", "TRUE");
        assertTrue(LegacyNames.strictFlag("Aurora.DisableRegionProfiler", "Canvas.DisableRegionProfiler"));
    }

    @Test
    void absentEverywhereIsOffAndSilent() {
        assertFalse(LegacyNames.flag("Aurora.AggressiveChunkSystem", "Canvas.AggressiveChunkSystem"));
        assertFalse(LegacyNames.strictFlag("Aurora.DisableRegionProfiler", "Canvas.DisableRegionProfiler"));
        assertNull(LegacyNames.property("Aurora.x", "Canvas.x"));
        assertEquals(List.of(), this.warnings);
    }

    @Test
    void warnOnceIsKeyedSoDifferentSubjectsEachGetOneLine() {
        assertTrue(LegacyNames.warnOnce("permission:a", "a"));
        assertFalse(LegacyNames.warnOnce("permission:a", "a"));
        assertTrue(LegacyNames.warnOnce("permission:b", "b"));
        assertEquals(List.of("a", "b"), this.warnings);
    }
}
