package dev.iyanz.sourbycraft.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PluginExistenceCheckTest {

    @Test
    void detectsExistingPluginByNamePrefix(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("ViaVersion-5.2.1.jar"));
        assertTrue(PluginExistenceCheck.isInstalled(dir, "ViaVersion"));
    }

    @Test
    void caseInsensitiveMatch(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("viaversion-5.2.1.jar"));
        assertTrue(PluginExistenceCheck.isInstalled(dir, "ViaVersion"));
    }

    @Test
    void absentPluginReturnsFalse(@TempDir Path dir) {
        assertFalse(PluginExistenceCheck.isInstalled(dir, "ViaVersion"));
    }

    @Test
    void onlyJarFilesCount(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("ViaVersion-readme.txt"));
        assertFalse(PluginExistenceCheck.isInstalled(dir, "ViaVersion"));
    }
}
