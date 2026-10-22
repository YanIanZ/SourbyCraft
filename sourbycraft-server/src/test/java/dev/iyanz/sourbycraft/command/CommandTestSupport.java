package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.api.metrics.Freshness;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;

/**
 * Shared fakes for the command tests: performance snapshots whose every unset figure is the
 * "unavailable" sentinel the real collector uses (NaN for doubles, -1 for longs and ints), and a
 * door to the process-wide {@code Bukkit.server} for the commands that read it.
 */
final class CommandTestSupport {

    private CommandTestSupport() {}

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Installs {@code server} as {@code Bukkit.server} for {@code action}, then restores the previous one. */
    static void withServer(final Server server, final ThrowingRunnable action) throws Exception {
        final Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        final Object previous = field.get(null);
        field.set(null, server);
        try {
            action.run();
        } finally {
            field.set(null, previous);
        }
    }

    /** {@code Command.testPermission} would otherwise ask {@code Bukkit} for the default message. */
    static <T extends Command> T quietDenial(final T command) {
        command.permissionMessage(Component.text("no permission"));
        return command;
    }

    private static Object sentinel(final Class<?> type) {
        if (type == double.class) return Double.NaN;
        if (type == long.class) return -1L;
        if (type == int.class) return -1;
        if (type == boolean.class) return false;
        if (type == String.class) return "";
        return null;
    }

    /** A proxy of {@code type} answering from {@code values} by method name, else the unavailable sentinel. */
    private static <T> T proxy(final Class<T> type, final Map<String, Object> values) {
        final InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return type.getSimpleName() + values;
                default: break;
            }
            return values.containsKey(method.getName()) ? values.get(method.getName()) : sentinel(method.getReturnType());
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    /** A window with the given figures ({@code name, value, name, value, ...}); every other figure is unavailable. */
    static WindowMetrics window(final Object... nameValuePairs) {
        return proxy(WindowMetrics.class, pairs(nameValuePairs));
    }

    /** Runtime metrics with the given figures; every other figure is unavailable. */
    static RuntimeMetrics runtime(final Object... nameValuePairs) {
        return proxy(RuntimeMetrics.class, pairs(nameValuePairs));
    }

    static Freshness freshness(final MetricState state, final long ageMillis, final String diagnostic) {
        return proxy(Freshness.class, Map.of("state", state, "ageMillis", ageMillis, "diagnostic", diagnostic));
    }

    private static Map<String, Object> pairs(final Object[] nameValuePairs) {
        final Map<String, Object> values = new java.util.HashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) values.put((String) nameValuePairs[i], nameValuePairs[i + 1]);
        return values;
    }

    /** A snapshot under construction. Unset windows and runtime are entirely unavailable. */
    static final class Snap implements PerformanceSnapshot {
        double targetTps = Double.NaN;
        int activeRegions = 0;
        int retained = 0;
        Freshness freshness = CommandTestSupport.freshness(MetricState.UNAVAILABLE, 0L, "");
        RuntimeMetrics runtime = CommandTestSupport.runtime();
        final Map<MetricWindow, WindowMetrics> windows = new EnumMap<>(MetricWindow.class);
        final Map<MetricWindow, WindowMetrics> globals = new EnumMap<>(MetricWindow.class);

        Snap target(final double tps) { this.targetTps = tps; return this; }
        Snap regions(final int active, final int retained) { this.activeRegions = active; this.retained = retained; return this; }
        Snap fresh(final Freshness freshness) { this.freshness = freshness; return this; }
        Snap runtime(final RuntimeMetrics runtime) { this.runtime = runtime; return this; }
        Snap window(final MetricWindow window, final WindowMetrics metrics) { this.windows.put(window, metrics); return this; }
        Snap global(final MetricWindow window, final WindowMetrics metrics) { this.globals.put(window, metrics); return this; }

        @Override public long sequence() { return 1L; }
        @Override public long sampledAtEpochMillis() { return 0L; }
        @Override public double targetTps() { return this.targetTps; }
        @Override public int activeRegionCount() { return this.activeRegions; }
        @Override public int retainedGenerationCount() { return this.retained; }
        @Override public Freshness freshness() { return this.freshness; }
        @Override public WindowMetrics window(final MetricWindow window) { return this.windows.getOrDefault(window, CommandTestSupport.window()); }
        @Override public WindowMetrics globalWindow(final MetricWindow window) { return this.globals.getOrDefault(window, CommandTestSupport.window()); }
        @Override public RuntimeMetrics runtime() { return this.runtime; }
    }

    /** Everything unavailable: what the provider publishes before the first collection. */
    static Snap nothingMeasured() {
        return new Snap();
    }

    /** A healthy 20 TPS server: every window at {@code tps}, {@code mspt} average, half the heap used. */
    static Snap healthy(final double tps, final double mspt) {
        final WindowMetrics w = window("worstTps", tps, "medianTps", tps, "aggregateTps", tps,
            "worstAverageMspt", mspt, "medianAverageMspt", mspt, "aggregateAverageMspt", mspt,
            "estimatedP95Mspt", mspt * 1.2, "estimatedP99Mspt", mspt * 1.5, "maximumMspt", mspt * 2,
            "busiestUtilisation", 0.4, "averageUtilisation", 0.2, "totalMissingCpuMs", 0.0);
        final Snap snap = new Snap().target(20.0).regions(3, 4)
            .fresh(freshness(MetricState.AVAILABLE, 0L, ""))
            .runtime(runtime("heapUsedBytes", 512L << 20, "heapMaxBytes", 1024L << 20,
                "gcTimePercent", 1.0, "gcCollectionsPerMinute", 2.0, "averageGcPauseMs", 5.0, "rssPercent", 40.0));
        for (final MetricWindow window : MetricWindow.values()) {
            snap.window(window, w);
            snap.global(window, w);
        }
        return snap;
    }

    /** Joins rendered components into one plain-text block. */
    static String plain(final List<Component> lines) {
        final var serializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        final StringBuilder out = new StringBuilder();
        for (final Component line : lines) out.append(serializer.serialize(line)).append('\n');
        return out.toString();
    }

    /** Joins everything a probe received into one plain-text block. */
    static String plainReceived(final List<ReplyProbe.Received> received) {
        final StringBuilder out = new StringBuilder();
        for (final ReplyProbe.Received r : received) out.append(r.text()).append('\n');
        return out.toString();
    }
}
