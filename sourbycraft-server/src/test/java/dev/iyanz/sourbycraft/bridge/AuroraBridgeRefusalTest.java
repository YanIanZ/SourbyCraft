package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics;
import org.junit.jupiter.api.Test;

/**
 * Case 2 of the LightingLuminol port (mode OFF, 2026-10-22 local run): a legacy plugin was refused with
 * the base's "Error loading plugin" ERROR and a 15-line stack trace, the explanation came three seconds
 * later in the future tense, and {@code /plugins} did not list it at all (the load-failure capture is
 * installed after Paper builds plugin providers). Paper feature patch 0024 calls {@link AuroraBridge#refused}.
 */
class AuroraBridgeRefusalTest {

    @Test
    void theRefusalLineNamesThePluginTheJarAndTheSettingThatAdmitsIt() {
        final String line = AuroraBridge.refusalMessage("Legacy v1.0", "legacy-1.0.jar");
        assertTrue(line.startsWith("Not loading Legacy v1.0 (legacy-1.0.jar)"), line);
        assertTrue(line.contains("aurora.bridge.mode = off"), line);
        assertTrue(line.contains("aurora.bridge.mode = \"safe\" and restart"), line);
        assertTrue(line.contains("not qualified"), line);
        assertEquals(-1, line.indexOf('\n'), "one line");
    }

    @Test
    void aRefusedPluginIsListedAsALoadFailureUnderItsName() {
        try {
            AuroraBridge.refused("Legacy", "Legacy v1.0", "legacy-1.0.jar");
            final PluginLoadDiagnostics.Entry entry = PluginLoadDiagnostics.recent().stream()
                .filter(e -> e.pluginJar().equals("legacy-1.0.jar")).findFirst().orElseThrow();
            assertEquals("Legacy", entry.pluginName());
            assertTrue(entry.reason().contains("aurora.bridge.mode = off"), entry.reason());
        } finally {
            PluginLoadDiagnostics.resetForTest();
        }
    }
}
