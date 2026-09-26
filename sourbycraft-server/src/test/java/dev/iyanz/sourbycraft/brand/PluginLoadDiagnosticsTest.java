package dev.iyanz.sourbycraft.brand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The log lines Paper emits for load and enable failures are what /plugins colours by. */
class PluginLoadDiagnosticsTest {

    @AfterEach
    void reset() {
        PluginLoadDiagnostics.resetForTest();
    }

    @Test
    void anEnableFailureIsAttributedByDisplayName() {
        PluginLoadDiagnostics.capture(Level.SEVERE,
            "Error occurred while enabling Essentials v2.21.0 (Is it up to date?)", new RuntimeException("x"));
        assertTrue(PluginLoadDiagnostics.enableFailed("Essentials"));
        assertFalse(PluginLoadDiagnostics.enableFailed("Essential"));
        assertFalse(PluginLoadDiagnostics.enableFailed("EssentialsChat"));
        assertEquals(0, PluginLoadDiagnostics.failedCount(), "an enable failure is not a load failure");
    }

    @Test
    void aLoadFailureIsStillCapturedWithItsReason() {
        PluginLoadDiagnostics.capture(Level.SEVERE,
            "Could not load plugin 'Legacy.jar' in folder 'plugins'",
            new RuntimeException("Could not load plugin 'Legacy' as it is not marked as supporting Folia!"));
        assertEquals(1, PluginLoadDiagnostics.failedCount());
        assertEquals("Legacy.jar", PluginLoadDiagnostics.recent().get(0).pluginJar());
    }

    @Test
    void informationalLinesAreIgnored() {
        PluginLoadDiagnostics.capture(Level.INFO,
            "Error occurred while enabling Quiet v1 (Is it up to date?)", null);
        assertFalse(PluginLoadDiagnostics.enableFailed("Quiet"));
    }
}
