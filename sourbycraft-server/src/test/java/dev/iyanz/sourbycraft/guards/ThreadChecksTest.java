package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.AuroraConfig.ThreadChecks;
import org.junit.jupiter.api.Test;

/**
 * The decision behind {@code aurora.bridge.thread-checks} (owner decision 2026-10-22, "Tiru penuh
 * LightingLuminol"): what a failed region-ownership check does, and that the relaxation only exists while the
 * bridge admits legacy plugins. The static fields are resolved once per JVM, so only the pure decisions are
 * pinned here; the boot report and rate limiting are read-tested through their inputs.
 */
class ThreadChecksTest {

    @Test
    void theOwnerDefaultIsRelaxed() {
        assertEquals(ThreadChecks.RELAXED, AuroraConfig.Bridge.DEFAULT.threadChecks());
    }

    @Test
    void nothingIsRelaxedWhileTheBridgeIsOff() {
        for (final ThreadChecks configured : ThreadChecks.values()) {
            assertEquals(ThreadChecks.ENFORCED, dev.iyanz.sourbycraft.guards.ThreadChecks.decide(configured, false), configured.name());
            assertTrue(dev.iyanz.sourbycraft.guards.ThreadChecks.refuses(configured, false, true), configured.name());
            assertTrue(dev.iyanz.sourbycraft.guards.ThreadChecks.refuses(configured, false, false), configured.name());
        }
    }

    @Test
    void enforcedAlwaysRefuses() {
        assertTrue(dev.iyanz.sourbycraft.guards.ThreadChecks.refuses(ThreadChecks.ENFORCED, true, true));
        assertTrue(dev.iyanz.sourbycraft.guards.ThreadChecks.refuses(ThreadChecks.ENFORCED, true, false));
    }

    @Test
    void relaxedNeverRefusesWhileTheBridgeAdmits() {
        assertFalse(dev.iyanz.sourbycraft.guards.ThreadChecks.refuses(ThreadChecks.RELAXED, true, true));
        assertFalse(dev.iyanz.sourbycraft.guards.ThreadChecks.refuses(ThreadChecks.RELAXED, true, false), "LightingLuminol's scope: every caller");
    }

    @Test
    void bridgedRelaxesOnlyInsideABridgedBody() {
        assertFalse(dev.iyanz.sourbycraft.guards.ThreadChecks.refuses(ThreadChecks.BRIDGED, true, true));
        assertTrue(dev.iyanz.sourbycraft.guards.ThreadChecks.refuses(ThreadChecks.BRIDGED, true, false), "a native plugin or the server itself keeps the check");
    }

    @Test
    void theConfiguredValueParsesLeniently() {
        assertEquals(ThreadChecks.RELAXED, ThreadChecks.parse(" Relaxed "));
        assertEquals(ThreadChecks.BRIDGED, ThreadChecks.parse("bridged"));
        assertEquals(ThreadChecks.ENFORCED, ThreadChecks.parse("ENFORCED"));
        assertNull(ThreadChecks.parse("off"));
        assertNull(ThreadChecks.parse(true));
        assertNull(ThreadChecks.parse(null));
        assertEquals("relaxed", ThreadChecks.RELAXED.configValue());
    }

    @Test
    void inThisJvmTheBridgeIsOffSoTheChecksAreEnforced() {
        // Unit tests run without a server directory: no TOML, so the bridge is off and nothing is relaxed,
        // whatever the owner default says. Production relaxation is observed on the agent test server.
        assertEquals(ThreadChecks.ENFORCED, dev.iyanz.sourbycraft.guards.ThreadChecks.effective());
        assertFalse(dev.iyanz.sourbycraft.guards.ThreadChecks.RELAXED_SCOPE);
        assertTrue(dev.iyanz.sourbycraft.guards.ThreadChecks.refuse("Cannot place block async"));
        assertEquals(0L, dev.iyanz.sourbycraft.guards.ThreadChecks.relaxedHits());
        assertTrue(dev.iyanz.sourbycraft.guards.ThreadChecks.describe().startsWith("enforced"));
    }

    @Test
    void theBootReportWarnsOnlyWhenSomethingIsRelaxed() {
        final java.util.List<String> warned = new java.util.ArrayList<>();
        final java.util.List<String> informed = new java.util.ArrayList<>();
        dev.iyanz.sourbycraft.guards.ThreadChecks.bootReport(warned::add, informed::add);
        assertTrue(warned.isEmpty(), "enforced in this JVM: no WARN");
        // The owner default is configured but inactive (bridge off): one INFO saying so.
        assertEquals(1, informed.size(), informed.toString());
        assertTrue(informed.get(0).contains("inactive while aurora.bridge.mode = off"), informed.get(0));
    }
}
