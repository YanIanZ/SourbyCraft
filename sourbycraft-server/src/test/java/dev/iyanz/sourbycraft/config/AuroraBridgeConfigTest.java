package dev.iyanz.sourbycraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The bridge stays off unless an operator says safe, and a typo never turns it on. */
class AuroraBridgeConfigTest {

    private static AuroraConfig.Parsed parse(final Map<String, Object> values) {
        return AuroraConfig.parse(new ConfigSnapshot(values));
    }

    @Test
    void theDefaultIsOff() {
        assertEquals(AuroraConfig.BridgeMode.OFF, AuroraConfig.DEFAULT.bridge().mode());
        assertEquals(3, AuroraConfig.DEFAULT.bridge().quarantineAfter());
    }

    @Test
    void safeIsAccepted() {
        final AuroraConfig.Parsed parsed = parse(Map.of(AuroraConfig.BRIDGE_MODE_KEY, "Safe",
            AuroraConfig.BRIDGE_QUARANTINE_KEY, 5));
        assertEquals(AuroraConfig.BridgeMode.SAFE, parsed.config().bridge().mode());
        assertEquals(5, parsed.config().bridge().quarantineAfter());
        assertTrue(parsed.invalidKeys().isEmpty());
    }

    @Test
    void anUnknownModeFallsBackToOff() {
        final AuroraConfig.Parsed parsed = parse(Map.of(AuroraConfig.BRIDGE_MODE_KEY, "on"));
        assertEquals(AuroraConfig.BridgeMode.OFF, parsed.config().bridge().mode());
        assertEquals(java.util.List.of(AuroraConfig.BRIDGE_MODE_KEY), parsed.invalidKeys());
    }

    @Test
    void aQuarantineBelowOneIsInvalid() {
        final AuroraConfig.Parsed parsed = parse(Map.of(AuroraConfig.BRIDGE_QUARANTINE_KEY, 0));
        assertEquals(3, parsed.config().bridge().quarantineAfter());
        assertEquals(java.util.List.of(AuroraConfig.BRIDGE_QUARANTINE_KEY), parsed.invalidKeys());
    }

    @Test
    void aModeChangeIsReportedAsRestartRequired() {
        final AuroraConfig safe = new AuroraConfig(AuroraConfig.DEFAULT.entity(), AuroraConfig.DEFAULT.diagnostics(),
            AuroraConfig.DEFAULT.cpu(), new AuroraConfig.Bridge(AuroraConfig.BridgeMode.SAFE, 3));
        final String summary = safe.reloadSummary(AuroraConfig.DEFAULT);
        assertTrue(summary.contains("0 live change(s)"), summary);
        assertTrue(summary.contains(AuroraConfig.BRIDGE_MODE_KEY + " (OFF -> SAFE)"), summary);
    }

    @Test
    void aQuarantineChangeIsLive() {
        final AuroraConfig stricter = new AuroraConfig(AuroraConfig.DEFAULT.entity(), AuroraConfig.DEFAULT.diagnostics(),
            AuroraConfig.DEFAULT.cpu(), new AuroraConfig.Bridge(AuroraConfig.BridgeMode.OFF, 1));
        assertTrue(stricter.reloadSummary(AuroraConfig.DEFAULT).contains("1 live change(s)"));
    }
}
