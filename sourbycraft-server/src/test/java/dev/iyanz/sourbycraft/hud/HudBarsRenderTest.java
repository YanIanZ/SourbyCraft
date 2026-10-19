package dev.iyanz.sourbycraft.hud;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.api.metrics.Freshness;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 / TEST-COVERAGE-GAPS #6: the boss-bar renderers of {@link HudBars}.
 *
 * <p>The gap list asked for "width clamp" and "unicode vs ASCII style selection"; the current
 * HudBars has neither (a bar is an adventure {@link BossBar} with a float progress and a colour),
 * so this pins what the code does: colour tiers relative to the dynamic target, the MSPT tier
 * against the tick budget, progress clamped to [0, 1], unavailable inputs (NaN, target &lt;= 0,
 * UNAVAILABLE freshness, missing heap/CPU) and viewer eviction on quit. Scheduling (show/hide on
 * the player's region, the global updater task) needs a running server and is not covered.</p>
 *
 * <p>{@code perf/MetricsConsumerTest} already covers single-snapshot reads, the cap at the target
 * and WARMING/UNAVAILABLE text; those cases are not repeated here.</p>
 */
class HudBarsRenderTest {

    @AfterEach
    void resetViewers() {
        HudBars.close();
    }

    @Test
    void tpsColourTiersFollowTheRatioToTheDynamicTarget() {
        assertTier(18.0, BossBar.Color.GREEN, SourbyCraftColors.HEALTHY);    // 0.90 exactly
        assertTier(17.9, BossBar.Color.YELLOW, SourbyCraftColors.PRESSURE);  // just below 0.90
        assertTier(15.0, BossBar.Color.YELLOW, SourbyCraftColors.PRESSURE);  // 0.75 exactly
        assertTier(14.9, BossBar.Color.RED, SourbyCraftColors.CRITICAL);     // just below 0.75
    }

    @Test
    void msptColourScalesWithTheTickBudget() {
        // target 20 TPS -> 50 ms budget: healthy below 40 ms, pressure below 60 ms, critical from 60 ms.
        assertEquals(SourbyCraftColors.HEALTHY, msptColour(20.0, 39.9));
        assertEquals(SourbyCraftColors.PRESSURE, msptColour(20.0, 40.0));
        assertEquals(SourbyCraftColors.PRESSURE, msptColour(20.0, 59.9));
        assertEquals(SourbyCraftColors.CRITICAL, msptColour(20.0, 60.0));
        // target 10 TPS -> 100 ms budget: the same 60 ms is healthy.
        assertEquals(SourbyCraftColors.HEALTHY, msptColour(10.0, 60.0));
    }

    @Test
    void unavailableMsptRendersAsUnavailableNotZero() {
        final HudBars.TpsDisplay display = HudBars.renderTps(() -> snapshot(MetricState.AVAILABLE, 20.0, 20.0, Double.NaN));
        final String text = plain(display.name());

        assertTrue(text.contains("MSPT unavailable"), text);
        assertEquals(SourbyCraftColors.DIM, colourOf(display.name(), "unavailable"));
        assertEquals(1.0F, display.progress(), 1.0E-6F);
    }

    @Test
    void progressIsClampedToZeroForNegativeTps() {
        final HudBars.TpsDisplay display = HudBars.renderTps(() -> snapshot(MetricState.AVAILABLE, 20.0, -5.0, 10.0));

        assertEquals(0.0F, display.progress(), 0.0F);
        assertEquals(BossBar.Color.RED, display.color());
        assertTrue(plain(display.name()).contains("TPS 0.00/20.00"));
    }

    @Test
    void progressNeverExceedsOneWhenTpsIsAboveTarget() {
        final HudBars.TpsDisplay display = HudBars.renderTps(() -> snapshot(MetricState.AVAILABLE, 20.0, 1_000.0, 1.0));

        assertEquals(1.0F, display.progress(), 0.0F);
        assertEquals(BossBar.Color.GREEN, display.color());
    }

    @Test
    void nonPositiveOrNanTargetRendersStateInsteadOfDividingByZero() {
        for (final double target : new double[] {0.0, -20.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            final HudBars.TpsDisplay display = HudBars.renderTps(() -> snapshot(MetricState.AVAILABLE, target, 20.0, 10.0));

            assertEquals("TPS AVAILABLE", plain(display.name()), "target " + target);
            assertEquals(0.0F, display.progress(), 0.0F, "target " + target);
            assertEquals(BossBar.Color.YELLOW, display.color(), "target " + target);
        }
    }

    @Test
    void unavailableFreshnessWinsOverFiniteValues() {
        final HudBars.TpsDisplay display = HudBars.renderTps(() -> snapshot(MetricState.UNAVAILABLE, 20.0, 20.0, 10.0));

        assertEquals("TPS UNAVAILABLE", plain(display.name()));
        assertEquals(0.0F, display.progress(), 0.0F);
        assertEquals(BossBar.Color.YELLOW, display.color());
    }

    @Test
    void staleSnapshotIsLabelledAndAvailableIsNot() {
        final String stale = plain(HudBars.renderTps(() -> snapshot(MetricState.STALE, 20.0, 20.0, 10.0)).name());
        final String fresh = plain(HudBars.renderTps(() -> snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0)).name());

        assertTrue(stale.endsWith("STALE"), stale);
        assertFalse(fresh.contains("STALE"), fresh);
    }

    @Test
    void perfBarAddsHeapAndCpuPercentagesToTheTpsLine() {
        final HudBars.TpsDisplay display = HudBars.renderPerf(() -> snapshot(MetricState.AVAILABLE, 20.0, 19.0, 10.0,
            50L, 200L, 12.4));
        final HudBars.TpsDisplay tps = HudBars.renderTps(() -> snapshot(MetricState.AVAILABLE, 20.0, 19.0, 10.0,
            50L, 200L, 12.4));
        final String text = plain(display.name());

        assertTrue(text.startsWith(plain(tps.name())), text);
        assertTrue(text.endsWith("RAM 25%  CPU 12%"), text);
        assertEquals(tps.progress(), display.progress(), 0.0F);
        assertEquals(tps.color(), display.color());
    }

    @Test
    void perfBarReportsUnavailableHeapAndCpuInsteadOfZero() {
        assertTrue(plain(HudBars.renderPerf(() -> snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0,
            50L, 0L, Double.NaN)).name()).endsWith("RAM unavailable  CPU unavailable"));
        assertTrue(plain(HudBars.renderPerf(() -> snapshot(MetricState.AVAILABLE, 20.0, 20.0, 10.0,
            -1L, 200L, Double.POSITIVE_INFINITY)).name()).endsWith("RAM unavailable  CPU unavailable"));
    }

    @Test
    void quitOfAPlayerWithoutBarsHidesNothing() {
        final RecordingPlayer player = new RecordingPlayer(new UUID(1L, 1L));

        HudBars.onQuit(player.proxy());

        assertTrue(player.hidden.isEmpty());
    }

    @Test
    void quitEvictsTheViewerFromEveryBar() throws ReflectiveOperationException {
        final RecordingPlayer player = new RecordingPlayer(new UUID(1L, 2L));
        final UUID id = player.id;
        viewers("TPS_VIEWERS").add(id);
        viewers("RAM_VIEWERS").add(id);
        viewers("PERF_VIEWERS").add(id);
        anyMap().put(id, Boolean.TRUE);

        HudBars.onQuit(player.proxy());

        assertFalse(viewers("TPS_VIEWERS").contains(id));
        assertFalse(viewers("RAM_VIEWERS").contains(id));
        assertFalse(viewers("PERF_VIEWERS").contains(id));
        assertFalse(anyMap().containsKey(id));
        assertEquals(List.of(bar("PERF_BAR"), bar("TPS_BAR"), bar("RAM_BAR")), player.hidden);
    }

    // ------------------------------------------------------------------------------------ helpers

    private static void assertTier(final double tps, final BossBar.Color bar, final TextColor text) {
        final HudBars.TpsDisplay display = HudBars.renderTps(() -> snapshot(MetricState.AVAILABLE, 20.0, tps, 10.0));
        assertEquals(bar, display.color(), "bar colour at " + tps);
        assertEquals(text, colourOf(display.name(), String.format(java.util.Locale.ROOT, "%.2f/20.00", tps)),
            "text colour at " + tps);
        assertEquals((float) (tps / 20.0), display.progress(), 1.0E-6F);
    }

    private static TextColor msptColour(final double target, final double mspt) {
        final Component name = HudBars.renderTps(() -> snapshot(MetricState.AVAILABLE, target, target, mspt)).name();
        return colourOf(name, dev.iyanz.sourbycraft.command.TpsCommand.ms(mspt));
    }

    private static PerformanceSnapshot snapshot(final MetricState state, final double target,
                                                final double worstTps, final double mspt) {
        return snapshot(state, target, worstTps, mspt, 25L, 100L, Double.NaN);
    }

    private static PerformanceSnapshot snapshot(final MetricState state, final double target,
                                                final double worstTps, final double mspt,
                                                final long heapUsed, final long heapMax, final double cpu) {
        final Map<String, Object> window = new HashMap<>();
        window.put("worstTps", worstTps);
        window.put("medianTps", worstTps);
        window.put("aggregateTps", worstTps);
        window.put("worstAverageMspt", mspt);
        final WindowMetrics windowMetrics = proxy(WindowMetrics.class, window);

        final Map<String, Object> runtime = new HashMap<>();
        runtime.put("heapUsedBytes", heapUsed);
        runtime.put("heapMaxBytes", heapMax);
        runtime.put("processCpuPercent", cpu);
        final RuntimeMetrics runtimeMetrics = proxy(RuntimeMetrics.class, runtime);

        final Map<String, Object> freshness = new HashMap<>();
        freshness.put("state", state);
        freshness.put("diagnostic", "test");
        final Freshness freshnessValue = proxy(Freshness.class, freshness);

        final Map<String, Object> values = new HashMap<>();
        values.put("targetTps", target);
        values.put("activeRegionCount", 1);
        values.put("freshness", freshnessValue);
        values.put("window", windowMetrics);
        values.put("globalWindow", windowMetrics);
        values.put("runtime", runtimeMetrics);
        return proxy(PerformanceSnapshot.class, values);
    }

    private static <T> T proxy(final Class<T> type, final Map<String, Object> values) {
        final InvocationHandler handler = (proxy, method, args) -> {
            if (values.containsKey(method.getName())) return values.get(method.getName());
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            return zero(method.getReturnType());
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object zero(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        return (short) 0;
    }

    private static String plain(final Component component) {
        final StringBuilder out = new StringBuilder();
        appendPlain(component, out);
        return out.toString();
    }

    private static void appendPlain(final Component component, final StringBuilder out) {
        if (component instanceof TextComponent text) out.append(text.content());
        component.children().forEach(child -> appendPlain(child, out));
    }

    /** Colour of the first text node whose content equals {@code content}. */
    private static TextColor colourOf(final Component component, final String content) {
        if (component instanceof TextComponent text && text.content().equals(content)) return text.color();
        for (final Component child : component.children()) {
            final TextColor found = colourOf(child, content);
            if (found != null) return found;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> viewers(final String name) throws ReflectiveOperationException {
        return (Set<UUID>) field(name).get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Boolean> anyMap() throws ReflectiveOperationException {
        return (Map<UUID, Boolean>) field("ANY").get(null);
    }

    private static BossBar bar(final String name) throws ReflectiveOperationException {
        return (BossBar) field(name).get(null);
    }

    private static Field field(final String name) throws NoSuchFieldException {
        final Field field = HudBars.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** A {@link Player} proxy that records hidden boss bars and fails on anything else stateful. */
    private static final class RecordingPlayer {
        final UUID id;
        final List<BossBar> hidden = new ArrayList<>();

        RecordingPlayer(final UUID id) {
            this.id = id;
        }

        Player proxy() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> handle(method, args));
        }

        private Object handle(final Method method, final Object[] args) {
            switch (method.getName()) {
                case "getUniqueId": return id;
                case "hideBossBar": hidden.add((BossBar) args[0]); return proxyReturn(method);
                case "hashCode": return id.hashCode();
                case "equals": return args[0] == this;
                case "toString": return "RecordingPlayer[" + id + "]";
                default: throw new AssertionError("unexpected Player call: " + method.getName());
            }
        }

        private static Object proxyReturn(final Method method) {
            return method.getReturnType() == void.class ? null : zero(method.getReturnType());
        }
    }
}
