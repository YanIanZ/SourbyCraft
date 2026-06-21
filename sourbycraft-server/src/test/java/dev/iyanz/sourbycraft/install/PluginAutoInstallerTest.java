package dev.iyanz.sourbycraft.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PluginAutoInstallerTest {

    @Test
    void skipsAlreadyInstalledPlugins(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("ViaVersion-5.2.1.jar"));
        var entry = new PluginEntry("ViaVersion", "ci", null, "http://nonexistent/x.jar", null, null);
        var result = PluginAutoInstaller.installAll(List.of(entry), dir);
        assertEquals(1, result.skippedCount());
        assertEquals(0, result.installedCount());
        assertEquals(0, result.failedCount());
    }

    @Test
    void countsFailedDownloads(@TempDir Path dir) {
        var entry = new PluginEntry("Nonexistent", "ci", null,
            "http://127.0.0.1:1/nope.jar", null, null);
        var result = PluginAutoInstaller.installAll(List.of(entry), dir);
        assertEquals(0, result.installedCount());
        assertEquals(1, result.failedCount());
    }

    @Test
    void emptyListProducesEmptyResult(@TempDir Path dir) {
        var result = PluginAutoInstaller.installAll(List.of(), dir);
        assertEquals(0, result.installedCount() + result.skippedCount() + result.failedCount());
    }
}
