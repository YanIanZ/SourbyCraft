package dev.iyanz.sourbycraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One-time TOML -> YAML conversion for SourbyCraft's own config files. */
class ConfigFormatMigrationTest {

    @TempDir Path dir;

    @Test
    void convertsValuesAndCommentsAndRenamesTheOldFile() throws Exception {
        final Path legacy = this.dir.resolve("aurora.toml");
        Files.writeString(legacy, """
            # Aurora services (test)
            [aurora.bridge]
            # Mode: off or safe.
            mode = "safe"
            quarantine-after = 7
            limits = [1, 2, 3]
            """);
        final Path yaml = this.dir.resolve("aurora.yml");
        assertEquals(yaml, ConfigFormatMigration.choose(yaml, legacy));
        assertTrue(Files.isRegularFile(yaml), "the YAML file is written");
        assertFalse(Files.exists(legacy), "the old file is renamed");
        assertTrue(Files.isRegularFile(this.dir.resolve("aurora.toml.migrated")), "the old bytes are kept");

        final CommentedConfig config = ConfigSources.read(yaml);
        assertEquals("safe", config.get("aurora.bridge.mode"));
        assertEquals(Integer.valueOf(7), config.get("aurora.bridge.quarantine-after"));
        final CommentedConfig aurora = config.get("aurora");
        assertEquals("Aurora services (test)", aurora.getComment("bridge"),
            "the header comment stays on the first table it was written above");
        assertEquals("Mode: off or safe.", ((CommentedConfig) aurora.get("bridge")).getComment("mode"));
        assertTrue(Files.readString(yaml).contains("# Mode: off or safe."), "comments are written");
        assertTrue(Files.readString(yaml).contains("mode: \"safe\""), "strings are quoted");
    }

    @Test
    void anUnparseableOldFileIsLeftAlone() throws Exception {
        final Path legacy = this.dir.resolve("aurora.toml");
        final String broken = "[aurora.awf]\nworlds = sourby\n";
        Files.writeString(legacy, broken);
        final Path yaml = this.dir.resolve("aurora.yml");
        assertEquals(legacy, ConfigFormatMigration.choose(yaml, legacy), "the readable path is the old file");
        assertFalse(Files.exists(yaml), "no YAML file is invented");
        assertEquals(broken, Files.readString(legacy), "the operator's bytes are untouched");
    }

    @Test
    void anExistingYamlFileWinsAndTheOldFileIsNotConverted() throws Exception {
        final Path legacy = this.dir.resolve("aurora.toml");
        final Path yaml = this.dir.resolve("aurora.yml");
        Files.writeString(legacy, "[aurora.bridge]\nmode = \"off\"\n");
        Files.writeString(yaml, "aurora:\n  bridge:\n    mode: \"safe\"\n");
        assertEquals(yaml, ConfigFormatMigration.choose(yaml, legacy));
        assertTrue(Files.exists(legacy), "the stale TOML is not touched");
        assertEquals("safe", ConfigSources.read(yaml).get("aurora.bridge.mode"));
    }

    @Test
    void aSecondBackupIsNumbered() throws Exception {
        final Path legacy = this.dir.resolve("aurora.toml");
        Files.writeString(legacy, "[aurora.bridge]\nmode = \"safe\"\n");
        Files.writeString(this.dir.resolve("aurora.toml.migrated"), "old");
        assertEquals(this.dir.resolve("aurora.toml.migrated.2"), ConfigFormatMigration.backupPath(legacy));
    }

    @Test
    void aReadOnlyDirectoryKeepsReadingTheOldFile() throws Exception {
        final Path directory = Files.createDirectory(this.dir.resolve("readonly"));
        final Path legacy = directory.resolve("aurora.toml");
        Files.writeString(legacy, "[aurora.bridge]\nmode = \"safe\"\n");
        assertTrue(directory.toFile().setWritable(false), "directory made read-only");
        try {
            final Path yaml = directory.resolve("aurora.yml");
            assertEquals(legacy, ConfigFormatMigration.choose(yaml, legacy));
            assertFalse(Files.exists(yaml));
            assertEquals("safe", ConfigSources.read(legacy).get("aurora.bridge.mode"));
        } finally {
            assertTrue(directory.toFile().setWritable(true));
        }
    }

    @Test
    void configSourcesPreferTheYamlSiblingAndFallBackToToml() throws Exception {
        final Path yaml = this.dir.resolve("aurora.yml");
        final Path legacy = this.dir.resolve("aurora.toml");
        assertNull(ConfigSources.existing(yaml), "neither file exists");
        Files.writeString(legacy, "aurora: {}\n");
        assertEquals(legacy, ConfigSources.existing(yaml), "the TOML is read until it is converted");
        Files.writeString(yaml, "aurora: {}\n");
        assertEquals(yaml, ConfigSources.existing(yaml), "the YAML wins once it exists");
        assertEquals(legacy, ConfigSources.legacySibling(yaml));
        assertNull(ConfigSources.legacySibling(legacy));
    }
}
