package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.execution.LanePortions;
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
    static final List<String> VIEWS =
        List.of("tick", "cpu", "memory", "gc", "lanes", "async", "network", "governor", "region", "health");

    public PerfCommand(final String name) {
        super(name);
        this.description = "SourbyCraft performance diagnostics";
        this.usageMessage = "/" + name + (name.equals("ram") ? "" : " [" + String.join("|", VIEWS) + "]");
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
        if (view.equals("lanes")) {
            renderLanes(lines, MetricsRuntime.lanePortions());
        }
        if (view.equals("async")) {
            renderAsyncPath(lines);
        }
        if (view.equals("network")) {
            renderNetwork(lines);
        }
        if (view.equals("governor")) {
            renderGovernor(lines);
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

    /**
     * Where the machine's time went, by execution lane.
     *
     * <p>Two facts are reported rather than one number, because they answer different questions.
     * A lane holding most of what was used is the thing to fix; a machine that is actually spent
     * is the only state where moving threads between lanes is the right kind of move. A server
     * can be the first without being the second, and that is exactly when adding threads to the
     * busy lane looks obvious and measures worse.</p>
     */
    private static void renderLanes(final List<Component> lines, final LanePortions.Report report) {
        if (!report.available()) {
            add(lines, "Lane load", "unavailable — " + report.reason());
            return;
        }
        for (final LanePortions.Portion portion : report.portions()) {
            if (portion.cores() < 0.005) {
                continue;                 // Below the noise the sampler can distinguish.
            }
            add(lines, "  " + portion.lane().display(),
                TpsCommand.value(portion.cores(), 2) + " cores ("
                    + percent(portion.shareOfUsed() * 100.0) + " of used)");
        }
        add(lines, "Used / idle / cores", TpsCommand.value(report.usedCores(), 2) + " / "
            + TpsCommand.value(report.idleCores(), 2) + " / " + report.machineCores());
        add(lines, "Shape", (report.isConcentrated()
                ? "concentrated in " + report.dominant().display() : "spread across lanes")
            + (report.isSaturated() ? ", machine saturated" : ", machine has headroom"));
    }

    /**
     * What the async-path pool has been doing, when it is on.
     *
     * <p>Saturation is visible as refused work. Periodic path recomputes are deliberately dropped
     * when the queue is full so the mob keeps its current path rather than forcing CPU-bound A*
     * back onto the region thread. The inline counter is retained only for compatibility with
     * older telemetry and should stay zero.</p>
     */
    private static void renderAsyncPath(final List<Component> lines) {
        if (!dev.iyanz.sourbycraft.perf.AsyncPathProcessor.isEnabled()) {
            add(lines, "Async pathfinding", "disabled (aurora.entity.async-pathfinding)");
            return;
        }
        final var stats = dev.iyanz.sourbycraft.perf.AsyncPathProcessor.stats();
        add(lines, "Solves admitted / outstanding",
            count(stats.admitted()) + " / " + count(stats.outstanding()));
        add(lines, "Solve time mean / slowest", Double.isNaN(stats.meanMillis()) ? "no solves yet"
            : TpsCommand.ms(stats.meanMillis()) + " / " + TpsCommand.ms(stats.slowestMillis()));
        // Queue wait separates "the A* is expensive" from "the pool is too small for the arrival
        // rate". A short solve behind a long wait still reaches the entity late.
        add(lines, "Queue wait mean / slowest", Double.isNaN(stats.meanWaitMillis())
            ? "no solves yet"
            : TpsCommand.ms(stats.meanWaitMillis()) + " / " + TpsCommand.ms(stats.slowestWaitMillis()));
        add(lines, "Queue depth / active workers / pool", stats.poolSize() < 0 ? "pool not running"
            : count(stats.queueDepth()) + " / " + count(stats.activeWorkers())
                + " / " + count(stats.poolSize()));
        add(lines, "Legacy caller-run solves", count(stats.inline())
            + (stats.inline() > 0 ? "  — unexpected on this build" : ""));
        add(lines, "Refused (saturation / shutdown)", count(stats.refused()));
    }

    /** Wire and packet rates from the network counters; totals since start beside them. */
    private static void renderNetwork(final List<Component> lines) {
        final var counters = dev.iyanz.sourbycraft.perf.NetworkCounters.GLOBAL;
        final var rates = counters.rates();
        final var totals = counters.totals(System.nanoTime());
        if (!rates.available()) {
            add(lines, "Network rates", "unavailable until two collector samples exist");
        } else {
            add(lines, "Bytes in / out per second", bytes((long)rates.bytesInPerSecond()) + " / "
                + bytes((long)rates.bytesOutPerSecond()) + " (wire, after compression)");
            add(lines, "Packets in / out per second", TpsCommand.value(rates.packetsInPerSecond(), 1) + " / "
                + TpsCommand.value(rates.packetsOutPerSecond(), 1));
        }
        add(lines, "Total bytes in / out", bytes(totals.bytesIn()) + " / " + bytes(totals.bytesOut()));
        add(lines, "Total packets in / out", count(totals.packetsIn()) + " / " + count(totals.packetsOut()));
        add(lines, "Connections opened", count(totals.connections()));
    }

    /** Budgets and counters for each Resource Governor lane that has been used. */
    private static void renderGovernor(final List<Component> lines) {
        final var stats = dev.iyanz.sourbycraft.execution.ResourceGovernor.GLOBAL.stats();
        if (stats.isEmpty()) {
            add(lines, "Resource Governor", "no governed lane has been used yet");
            return;
        }
        for (final var lane : stats) {
            add(lines, lane.name() + " budget", lane.threads() + " threads, queue " + lane.queueCapacity());
            add(lines, lane.name() + " active / queued / peak queued",
                lane.active() + " / " + lane.queued() + " / " + lane.peakQueued());
            add(lines, lane.name() + " submitted / completed / failed / rejected", lane.submitted() + " / "
                + lane.completed() + " / " + lane.failed() + " / " + lane.rejected());
        }
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
