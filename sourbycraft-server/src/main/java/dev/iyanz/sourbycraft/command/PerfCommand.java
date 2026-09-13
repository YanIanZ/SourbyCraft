package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.perf.MetricsRuntime;
import dev.iyanz.sourbycraft.util.ContainerMemory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/** Read-only diagnostics: one immutable generation per invocation, no world/OS queries. */
public final class PerfCommand extends Command {
    private static final List<String> VIEWS = List.of("tick", "cpu", "memory", "gc", "region", "health");

    public PerfCommand(final String name) {
        super(name);
        this.description = "SourbyCraft performance diagnostics";
        this.usageMessage = "/" + name + (name.equals("ram") ? "" : " [tick|cpu|memory|gc|region|health]");
        this.setPermission("sourbycraft.command." + name);
    }

    @Override
    public boolean execute(final CommandSender sender, final String alias, final String[] args) {
        if (!this.testPermission(sender)) return true;
        final String view = getName().equals("ram") ? "memory"
            : args.length == 0 ? "overview" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > (getName().equals("ram") ? 0 : 1)
            || !(view.equals("overview") || VIEWS.contains(view))) {
            sender.sendMessage(Component.text(this.usageMessage, SourbyCraftColors.DIM));
            return true;
        }
        render(MetricsRuntime.provider(), view).forEach(sender::sendMessage);
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!testPermissionSilent(sender) || getName().equals("ram") || args.length != 1) return List.of();
        final String prefix = args[0].toLowerCase(Locale.ROOT);
        return VIEWS.stream().filter(view -> view.startsWith(prefix)).toList();
    }

    public static List<Component> render(final SourbyMetrics metrics, final String view) {
        final PerformanceSnapshot snapshot = metrics.snapshot();
        final var runtime = snapshot.runtime();
        final var tick = snapshot.window(MetricWindow.FIVE_SECONDS);
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.text("SourbyCraft " + (view.equals("memory") ? "Memory" : "Performance"),
            SourbyCraftColors.HEADER));
        if (view.equals("overview") || view.equals("tick")) {
            add(lines, "Target TPS", TpsCommand.value(snapshot.targetTps(), 2));
            add(lines, "Worst region TPS", TpsCommand.value(TpsCommand.cappedTps(tick.worstTps(), snapshot.targetTps()), 2));
            add(lines, "Worst average MSPT", TpsCommand.ms(tick.worstAverageMspt()));
            add(lines, "Estimated p95 / p99", TpsCommand.ms(tick.estimatedP95Mspt()) + " / "
                + TpsCommand.ms(tick.estimatedP99Mspt()) + " (approximate)");
            add(lines, "Recent maximum", TpsCommand.ms(tick.maximumMspt()));
        }
        if (view.equals("overview") || view.equals("cpu")) {
            add(lines, "Process CPU / system CPU", percent(runtime.processCpuPercent()) + " / "
                + percent(runtime.systemCpuPercent()));
            add(lines, "Available processors / live platform threads", count(runtime.availableProcessors())
                + " / " + count(runtime.liveThreadCount()));
            add(lines, "Uptime", runtime.uptimeMillis() < 0 ? "unavailable" : runtime.uptimeMillis() / 1000 + "s");
        }
        if (view.equals("overview") || view.equals("memory")) {
            add(lines, "Heap used / committed / maximum", bytes(runtime.heapUsedBytes()) + " / "
                + bytes(runtime.heapCommittedBytes()) + " / " + bytes(runtime.heapMaxBytes()));
            add(lines, "Non-heap", bytes(runtime.nonHeapUsedBytes()));
            add(lines, "Process RSS", bytes(runtime.processRssBytes()));
            add(lines, "Container memory usage", percent(runtime.rssPercent()));
        }
        if (view.equals("overview") || view.equals("gc") || view.equals("memory")) {
            add(lines, "GC collections / cumulative collection time", count(runtime.gcCollectionCount()) + " / "
                + (runtime.gcCollectionTimeMillis() < 0 ? "unavailable" : runtime.gcCollectionTimeMillis() + "ms"));
            add(lines, "GC collections/min", TpsCommand.value(runtime.gcCollectionsPerMinute(), 1));
            add(lines, "Pause distribution / allocation samples", "use JFR or /spark profiler start");
            add(lines, "GC scope", "MXBean collection time is not stop-the-world pause time");
        }
        if (view.equals("overview") || view.equals("region")) {
            final boolean known = snapshot.freshness().state() != MetricState.UNAVAILABLE;
            add(lines, "Active regions / retained generations", known
                ? snapshot.activeRegionCount() + " / " + snapshot.retainedGenerationCount() : "unavailable");
            add(lines, "Worst / median / aggregate average MSPT", TpsCommand.ms(tick.worstAverageMspt()) + " / "
                + TpsCommand.ms(tick.medianAverageMspt()) + " / " + TpsCommand.ms(tick.aggregateAverageMspt()));
            add(lines, "Region coordinates / queues", "not instrumented in this snapshot");
        }
        if (view.equals("overview") || view.equals("health")) {
            add(lines, "Tick health", health(snapshot));
            add(lines, "Diagnostics", "settings are operator-owned; /spark profiler start for samples");
        }
        lines.add(TpsCommand.freshness(snapshot.freshness()));
        return List.copyOf(lines);
    }

    public static String health(final PerformanceSnapshot snapshot) {
        if (snapshot.freshness().state() != MetricState.AVAILABLE) return snapshot.freshness().state().name();
        final var tick = snapshot.window(MetricWindow.FIVE_SECONDS);
        final double target = snapshot.targetTps();
        final double mspt = tick.worstAverageMspt();
        final double tps = tick.worstTps();
        if (!Double.isFinite(target) || target <= 0 || !Double.isFinite(tps) || !Double.isFinite(mspt)) {
            return "UNAVAILABLE";
        }
        final double budget = 1000.0 / target;
        if (tps < target * 0.85 || mspt >= budget) return "CRITICAL";
        if (tps < target * 0.95 || mspt >= budget * 0.8) return "WARNING";
        if (mspt >= budget * 0.5) return "GOOD";
        return "EXCELLENT";
    }

    private static void add(final List<Component> lines, final String label, final String value) {
        lines.add(Component.text("  " + label + ": ", SourbyCraftColors.LABEL)
            .append(Component.text(value, SourbyCraftColors.VALUE)));
    }

    private static String bytes(final long value) { return value < 0 ? "unavailable" : ContainerMemory.fmt(value); }
    private static String count(final long value) { return value < 0 ? "unavailable" : Long.toString(value); }
    private static String percent(final double value) {
        return Double.isFinite(value) ? TpsCommand.value(value, 1) + "%" : "unavailable";
    }
}
