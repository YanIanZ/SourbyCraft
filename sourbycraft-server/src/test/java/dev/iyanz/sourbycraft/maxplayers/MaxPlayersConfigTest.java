package dev.iyanz.sourbycraft.maxplayers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import static dev.iyanz.sourbycraft.maxplayers.TestConfigOverride.proxy;
import static dev.iyanz.sourbycraft.maxplayers.TestConfigOverride.withConfig;
import static dev.iyanz.sourbycraft.maxplayers.TestConfigOverride.withServer;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * OBS-14 / TEST-COVERAGE-GAPS #3: {@code /maxp} persistence and its boot re-apply.
 *
 * <p>The successful {@link MaxPlayersConfig#persist(int)} path writes the real
 * {@code sourbycraft_config/sourbycraft_global_config.toml} relative to the working directory
 * (no path seam in {@code SourbyCraftConfig}), so only its rejection paths are tested here; they
 * return before any file access.</p>
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
