package dev.iyanz.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A changed {@code aurora.bridge.thread-checks} is reported as RESTART_REQUIRED by {@code /sourbycraft reload}. */
class ThreadChecksRestartKeyTest {

    @Test
    void aChangedThreadChecksValueIsARestartChange() {
        final ConfigSnapshot empty = new ConfigSnapshot(Map.of());
        final AuroraConfig before = AuroraConfig.parse(empty).config();
        final AuroraConfig after = AuroraConfig.parse(new ConfigSnapshot(Map.of(AuroraConfig.BRIDGE_THREAD_CHECKS_KEY, "enforced"))).config();
        assertTrue(SourbyCraftConfig.restartChanges(empty, before, empty, after).contains(AuroraConfig.BRIDGE_THREAD_CHECKS_KEY));
        assertTrue(SourbyCraftConfig.restartChanges(empty, after, empty, after).isEmpty());
    }
}
