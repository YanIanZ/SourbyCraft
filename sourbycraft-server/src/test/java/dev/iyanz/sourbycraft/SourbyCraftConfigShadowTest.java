package dev.iyanz.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The keys a deployment ends up holding twice after Aurora gained its own file.
 *
 * <p>Layering already decides which value runs, and that is tested next to the layering. What is
 * tested here is whether the operator is told: the losing value sits in a file they can open,
 * looks like a setting, and changes nothing.</p>
 */
public class SourbyCraftConfigShadowTest {

    private static ConfigSnapshot of(final Map<String, Object> values) {
        return new ConfigSnapshot(values);
    }

    @Test
    void aKeyBothFilesSetDifferentlyIsReported() {
        // Exactly the shape found on a deployed server: the split copied the key forward and
        // left the original behind, so the global file says false while aurora.toml says true.
        final List<String> shadowed = SourbyCraftConfig.shadowedAuroraKeys(
            of(Map.of(AuroraConfig.ASYNC_PATH_KEY, false)),
            of(Map.of(AuroraConfig.ASYNC_PATH_KEY, true)));

        assertEquals(List.of(AuroraConfig.ASYNC_PATH_KEY), shadowed);
    }

    @Test
    void agreeingFilesAreNotATrap() {
        // Both files say the same thing, so whichever one the operator edits, they are editing
        // the value that runs. Warning here would train them to ignore the warning.
        assertTrue(SourbyCraftConfig.shadowedAuroraKeys(
            of(Map.of(AuroraConfig.ASYNC_PATH_KEY, true)),
            of(Map.of(AuroraConfig.ASYNC_PATH_KEY, true))).isEmpty());
    }

    @Test
    void aKeyOnlyTheAuroraFileSetsIsNotShadowed() {
        assertTrue(SourbyCraftConfig.shadowedAuroraKeys(
            of(Map.of()),
            of(Map.of(AuroraConfig.ASYNC_PATH_KEY, true))).isEmpty());
    }

    @Test
    void aKeyOnlyTheGlobalFileSetsIsNotShadowed() {
        // Nothing overrides it, so it is still the value that runs -- that is the migration path
        // for a server that predates aurora.toml, not a fault.
        assertTrue(SourbyCraftConfig.shadowedAuroraKeys(
            of(Map.of(AuroraConfig.ASYNC_PATH_KEY, true)),
            of(Map.of())).isEmpty());
    }

    @Test
    void nonAuroraKeysAreLeftAlone() {
        // The unified file owns everything outside the aurora namespace; aurora.toml does not
        // shadow it, and reporting it would be noise about a file that is working correctly.
        assertTrue(SourbyCraftConfig.shadowedAuroraKeys(
            of(Map.of("sourbycraft.max-players", 0)),
            of(Map.of("sourbycraft.max-players", 40))).isEmpty());
    }

    @Test
    void everyDisagreeingAuroraKeyIsReported() {
        final List<String> shadowed = SourbyCraftConfig.shadowedAuroraKeys(
            of(Map.of(AuroraConfig.ASYNC_PATH_KEY, false,
                      AuroraConfig.LANE_SAMPLING_KEY, false)),
            of(Map.of(AuroraConfig.ASYNC_PATH_KEY, true,
                      AuroraConfig.LANE_SAMPLING_KEY, true)));

        assertEquals(2, shadowed.size(), "one operator-visible dead setting each");
        assertTrue(shadowed.contains(AuroraConfig.ASYNC_PATH_KEY));
        assertTrue(shadowed.contains(AuroraConfig.LANE_SAMPLING_KEY));
    }
}
