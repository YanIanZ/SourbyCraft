package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.CommandTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Server;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

/**
 * {@code /sys}: permission first (before the metrics or the server are touched), every sender gets
 * a reply, and unmeasured figures are placeholders, not {@code NaN}.
 */
class SysCommandTest {

    private static Server serverWithPlugins(final boolean... enabled) {
        final Server server = mock(Server.class);
        final PluginManager manager = mock(PluginManager.class);
        final Plugin[] plugins = new Plugin[enabled.length];
        for (int i = 0; i < enabled.length; i++) {
            plugins[i] = mock(Plugin.class);
            when(plugins[i].isEnabled()).thenReturn(enabled[i]);
        }
        when(manager.getPlugins()).thenReturn(plugins);
        when(server.getPluginManager()).thenReturn(manager);
        return server;
    }

    private static void assertNoLeak(final String text) {
        assertFalse(text.contains("NaN"), text);
        assertFalse(text.contains("Infinity"), text);
    }

    @Test
    void deniedBeforeTheMetricsOrTheServerAreTouched() {
        final ReplyProbe probe = new ReplyProbe();
        final AtomicInteger reads = new AtomicInteger();
        final SysCommand command = quietDenial(new SysCommand("sys", () -> {
            reads.incrementAndGet();
            return () -> healthy(20.0, 10.0);
        }));
        // Bukkit.server is null here: reading the plugin roster would throw. A denied sender must
        // get the permission message and nothing else.
        for (final Class<? extends CommandSender> type : java.util.List.of(
            Player.class, ConsoleCommandSender.class, BlockCommandSender.class)) {
            assertDoesNotThrow(() -> command.execute(probe.sender(type, false), "sys", new String[0]));
        }
        assertEquals(0, reads.get());
        final var received = probe.received();
        assertEquals(3, received.size());
        received.forEach(r -> assertEquals("no permission", r.text()));
        assertEquals("sourbycraft.command.sys", command.getPermission());
    }

    @Test
    void everySenderTypeGetsThePanelAndThePluginCounts() throws Exception {
        withServer(serverWithPlugins(true, true, false), () -> {
            for (final Class<? extends CommandSender> type : java.util.List.of(
                Player.class, ConsoleCommandSender.class, BlockCommandSender.class)) {
                final ReplyProbe probe = new ReplyProbe();
                final SysCommand command = new SysCommand("sys", () -> () -> healthy(20.0, 10.0));

                assertTrue(command.execute(probe.sender(type, true), "sys", new String[0]));

                final String all = plainReceived(probe.received());
                assertTrue(all.contains("SourbyCraft / System"), type.getSimpleName() + ": " + all);
                assertTrue(all.contains("Uptime: "), all);
                assertTrue(all.contains("Java: " + System.getProperty("java.version")), all);
                assertTrue(all.contains("Enabled / loaded: 2 / 3"), all);
                assertTrue(all.contains("Disabled / recent load failures: 1 / "), all);
                assertTrue(all.contains("RAM:"), all);
                assertTrue(all.contains("512 / 1024 MB"), all);
                assertNoLeak(all);
            }
        });
    }

    @Test
    void unmeasuredMetricsRenderAsPlaceholdersNotNaN() throws Exception {
        withServer(serverWithPlugins(), () -> {
            final ReplyProbe probe = new ReplyProbe();
            final SysCommand command = new SysCommand("sys", () -> () -> nothingMeasured());

            assertTrue(command.execute(probe.sender(ConsoleCommandSender.class, true), "sys", new String[0]));

            final String all = plainReceived(probe.received());
            assertTrue(all.contains("RAM: unavailable"), all);
            assertTrue(all.contains("Health unavailable"), all);
            assertTrue(all.contains("Freshness: UNAVAILABLE"), all);
            assertFalse(all.contains(" TPS: "), "no TPS figure was measured: " + all);
            assertFalse(all.contains("GC:"), all);
            assertFalse(all.contains("Heap:"), all);
            assertFalse(all.contains("RSS:"), all);
            assertTrue(all.contains("Enabled / loaded: 0 / 0"), all);
            assertNoLeak(all);
        });
    }

    @Test
    void anUnsetHeapMaxIsNotADivision() {
        // heapMax 0 and -1 both mean "unknown"; so does a negative used figure.
        for (final long max : new long[] {0L, -1L}) {
            final String text = plain(SysCommand.renderPerformance(() ->
                healthy(20.0, 10.0).runtime(runtime("heapUsedBytes", 100L, "heapMaxBytes", max))));
            assertFalse(text.contains("Heap:"), text);
            assertNoLeak(text);
        }
        final String negUsed = plain(SysCommand.renderPerformance(() ->
            healthy(20.0, 10.0).runtime(runtime("heapUsedBytes", -1L, "heapMaxBytes", 1024L))));
        assertFalse(negUsed.contains("Heap:"), negUsed);
    }

    @Test
    void healthyPerformanceBlockShowsEveryMeasuredFigure() {
        final String text = plain(SysCommand.renderPerformance(() -> healthy(20.0, 10.0)));

        assertTrue(text.contains("Health ") && text.contains("/100"), text);
        assertTrue(text.contains("TPS: 20.00  worst active"), text);
        assertTrue(text.contains("MSPT: worst 10.0ms  median 10.0ms  estimated p95 12.0ms"), text);
        assertTrue(text.contains("Regions: 3 active  / 4 retained"), text);
        assertTrue(text.contains("busiest 40% / avg 20%"), text);
        assertTrue(text.contains("GC: 1.0% time  2.0/min  ~5.0ms avg"), text);
        assertTrue(text.contains("Heap: 50.0%"), text);
        assertTrue(text.contains("RSS: 40.0%"), text);
        assertFalse(text.contains("CPU starvation"), text);
        assertNoLeak(text);
    }

    @Test
    void cpuStarvationIsReportedOnlyAboveOneMillisecond() {
        final var starved = healthy(20.0, 10.0).window(MetricWindow.FIVE_SECONDS,
            window("worstTps", 20.0, "worstAverageMspt", 10.0, "busiestUtilisation", 0.9,
                "averageUtilisation", 0.8, "totalMissingCpuMs", 12.0, "estimatedP95Mspt", 12.0));
        assertTrue(plain(SysCommand.renderPerformance(() -> starved)).contains("CPU starvation: 12.0ms/tick owed"));
        final var fine = healthy(20.0, 10.0).window(MetricWindow.FIVE_SECONDS,
            window("worstTps", 20.0, "worstAverageMspt", 10.0, "busiestUtilisation", 0.9,
                "averageUtilisation", 0.8, "totalMissingCpuMs", 0.5, "estimatedP95Mspt", 12.0));
        assertFalse(plain(SysCommand.renderPerformance(() -> fine)).contains("CPU starvation"));
    }

    @Test
    void healthScoreStaysWithin0And100WhateverTheMetrics() {
        final var awful = healthy(1.0, 5_000.0)
            .window(MetricWindow.FIVE_SECONDS, window("worstTps", 1.0, "worstAverageMspt", 5000.0,
                "busiestUtilisation", 50.0, "estimatedP95Mspt", 9_000.0, "totalMissingCpuMs", 1.0e9))
            .runtime(runtime("heapUsedBytes", 2048L << 20, "heapMaxBytes", 1024L << 20, "gcTimePercent", 90.0,
                "gcCollectionsPerMinute", 1.0, "averageGcPauseMs", 1.0));
        final String text = plain(SysCommand.renderPerformance(() -> awful));
        assertTrue(text.contains("0/100"), text);
        assertNoLeak(text);
    }

    @Test
    void staleFreshnessIsShownInSysToo() {
        final String text = plain(SysCommand.renderPerformance(() ->
            healthy(20.0, 10.0).fresh(freshness(MetricState.STALE, 9_000L, "late"))));
        assertTrue(text.contains("Freshness: STALE (9000ms old): late"), text);
    }

    @Test
    void theProductionConstructorExecutesAgainstTheRealProvider() throws Exception {
        withServer(serverWithPlugins(true), () -> {
            final ReplyProbe probe = new ReplyProbe();
            assertTrue(new SysCommand("sys").execute(probe.sender(ConsoleCommandSender.class, true), "sys", new String[0]));
            final String all = plainReceived(probe.received());
            assertTrue(all.contains("SourbyCraft / System"), all);
            assertFalse(all.contains("NaN"), all);
        });
    }
}
