package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PluginLoggingRouteTest {
    @Test void v23ModernLoaderLog4jFailuresReachTheRoster() {
        PluginLoadDiagnostics.install();
        var logger = org.apache.logging.log4j.LogManager.getLogger("UiModernLoaderProbe");
        logger.error("Could not load plugin 'UiModernProbe-1.0.jar' in folder 'plugins'",
            new IllegalStateException("missing fixture class"));
        logger.error("Could not load plugin 'UiModernProbe-1.0.jar' in folder 'plugins'",
            new IllegalStateException("updated fixture reason"));
        var failures = PluginLoadDiagnostics.recent().stream()
            .filter(entry -> entry.pluginJar().equals("UiModernProbe-1.0.jar")).toList();
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().reason().contains("updated fixture reason"));
        logger.error("Error occurred while enabling UiEnableProbe v1.0 (Is it up to date?)");
        assertTrue(PluginLoadDiagnostics.enableFailed("UiEnableProbe"));
    }
}
