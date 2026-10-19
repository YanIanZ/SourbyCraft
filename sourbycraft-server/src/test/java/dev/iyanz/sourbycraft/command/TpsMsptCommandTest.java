package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.CommandTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * {@code /tps} and {@code /mspt}: permission first, any sender gets a reply, and a measurement the
 * collector could not make (NaN, -1) is rendered as the placeholder {@code unavailable}, never as
 * {@code NaN}, {@code Infinity} or a negative duration.
 */
class TpsMsptCommandTest {

    private static void assertNoLeak(final String text) {
        assertFalse(text.contains("NaN"), text);
        assertFalse(text.contains("Infinity"), text);
        assertFalse(text.contains("-1"), text);
    }

    /** A metrics source that counts how often it is read. */
    private static final class CountingSource implements java.util.function.Supplier<dev.iyanz.sourbycraft.api.metrics.SourbyMetrics> {
        final AtomicInteger reads = new AtomicInteger();
        final dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot snapshot;

        CountingSource(final dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public dev.iyanz.sourbycraft.api.metrics.SourbyMetrics get() {
            this.reads.incrementAndGet();
            return () -> this.snapshot;
        }
    }

    // ----------------------------------------------------------------------------- permission

    @Test
    void tpsDeniedBeforeAnyMetricsRead() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final CountingSource source = new CountingSource(healthy(20.0, 10.0));
        final TpsCommand command = quietDenial(new TpsCommand("tps", source));

        for (final Class<? extends CommandSender> type : java.util.List.of(
            Player.class, ConsoleCommandSender.class, BlockCommandSender.class)) {
            assertTrue(command.execute(probe.sender(type, false), "tps", new String[0]));
        }
        assertEquals(0, source.reads.get(), "no metrics were read for a denied sender");
        final var received = probe.received();
        assertEquals(3, received.size());
        received.forEach(r -> assertEquals("no permission", r.text()));
    }

    @Test
    void msptDeniedBeforeAnyMetricsRead() throws Exception {
        final ReplyProbe probe = new ReplyProbe();
        final CountingSource source = new CountingSource(healthy(20.0, 10.0));
        final MsptCommand command = quietDenial(new MsptCommand("mspt", source));

        assertTrue(command.execute(probe.sender(Player.class, false), "mspt", new String[0]));
        assertEquals(0, source.reads.get());
        assertEquals("no permission", probe.next().text());
    }

    @Test
    void permissionNodesAreTheDocumentedOnes() {
        assertEquals("sourbycraft.command.tps", new TpsCommand("tps").getPermission());
        assertEquals("sourbycraft.command.mspt", new MsptCommand("mspt").getPermission());
        assertTrue(new TpsCommand("tps").getAliases().contains("lag"));
    }

    // ------------------------------------------------------------------------- non-player senders

    @Test
    void everySenderTypeGetsAPanelNotAnException() {
        for (final Class<? extends CommandSender> type : java.util.List.of(
            Player.class, ConsoleCommandSender.class, BlockCommandSender.class)) {
            final ReplyProbe probe = new ReplyProbe();
            final CommandSender sender = probe.sender(type, true);
            assertDoesNotThrow(() -> new TpsCommand("tps", () -> () -> healthy(19.0, 30.0)).execute(sender, "tps", new String[0]));
            assertDoesNotThrow(() -> new MsptCommand("mspt", () -> () -> healthy(19.0, 30.0)).execute(sender, "mspt", new String[0]));
            final String all = plainReceived(probe.received());
            assertTrue(all.contains("SourbyCraft / TPS"), type.getSimpleName() + ": " + all);
            assertTrue(all.contains("SourbyCraft / MSPT"), type.getSimpleName() + ": " + all);
        }
    }

    @Test
    void theProductionConstructorsReadTheRealProvider() {
        // Whatever the provider holds (warming, or a live snapshot when another test started it),
        // the panel renders and carries no unrendered sentinel.
        final ReplyProbe probe = new ReplyProbe();
        final CommandSender sender = probe.sender(ConsoleCommandSender.class, true);
        assertTrue(new TpsCommand("tps").execute(sender, "tps", new String[0]));
        assertTrue(new MsptCommand("mspt").execute(sender, "mspt", new String[0]));
        final String all = plainReceived(probe.received());
        assertTrue(all.contains("SourbyCraft / TPS") && all.contains("SourbyCraft / MSPT"), all);
        assertFalse(all.contains("NaN"), all);
    }

    // --------------------------------------------------------------------- unavailable metrics

    @Test
    void tpsWithNothingMeasuredShowsPlaceholdersNotNaN() {
        final String text = plain(TpsCommand.render(() -> nothingMeasured()));

        assertNoLeak(text);
        assertTrue(text.contains("Target unavailable TPS"), text);
        assertTrue(text.contains("5s   unavailable"), text);
        assertTrue(text.contains("15m  unavailable"), text);
        assertTrue(text.contains("Worst average MSPT: unavailable"), text);
        assertTrue(text.contains("Active regions: unavailable"), text);
        assertTrue(text.contains("Global: unavailable"), text);
        assertTrue(text.contains("Tier: unavailable"), text);
        assertTrue(text.contains("Freshness: UNAVAILABLE"), text);
        assertFalse(text.contains("Memory"), "no heap figure, so no memory line: " + text);
        assertFalse(text.contains("GC:"), text);
    }

    @Test
    void tpsWithAZeroOrNegativeTargetNeverDividesByIt() {
        for (final double target : new double[] {0.0, -20.0, Double.POSITIVE_INFINITY}) {
            final String text = plain(TpsCommand.render(() -> healthy(20.0, 10.0).target(target)));
            assertNoLeak(text);
            assertTrue(text.contains("Tier: unavailable"), "target " + target + ": " + text);
            assertTrue(text.contains("5s   unavailable"), "target " + target + ": " + text);
        }
    }

    @Test
    void tpsHeapAndGcAreOmittedWhenTheRuntimeSentinelsAreInTheSnapshot() {
        // heapMax -1 / heapUsed -1 / gc NaN: what RuntimeSampler publishes when a read fails.
        final String text = plain(TpsCommand.render(() -> healthy(20.0, 10.0)
            .runtime(runtime("heapUsedBytes", -1L, "heapMaxBytes", -1L))));
        assertNoLeak(text);
        assertFalse(text.contains("Memory"), text);
        assertFalse(text.contains("GC:"), text);
        // A max with no used figure is also not a percentage.
        final String partial = plain(TpsCommand.render(() -> healthy(20.0, 10.0)
            .runtime(runtime("heapUsedBytes", -1L, "heapMaxBytes", 1024L))));
        assertFalse(partial.contains("Memory"), partial);
        assertNoLeak(partial);
    }

    @Test
    void tpsHealthyServerShowsBarsMemoryTierAndCapsTpsAtTheTarget() {
        // The collector can measure 25 TPS over a window (catch-up ticks); the panel never shows
        // more than the target.
        final String text = plain(TpsCommand.render(() -> healthy(25.0, 10.0)));

        assertNoLeak(text);
        assertTrue(text.contains("Target 20.00 TPS"), text);
        assertTrue(text.contains("Worst 20.00"), text);
        assertFalse(text.contains("25.00"), text);
        assertTrue(text.contains("Median 20.00 / Aggregate 20.00"), text);
        assertTrue(text.contains("Memory 50.0%"), text);
        assertTrue(text.contains("GC: 1.0% time  2.0/min  ~5.0ms avg"), text);
        assertTrue(text.contains("Active regions: 3"), text);
        assertTrue(text.contains("Tier: GREEN"), text);
        assertTrue(text.contains("Freshness: AVAILABLE"), text);
        assertTrue(text.contains("Global: TPS 20.00 / MSPT 10.0ms"), text);
    }

    @Test
    void tpsTierFollowsTheWorstRegion() {
        assertTrue(plain(TpsCommand.render(() -> healthy(17.0, 30.0))).contains("Tier: YELLOW"));
        assertTrue(plain(TpsCommand.render(() -> healthy(10.0, 80.0))).contains("Tier: RED"));
        // Fast enough but the tick budget (50 ms at 20 TPS) is blown: not GREEN.
        assertTrue(plain(TpsCommand.render(() -> healthy(20.0, 55.0))).contains("Tier: RED"));
    }

    @Test
    void tpsStaleAndWarmingFreshnessAreLabelled() {
        final String stale = plain(TpsCommand.render(() ->
            healthy(20.0, 10.0).fresh(freshness(MetricState.STALE, 7_500L, "collector late"))));
        assertTrue(stale.contains("Freshness: STALE (7500ms old): collector late"), stale);
        final String warming = plain(TpsCommand.render(() ->
            nothingMeasured().fresh(freshness(MetricState.WARMING, 0L, "Awaiting first collection"))));
        assertTrue(warming.contains("Freshness: WARMING: Awaiting first collection"), warming);
        assertNoLeak(warming);
    }

    @Test
    void tpsTopologyReadsUnavailableOnlyWhenTheSnapshotIsUnavailable() {
        assertTrue(plain(TpsCommand.render(() -> healthy(20.0, 10.0).regions(0, 0))).contains("Active regions: 0"));
        assertTrue(plain(TpsCommand.render(() -> healthy(20.0, 10.0)
            .fresh(freshness(MetricState.UNAVAILABLE, 0L, "x")))).contains("Active regions: unavailable"));
    }

    @Test
    void tpsHelpersDegradeNaNAndInfinityToUnavailable() {
        for (final double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals("unavailable", TpsCommand.ms(bad));
            assertEquals("unavailable", TpsCommand.value(bad, 2));
            assertFalse(TpsCommand.available(bad));
            assertTrue(Double.isNaN(TpsCommand.cappedTps(bad, 20.0)));
        }
        assertEquals("12.0ms", TpsCommand.ms(12.0));
        assertEquals("1.50ms", TpsCommand.ms(1.5));
        assertEquals("0.050ms", TpsCommand.ms(0.05));
        assertEquals("0.50", TpsCommand.value(0.5, 2));
        assertEquals(0.0, TpsCommand.cappedTps(-3.0, 20.0));
    }

    @Test
    void msptWithNothingMeasuredShowsPlaceholdersNotNaN() {
        final String text = plain(MsptCommand.render(() -> nothingMeasured()));

        assertNoLeak(text);
        assertTrue(text.contains("Worst average unavailable"), text);
        assertTrue(text.contains("Estimated p95 unavailable / p99 unavailable (approximate)"), text);
        assertTrue(text.contains("Exact recent max unavailable"), text);
        assertTrue(text.contains("Freshness: UNAVAILABLE"), text);
    }

    @Test
    void msptWithAZeroOrNegativeTargetHasNoBudgetPercentage() {
        for (final double target : new double[] {0.0, -1.0, Double.NaN}) {
            final String text = plain(MsptCommand.render(() -> healthy(20.0, 10.0).target(target)));
            assertNoLeak(text);
            assertTrue(text.contains("Worst average unavailable"), "target " + target + ": " + text);
            assertFalse(text.contains("% of"), text);
        }
    }

    @Test
    void msptShowsBudgetPercentageAndClampsAnOverrunTo100() {
        final String half = plain(MsptCommand.render(() -> healthy(20.0, 25.0)));
        assertTrue(half.contains("Worst average 25.0ms"), half);
        assertTrue(half.contains("(50% of 50.0ms budget)"), half);
        assertTrue(half.contains("Estimated p95 30.0ms / p99 37.5ms (approximate)"), half);
        assertTrue(half.contains("Exact recent max 50.0ms"), half);

        final String over = plain(MsptCommand.render(() -> healthy(20.0, 500.0)));
        assertTrue(over.contains("(100% of 50.0ms budget)"), over);
        assertNoLeak(over);
    }

    @Test
    void msptReadsOnlyTheFiveSecondWindowForTheHeadline() {
        final var snap = healthy(20.0, 10.0)
            .window(MetricWindow.FIVE_SECONDS, window("worstAverageMspt", 40.0));
        final String text = plain(MsptCommand.render(() -> snap));
        assertTrue(text.contains("Worst average 40.0ms"), text);
        assertTrue(text.contains("Estimated p95 unavailable"), "p95 was not set on the 5s window: " + text);
    }
}
