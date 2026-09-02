package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.Freshness;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.command.MsptCommand;
import dev.iyanz.sourbycraft.command.SysCommand;
import dev.iyanz.sourbycraft.command.TpsCommand;
import dev.iyanz.sourbycraft.hud.HudBars;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsConsumerTest {

    @Test
    void eachMetricsRendererReadsExactlyOneImmutableSnapshot() {
        assertSingleRead(TpsCommand::render);
        assertSingleRead(MsptCommand::render);
        assertSingleRead(SysCommand::renderPerformance);
        assertSingleRead(HudBars::renderTps);
    }

    @Test
    void tpsRendersActiveDistributionsGlobalRegionAndFreshness() {
        final PerformanceSnapshot snapshot = snapshot(MetricState.STALE, 10.0,
            window(8.0, 9.0, 9.5, 32.0, 44.0, 48.0, 52.0, true),
            window(7.0, 7.0, 7.0, 60.0, 60.0, 60.0, 60.0, false));

        final String rendered = plain(TpsCommand.render(() -> snapshot));

        assertTrue(rendered.contains("Worst 8.00"));
        assertTrue(rendered.contains("Median 9.00"));
        assertTrue(rendered.contains("Aggregate 9.50"));
        assertTrue(rendered.contains("Worst average MSPT: 32.0ms"));
        assertTrue(rendered.contains("Active regions: 3"));
        assertTrue(rendered.contains("Global: TPS 7.00 / MSPT 60.0ms"));
        assertTrue(rendered.contains("STALE (2500ms old): delayed"));
        assertTrue(rendered.contains("Target 10.00 TPS"));
    }

    @Test
    void msptLabelsEstimatedTailsAndExactRecentMaximum() {
        final PerformanceSnapshot snapshot = snapshot(MetricState.AVAILABLE, 20.0,
            window(18.0, 19.0, 19.5, 12.5, 22.0, 31.0, 47.0, false),
            ImmutableWindowMetrics.EMPTY);

        final String rendered = plain(MsptCommand.render(() -> snapshot));

        assertTrue(rendered.contains("Worst average 12.5ms"));
        assertTrue(rendered.contains("Estimated p95 31.0ms / p99 47.0ms"));
        assertTrue(rendered.contains("Exact recent max 22.0ms"));
        assertTrue(rendered.contains("approximate"));
        assertTrue(rendered.contains("AVAILABLE"));
    }

    @Test
    void unavailableStatesAndNanNeverRenderAsHealthyZero() {
        for (final MetricState state : List.of(MetricState.WARMING, MetricState.UNAVAILABLE)) {
            final PerformanceSnapshot snapshot = snapshot(state, Double.NaN,
                ImmutableWindowMetrics.EMPTY, ImmutableWindowMetrics.EMPTY);

            final String tps = plain(TpsCommand.render(() -> snapshot));
            final String mspt = plain(MsptCommand.render(() -> snapshot));
            final String sys = plain(SysCommand.renderPerformance(() -> snapshot));
            final String hud = plain(HudBars.renderTps(() -> snapshot).name());

            assertTrue(tps.contains(state.name()));
            assertTrue(mspt.contains(state.name()));
            assertTrue(sys.contains(state.name()));
            assertTrue(tps.contains("unavailable"));
            assertTrue(tps.contains("Active regions: unavailable"));
            assertTrue(mspt.contains("unavailable"));
            assertTrue(sys.contains("Health unavailable"));
            assertFalse(sys.contains("100/100"));
            assertTrue(hud.contains(state.name()));
            assertFalse(hud.contains("0.00"));
        }
    }

    @Test
    void hudUsesWorstActiveValuesAndDynamicTargetProgress() {
        final PerformanceSnapshot snapshot = snapshot(MetricState.AVAILABLE, 10.0,
            window(8.0, 9.0, 9.5, 12.0, 20.0, 30.0, 40.0, false),
            ImmutableWindowMetrics.EMPTY);

        final HudBars.TpsDisplay display = HudBars.renderTps(() -> snapshot);
        final String rendered = plain(display.name());

        assertEquals(0.8F, display.progress(), 1.0E-6F);
        assertTrue(rendered.contains("TPS 8.00/10.00"));
        assertTrue(rendered.contains("MSPT 12.0ms"));
    }

    @Test
    void sysRendersCachedRegionRuntimeAndGcValues() {
        final PerformanceSnapshot snapshot = snapshot(MetricState.AVAILABLE, 20.0,
            window(18.0, 19.0, 19.5, 12.0, 35.0, 25.0, 30.0, false),
            ImmutableWindowMetrics.EMPTY);

        final String rendered = plain(SysCommand.renderPerformance(() -> snapshot));

        assertTrue(rendered.contains("3 active"));
        assertTrue(rendered.contains("busiest 75% / avg 50%"));
        assertTrue(rendered.contains("5.00ms/tick owed"));
        assertTrue(rendered.contains("GC: 2.0% time"));
        assertTrue(rendered.contains("Heap: 25.0%"));
        assertTrue(rendered.contains("RSS: 40.0%"));
    }

    private static void assertSingleRead(final Renderer renderer) {
        final AtomicInteger reads = new AtomicInteger();
        final SourbyMetrics metrics = () -> snapshotForSequence(reads.incrementAndGet());

        final Object rendered = renderer.render(metrics);

        assertEquals(1, reads.get());
        assertTrue(plain(rendered instanceof HudBars.TpsDisplay display ? display.name() : rendered)
            .contains("8.00"));
    }

    private static PerformanceSnapshot snapshotForSequence(final int sequence) {
        final double marker = 7.0 + sequence;
        return snapshot(MetricState.AVAILABLE, 10.0,
            window(marker, marker, marker, marker, marker, marker, marker, false),
            ImmutableWindowMetrics.EMPTY);
    }

    private static PerformanceSnapshot snapshot(final MetricState state, final double targetTps,
                                                final ImmutableWindowMetrics active,
                                                final ImmutableWindowMetrics global) {
        final Freshness freshness = new ImmutableFreshness(state, 2_500L, 400L, 30L, "delayed");
        return new ImmutablePerformanceSnapshot(1L, 1_000L, targetTps, 3, 4, freshness,
            active, active, active, active, active,
            new ImmutableRuntimeMetrics(25L, 100L, 40.0, 2.0, 3.0, 4.0),
            new ImmutableGlobalMetrics(global, global, global, global, global));
    }

    private static ImmutableWindowMetrics window(final double worstTps, final double medianTps,
                                                  final double aggregateTps, final double worstAverageMspt,
                                                  final double maximumMspt, final double p95,
                                                  final double p99, final boolean approximate) {
        return new ImmutableWindowMetrics(5_000L, 100L, approximate, false,
            worstTps, medianTps, aggregateTps, worstAverageMspt, worstAverageMspt,
            1.0, maximumMspt, worstAverageMspt, p95, p99, 75.0, 50.0, 5.0);
    }

    private static String plain(final Object value) {
        if (value instanceof Component component) {
            return plain(component);
        }
        if (value instanceof Iterable<?> values) {
            final StringBuilder result = new StringBuilder();
            for (final Object element : values) {
                result.append(plain(element)).append('\n');
            }
            return result.toString();
        }
        throw new AssertionError("Unsupported rendered value: " + value);
    }

    private static String plain(final Component component) {
        final StringBuilder result = new StringBuilder();
        appendPlain(component, result);
        return result.toString();
    }

    private static void appendPlain(final Component component, final StringBuilder result) {
        if (component instanceof TextComponent text) {
            result.append(text.content());
        }
        component.children().forEach(child -> appendPlain(child, result));
    }

    @FunctionalInterface
    private interface Renderer {
        Object render(SourbyMetrics metrics);
    }
}
