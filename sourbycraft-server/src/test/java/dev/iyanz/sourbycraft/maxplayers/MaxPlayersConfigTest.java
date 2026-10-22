package dev.iyanz.sourbycraft.maxplayers;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.SourbyCraftConfigTesting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static dev.iyanz.sourbycraft.maxplayers.TestConfigOverride.proxy;
import static dev.iyanz.sourbycraft.maxplayers.TestConfigOverride.withConfig;
import static dev.iyanz.sourbycraft.maxplayers.TestConfigOverride.withServer;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 / TEST-COVERAGE-GAPS #3: {@code /maxp} persistence and its boot re-apply.
 *
 * <p>{@link MaxPlayersConfig#persist(int)} writes through {@code SourbyCraftConfig.setAndSave}.
 * The test seam points that at a {@code @TempDir} file, so the success path is tested without
 * touching {@code sourbycraft_config/} in the working directory; the rejection paths return before
 * any file access.</p>
 */
class MaxPlayersConfigTest {

    @Test
    void persistRejectsOutOfRangeCountsWithoutWriting() {
        assertFalse(MaxPlayersConfig.persist(0));
        assertFalse(MaxPlayersConfig.persist(-1));
        assertFalse(MaxPlayersConfig.persist(Integer.MIN_VALUE));
        assertFalse(MaxPlayersConfig.persist(100_001));
        assertFalse(MaxPlayersConfig.persist(Integer.MAX_VALUE));
    }

    @Test
    void persistWritesTheKeyAndTheLiveSnapshotSeesIt(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("sourbycraft_global_config.toml");
        SourbyCraftConfigTesting.withConfigFile(Map.of(), file, () -> {
            assertEquals(0, SourbyCraftConfig.cfgInt(MaxPlayersConfig.KEY, 0));
            assertTrue(MaxPlayersConfig.persist(64));
            assertEquals(64, SourbyCraftConfig.cfgInt(MaxPlayersConfig.KEY, 0), "live snapshot updated");
            try (CommentedFileConfig onDisk = CommentedFileConfig.of(file)) {
                onDisk.load();
                assertEquals(64, ((Number) onDisk.get(MaxPlayersConfig.KEY)).intValue());
                assertNotNull(onDisk.getComment(MaxPlayersConfig.KEY), "first write adds the explanatory comment");
            }
        });
    }

    @Test
    void persistOverwritesAPreviousValueAndKeepsItsComment(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("sourbycraft_global_config.toml");
        SourbyCraftConfigTesting.withConfigFile(Map.of(), file, () -> {
            assertTrue(MaxPlayersConfig.persist(20));
            assertTrue(MaxPlayersConfig.persist(35));
            assertEquals(35, SourbyCraftConfig.cfgInt(MaxPlayersConfig.KEY, 0));
            try (CommentedFileConfig onDisk = CommentedFileConfig.of(file)) {
                onDisk.load();
                assertEquals(35, ((Number) onDisk.get(MaxPlayersConfig.KEY)).intValue());
                assertNotNull(onDisk.getComment(MaxPlayersConfig.KEY));
            }
        });
    }

    @Test
    void persistedValueIsReAppliedAtBoot(@TempDir final Path dir) throws Exception {
        final List<Integer> calls = new ArrayList<>();
        final Server server = proxy(Server.class, method -> null, (method, args) -> {
            if (method.getName().equals("setMaxPlayers")) calls.add((Integer) args[0]);
        });
        SourbyCraftConfigTesting.withConfigFile(Map.of(), dir.resolve("g.toml"), () -> {
            assertTrue(MaxPlayersConfig.persist(77));
            withServer(server, MaxPlayersConfig::applyAtBoot);
        });
        assertEquals(List.of(77), calls);
    }

    @Test
    void persistReportsFailureWhenTheFileCannotBeWritten(@TempDir final Path dir) throws Exception {
        // The config path is a directory, so the file cannot be opened/saved: persist returns false, never throws.
        final Path blocked = dir.resolve("is-a-directory.toml");
        Files.createDirectory(blocked);
        SourbyCraftConfigTesting.withConfigFile(Map.of(), blocked, () -> assertFalse(MaxPlayersConfig.persist(10)));
    }

    @Test
    void theSeamRestoresTheSnapshotAndFile(@TempDir final Path dir) throws Exception {
        SourbyCraftConfigTesting.withConfig(Map.of(MaxPlayersConfig.KEY, 5), () ->
            assertEquals(5, SourbyCraftConfig.cfgInt(MaxPlayersConfig.KEY, 0)));
        assertEquals(0, SourbyCraftConfig.cfgInt(MaxPlayersConfig.KEY, 0), "snapshot restored after the block");
        assertThrows(IllegalStateException.class, () ->
            SourbyCraftConfigTesting.withConfigFile(Map.of(), dir.resolve("x.toml"), () -> {
                assertTrue(MaxPlayersConfig.persist(9));
                throw new IllegalStateException("boom");
            }));
        assertEquals(0, SourbyCraftConfig.cfgInt(MaxPlayersConfig.KEY, 0), "restored even when the body throws");
    }

    @Test
    void bootAppliesAPersistedCount() throws Exception {
        assertEquals(List.of(42), applied(Map.of(MaxPlayersConfig.KEY, 42)));
        assertEquals(List.of(100_000), applied(Map.of(MaxPlayersConfig.KEY, 100_000)), "upper bound is inclusive");
    }

    @Test
    void bootAcceptsAnyNumericTomlType() throws Exception {
        assertEquals(List.of(7), applied(Map.of(MaxPlayersConfig.KEY, 7L)));
        assertEquals(List.of(12), applied(Map.of(MaxPlayersConfig.KEY, 12.9D)), "fraction truncates like Number.intValue");
    }

    @Test
    void bootIgnoresAbsentZeroNegativeOversizedAndNonNumericValues() throws Exception {
        assertEquals(List.of(), applied(Map.of()));
        assertEquals(List.of(), applied(Map.of(MaxPlayersConfig.KEY, 0)));
        assertEquals(List.of(), applied(Map.of(MaxPlayersConfig.KEY, -5)));
        assertEquals(List.of(), applied(Map.of(MaxPlayersConfig.KEY, 100_001)));
        assertEquals(List.of(), applied(Map.of(MaxPlayersConfig.KEY, "50")));
        assertEquals(List.of(), applied(Map.of(MaxPlayersConfig.KEY, "abc")));
        assertEquals(List.of(), applied(Map.of(MaxPlayersConfig.KEY, true)));
    }

    @Test
    void bootNeverThrowsWhenTheServerRejectsTheCount() throws Exception {
        final Server server = proxy(Server.class, method -> {
            if (method.getName().equals("setMaxPlayers")) throw new IllegalStateException("refused");
            return null;
        }, (method, args) -> {});

        withConfig(Map.of(MaxPlayersConfig.KEY, 10),
            () -> withServer(server, () -> assertDoesNotThrow(MaxPlayersConfig::applyAtBoot)));
    }

    private static List<Integer> applied(final Map<String, Object> config) throws Exception {
        final List<Integer> calls = new ArrayList<>();
        final Server server = proxy(Server.class, method -> null, (method, args) -> {
            if (method.getName().equals("setMaxPlayers")) calls.add((Integer) args[0]);
        });
        withConfig(config, () -> withServer(server, MaxPlayersConfig::applyAtBoot));
        return calls;
    }
}
