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

    @Test
    void schedulerBudgetsParseAndAreRestartRequired() {
        final AuroraConfig.Parsed parsed = parse(Map.of(AuroraConfig.BRIDGE_IO_THREADS_KEY, 6,
            AuroraConfig.BRIDGE_IO_QUEUE_KEY, 32, AuroraConfig.STORAGE_THREADS_KEY, 0, AuroraConfig.STORAGE_QUEUE_KEY, 8));
        assertEquals(new AuroraConfig.Scheduler(6, 32, 0, 8), parsed.config().scheduler());
        assertEquals(6, parsed.config().scheduler().resolvedBridgeIoThreads());
        assertEquals(1, parsed.config().scheduler().resolvedStorageThreads());
        final String summary = parsed.config().reloadSummary(AuroraConfig.DEFAULT);
        assertTrue(summary.contains("aurora.scheduler budgets"), summary);
    }

    @Test
    void invalidBudgetsFallBackPerKey() {
        final AuroraConfig.Parsed parsed = parse(Map.of(AuroraConfig.BRIDGE_IO_QUEUE_KEY, 0,
            AuroraConfig.STORAGE_THREADS_KEY, -1, AuroraConfig.BRIDGE_IO_THREADS_KEY, 2.5, AuroraConfig.STORAGE_QUEUE_KEY, 16));
        assertEquals(new AuroraConfig.Scheduler(0, 256, 1, 16), parsed.config().scheduler());
        assertEquals(3, parsed.invalidKeys().size());
    }

    @Test
    void networkCountersAreLive() {
        final AuroraConfig off = parse(Map.of(AuroraConfig.NETWORK_COUNTERS_KEY, false)).config();
        assertEquals(false, off.network().counters());
        assertTrue(off.reloadSummary(AuroraConfig.DEFAULT).startsWith("Aurora: 1 live change(s)"));
        assertEquals(java.util.List.of(AuroraConfig.NETWORK_COUNTERS_KEY),
            parse(Map.of(AuroraConfig.NETWORK_COUNTERS_KEY, "yes")).invalidKeys());
    }

    @Test
    void syncTasksFollowTheCallersRegionByDefault() {
        assertEquals(AuroraConfig.SyncRoute.CALLER_REGION, parse(Map.of()).config().bridge().syncRoute());
    }

    @Test
    void theSyncRouteCanBeGlobalAndATypoKeepsTheDefault() {
        assertEquals(AuroraConfig.SyncRoute.GLOBAL,
            parse(Map.of(AuroraConfig.BRIDGE_SYNC_ROUTE_KEY, "global")).config().bridge().syncRoute());
        final AuroraConfig.Parsed typo = parse(Map.of(AuroraConfig.BRIDGE_SYNC_ROUTE_KEY, "nearest"));
        assertEquals(AuroraConfig.SyncRoute.CALLER_REGION, typo.config().bridge().syncRoute());
        assertTrue(typo.invalidKeys().contains(AuroraConfig.BRIDGE_SYNC_ROUTE_KEY));
    }

    @Test
    void changingTheSyncRouteIsALiveChange() {
        final AuroraConfig before = parse(Map.of()).config();
        final AuroraConfig after = parse(Map.of(AuroraConfig.BRIDGE_SYNC_ROUTE_KEY, "global")).config();
        assertTrue(after.reloadSummary(before).startsWith("Aurora: 1 live change(s) applied; restart required: none"));
    }
}
