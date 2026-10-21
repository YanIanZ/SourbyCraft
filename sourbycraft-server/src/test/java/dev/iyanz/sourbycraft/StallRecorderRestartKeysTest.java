package dev.iyanz.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An edited stall-recorder key stays listed as restart-pending until the process restarts. */
class StallRecorderRestartKeysTest {

    @Test
    void pendingRestartKeysListTheRecorder() {
        final ConfigSnapshot empty = new ConfigSnapshot(Map.of());
        final AuroraConfig changed = AuroraConfig.parse(new ConfigSnapshot(
            Map.of(AuroraConfig.STALL_RECORDER_ENABLED_KEY, false))).config();
        assertEquals(List.of("aurora.diagnostics.stall-recorder.*"),
            SourbyCraftConfig.restartChanges(empty, AuroraConfig.DEFAULT, empty, changed));
        assertTrue(SourbyCraftConfig.restartChanges(empty, changed, empty, changed).isEmpty());
    }
}
