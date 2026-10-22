package dev.iyanz.sourbycraft;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class ConfigPresentationTest {
    @Test void v24RestartComparisonKeepsChangesPendingUntilTheyMatchBootAgain() {
        var boot = new ConfigSnapshot(Map.of("ui.console-style", "auto", "ui.plugins-page-size", 12));
        var edited = new ConfigSnapshot(Map.of("ui.console-style", "plain", "ui.plugins-page-size", 5));
        var aurora = AuroraConfig.DEFAULT;
        assertEquals(java.util.List.of("ui.console-style"), SourbyCraftConfig.restartChanges(boot, aurora, edited, aurora));
        // A second reload of identical disk values does not clear pending restart changes.
        assertEquals(java.util.List.of("ui.console-style"), SourbyCraftConfig.restartChanges(boot, aurora, edited, aurora));
        assertTrue(SourbyCraftConfig.restartChanges(boot, aurora, boot, aurora).isEmpty());
        var changedCpu = AuroraConfig.parse(new ConfigSnapshot(Map.of(AuroraConfig.CPU_CORES_KEY, 2))).config();
        assertTrue(SourbyCraftConfig.restartChanges(boot, aurora, boot, changedCpu).contains(AuroraConfig.CPU_CORES_KEY));
    }

    @Test void v24NewUtilityConfigHasUiDefaultsAndLifecycleComments(@TempDir Path directory) throws Exception {
        var flag = SourbyCraftConfig.class.getDeclaredField("newFile");
        flag.setAccessible(true);
        boolean previous = flag.getBoolean(null);
        var seed = SourbyCraftConfig.class.getDeclaredMethod("seedDefaults", CommentedFileConfig.class);
        seed.setAccessible(true);
        Path file = directory.resolve("new.toml");
        try (var config = CommentedFileConfig.builder(file).sync().build()) {
            flag.setBoolean(null, true);
            seed.invoke(null, config);
        } finally { flag.setBoolean(null, previous); }
        try (var reread = CommentedFileConfig.of(file)) {
            reread.load();
            assertEquals("auto", reread.get("ui.console-style"));
            assertEquals(12, (int)reread.get("ui.plugins-page-size"));
            assertTrue(reread.getComment("ui.console-style").contains("RESTART_REQUIRED"));
            assertTrue(reread.getComment("ui.plugins-page-size").contains("LIVE"));
        }
        String generated = Files.readString(file);
        assertFalse(generated.contains("Powered by Canvas"));
        assertFalse(generated.contains("TPS anti drop"));
    }

    @Test void v24UnreadableAuroraFileDoesNotPublishOrReportSuccess(@TempDir Path directory) throws Exception {
        var utilityField = SourbyCraftConfig.class.getDeclaredField("FILE");
        var auroraField = SourbyCraftConfig.class.getDeclaredField("AURORA_FILE");
        utilityField.setAccessible(true);
        auroraField.setAccessible(true);
        Object oldUtility = utilityField.get(null), oldAurora = auroraField.get(null);
        Path utilityPath = directory.resolve("utility.toml"), auroraPath = directory.resolve("aurora.toml");
        Files.writeString(utilityPath, "[ui]\nplugins-page-size = 4\n");
        Files.writeString(auroraPath, "[aurora.cpu]\ncores = [broken\n");
        int previousSize = SourbyCraftConfig.cfgInt("ui.plugins-page-size", 12);
        try (var utility = CommentedFileConfig.of(utilityPath); var aurora = CommentedFileConfig.of(auroraPath)) {
            utilityField.set(null, utility);
            auroraField.set(null, aurora);
            var report = SourbyCraftConfig.reloadDetailed();
            assertFalse(report.successful());
            assertTrue(report.lines().stream().anyMatch(line -> line.contains("No new config snapshot")));
            assertEquals(previousSize, SourbyCraftConfig.cfgInt("ui.plugins-page-size", 12));
            assertEquals("[ui]\nplugins-page-size = 4\n", Files.readString(utilityPath));
        } finally {
            utilityField.set(null, oldUtility);
            auroraField.set(null, oldAurora);
        }
    }
}
