package dev.iyanz.sourbycraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.awf.AwfSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code aurora.toml} key table against the typed parsers: a file seeded from it parses to
 * {@link AuroraConfig#DEFAULT} and {@link AwfSettings#DEFAULT}, every documented lifecycle matches
 * the {@link AuroraConfig.Setting} the code declares, and the path set is the documented one.
 */
class AuroraKeysTest {

    @Test
    void aFileSeededFromTheTableParsesToTheTypedDefaults(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("aurora.toml");
        try (CommentedFileConfig f = CommentedFileConfig.builder(file).sync().preserveInsertionOrder().build()) {
            for (final ConfigKey key : AuroraKeys.KEYS) {
                f.set(key.path(), key.defaultValue() instanceof List<?> list ? new ArrayList<>(list) : key.defaultValue());
                f.setComment(key.path(), key.documentation());
            }
            for (final UtilityKeys.Section section : AuroraKeys.SECTIONS) f.setComment(section.path(), section.comment());
            f.save();
        }
        final Map<String, Object> values;
        try (CommentedFileConfig reread = CommentedFileConfig.of(file)) {
            reread.load();
            values = ConfigSnapshot.copyOf(reread).values();
            assertTrue(reread.getComment("aurora").contains("Build 49 layout"), "header on the [aurora] table");
            for (final ConfigKey key : AuroraKeys.KEYS) {
                assertTrue(reread.getComment(key.path()).startsWith(key.lifecycle() + ". Default "), key.path());
            }
        }
        final AuroraConfig.Parsed parsed = AuroraConfig.parse(new ConfigSnapshot(values));
        assertTrue(parsed.invalidKeys().isEmpty(), parsed.invalidKeys().toString());
        assertTrue(parsed.deprecatedKeys().isEmpty());
        assertEquals(AuroraConfig.DEFAULT, parsed.config(), "seeded Aurora defaults == typed defaults");
        final AwfSettings.Parsed awf = AwfSettings.parse(values);
        assertTrue(awf.invalidKeys().isEmpty(), awf.invalidKeys().toString());
        assertEquals(AwfSettings.DEFAULT, awf.settings(), "seeded AWF defaults == typed defaults");
        final String text = Files.readString(file);
        assertTrue(text.indexOf("[aurora.entity]") < text.indexOf("[aurora.awf]"), "file order follows the table");
        assertFalse(text.contains("= {}"), "no empty inline tables");
    }

    @Test
    void theDocumentedLifecyclesMatchTheSettingsTheCodeDeclares() {
        final Map<String, AuroraConfig.Setting> declared = Map.ofEntries(
            Map.entry(AuroraConfig.ASYNC_PATH_KEY, AuroraConfig.ASYNC_PATH),
            Map.entry(AuroraConfig.LANE_SAMPLING_KEY, AuroraConfig.LANE_SAMPLING),
            Map.entry(AuroraConfig.CHUNK_GENERATION_METRICS_KEY, AuroraConfig.CHUNK_GENERATION_METRICS),
            Map.entry(AuroraConfig.CPU_CORES_KEY, AuroraConfig.CPU_CORES),
            Map.entry(AuroraConfig.BRIDGE_MODE_KEY, AuroraConfig.BRIDGE_MODE),
            Map.entry(AuroraConfig.BRIDGE_QUARANTINE_KEY, AuroraConfig.BRIDGE_QUARANTINE),
            Map.entry(AuroraConfig.BRIDGE_SYNC_ROUTE_KEY, AuroraConfig.BRIDGE_SYNC_ROUTE),
            Map.entry(AuroraConfig.BRIDGE_THREAD_CHECKS_KEY, AuroraConfig.BRIDGE_THREAD_CHECKS),
            Map.entry(AuroraConfig.BRIDGE_MAX_PENDING_KEY, AuroraConfig.BRIDGE_MAX_PENDING),
            Map.entry(AuroraConfig.BRIDGE_MAX_RUNNING_ASYNC_KEY, AuroraConfig.BRIDGE_MAX_RUNNING_ASYNC),
            Map.entry(AuroraConfig.NETWORK_COUNTERS_KEY, AuroraConfig.NETWORK_COUNTERS),
            Map.entry(AuroraConfig.COMMAND_BLOCKS_KEY, AuroraConfig.COMMAND_BLOCKS),
            Map.entry(AuroraConfig.TICK_FUNCTIONS_KEY, AuroraConfig.TICK_FUNCTIONS));
        for (final Map.Entry<String, AuroraConfig.Setting> entry : declared.entrySet()) {
            final ConfigKey key = AuroraKeys.byPath(entry.getKey());
            assertNotNull(key, entry.getKey());
            assertEquals(entry.getValue().lifecycle(), key.lifecycle(), entry.getKey());
        }
        for (final ConfigKey key : AuroraKeys.KEYS) {
            if (key.path().startsWith("aurora.scheduler.") || key.path().startsWith("aurora.awf.")
                || key.path().startsWith("aurora.diagnostics.stall-recorder.")) {
                assertEquals(AuroraConfig.Lifecycle.RESTART_REQUIRED, key.lifecycle(), key.path());
            }
        }
    }

    @Test
    void thePathsAreTheDocumentedOnesAndTheTableIsComplete() {
        final Set<String> paths = new HashSet<>(AuroraKeys.paths());
        assertEquals(AuroraKeys.KEYS.size(), paths.size(), "duplicate path");
        final Set<String> expected = new HashSet<>(List.of(
            AuroraConfig.ASYNC_PATH_KEY, AuroraConfig.LANE_SAMPLING_KEY, AuroraConfig.CHUNK_GENERATION_METRICS_KEY,
            AuroraConfig.STALL_RECORDER_ENABLED_KEY, AuroraConfig.STALL_RECORDER_AFTER_KEY, AuroraConfig.STALL_RECORDER_REPEAT_KEY,
            AuroraConfig.STALL_RECORDER_MAX_DUMPS_KEY, AuroraConfig.STALL_RECORDER_KEEP_FILES_KEY, AuroraConfig.CPU_CORES_KEY,
            AuroraConfig.BRIDGE_MODE_KEY, AuroraConfig.BRIDGE_QUARANTINE_KEY, AuroraConfig.BRIDGE_SYNC_ROUTE_KEY,
            AuroraConfig.BRIDGE_THREAD_CHECKS_KEY, AuroraConfig.BRIDGE_MAX_PENDING_KEY, AuroraConfig.BRIDGE_MAX_RUNNING_ASYNC_KEY, AuroraConfig.BRIDGE_IO_THREADS_KEY,
            AuroraConfig.BRIDGE_IO_QUEUE_KEY, AuroraConfig.STORAGE_THREADS_KEY, AuroraConfig.STORAGE_QUEUE_KEY,
            AuroraConfig.NETWORK_COUNTERS_KEY, AuroraConfig.COMMAND_BLOCKS_KEY, AuroraConfig.TICK_FUNCTIONS_KEY,
            AwfSettings.WORLDS_KEY, AwfSettings.EXPORT_KEY, AwfSettings.BACKEND_KEY, AwfSettings.PERSISTENCE_KEY,
            AwfSettings.COMMIT_INTERVAL_KEY, AwfSettings.RESIDENT_CHUNKS_KEY, AwfSettings.RETAINED_GENERATIONS_KEY,
            AwfSettings.COMMIT_ATTEMPTS_KEY, AwfSettings.DELTA_GENERATIONS_KEY, AwfSettings.COMMIT_DIRTY_BYTES_KEY,
            AwfSettings.SHUTDOWN_DEADLINE_KEY));
        for (final ConfigKey key : AuroraKeys.AWF_MULTIPROFILE_KEYS) expected.add(key.path());
        for (final ConfigKey key : AuroraKeys.AWF_REDIS_FABRIC_KEYS) expected.add(key.path());
        for (final ConfigKey key : AuroraKeys.ABYSSLINK_KEYS) expected.add(key.path());
        assertEquals(expected, paths);
        assertEquals(List.of(AuroraConfig.LEGACY_ASYNC_PATH_KEY), AuroraKeys.ASYNC_PATHFINDING.legacyPaths());
        assertTrue(AuroraKeys.owns("aurora.cpu.cores"));
        assertTrue(AuroraKeys.owns(AuroraConfig.LEGACY_ASYNC_PATH_KEY));
        assertFalse(AuroraKeys.owns("console.style"));
        assertEquals(AuroraConfig.ASYNC_PATH_KEY, AuroraKeys.currentPath(AuroraConfig.LEGACY_ASYNC_PATH_KEY));
        assertEquals("aurora.cpu.cores", AuroraKeys.currentPath("aurora.cpu.cores"));
        for (final ConfigKey key : AuroraKeys.KEYS) {
            assertTrue(key.documentation().length() > 40, key.path());
            assertFalse(key.path().contains("_"), key.path() + ": kebab-case only");
        }
    }
}
