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
        List.of("tick", "cpu", "memory", "gc", "lanes", "async", "network", "governor", "storage", "awf", "plugins", "history", "player", "chunks", "entities", "region", "health");

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
        if (view.equals("player")) {
            if (args.length != 2) {
                sender.sendMessage(Component.text("/" + getName() + " player <name>", SourbyCraftColors.DIM));
            } else {
                renderPlayer(sender, args[1]);
            }
            return true;
        }
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
        if (view.equals("storage")) {
            renderStorage(lines);
        }
        if (view.equals("awf")) {
            renderAwf(lines);
        }
        if (view.equals("chunks") || view.equals("entities")) {
            renderPopulation(lines, view.equals("chunks"));
        }
        if (view.equals("plugins")) {
            renderPlugins(lines);
        }
        if (view.equals("history")) {
            renderHistory(lines);
        }
        if (view.equals("overview") || view.equals("region")) {
            final boolean known = snapshot.freshness().state() != MetricState.UNAVAILABLE;
            add(lines, "Active regions / retained generations", known
                ? snapshot.activeRegionCount() + " / " + snapshot.retainedGenerationCount() : "unavailable");
            add(lines, "Worst / median / aggregate average MSPT", TpsCommand.ms(tick.worstAverageMspt()) + " / "
                + TpsCommand.ms(tick.medianAverageMspt()) + " / " + TpsCommand.ms(tick.aggregateAverageMspt()));
            final dev.iyanz.sourbycraft.perf.SlowRegion slow = MetricsRuntime.slowestRegion();
            add(lines, "Slowest region (5s)", slow == null ? "none measured"
                : "world #" + slow.worldId() + " region #" + slow.regionId() + " gen " + slow.generationId()
                    + ": " + TpsCommand.ms(slow.averageMspt()) + " avg, " + TpsCommand.ms(slow.maximumMspt())
                    + " max over " + slow.samples() + " ticks");
            add(lines, "Region coordinates / queues", "not instrumented");
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

    private static final dev.iyanz.sourbycraft.execution.region.RegionPopulation POPULATION =
        new dev.iyanz.sourbycraft.execution.region.FoliaRegionBackend();

    private static void renderPopulation(final List<Component> lines, final boolean chunks) {
        final var worlds = POPULATION.population();
        if (worlds.isEmpty()) {
            add(lines, chunks ? "Loaded chunks" : "Entities", "no world loaded");
            return;
        }
        long total = 0;
        for (final var world : worlds) {
            total += chunks ? world.chunks() : world.entities();
            add(lines, world.world() + (chunks ? " chunks / regions" : " entities / players"),
                chunks ? world.chunks() + " / " + world.regions() : world.entities() + " / " + world.players());
        }
        add(lines, chunks ? "Total loaded chunks" : "Total entities", total + " (each region's own count, up to one tick old)");
    }

    private static void renderPlugins(final List<Component> lines) {
        final var plugins = org.bukkit.Bukkit.getPluginManager().getPlugins();
        final java.util.Map<dev.iyanz.sourbycraft.bridge.CompatibilityState, Integer> counts =
            new java.util.EnumMap<>(dev.iyanz.sourbycraft.bridge.CompatibilityState.class);
        for (final var plugin : plugins) counts.merge(PluginsCommand.stateOf(plugin), 1, Integer::sum);
        final StringBuilder summary = new StringBuilder();
        for (final var state : dev.iyanz.sourbycraft.bridge.CompatibilityState.values()) {
            if (summary.length() > 0) summary.append(" / ");
            summary.append(state.name().toLowerCase(Locale.ROOT)).append(' ').append(counts.getOrDefault(state, 0));
        }
        add(lines, "Plugins by state", summary.toString());
        add(lines, "Aurora Bridge mode", String.valueOf(dev.iyanz.sourbycraft.bridge.AuroraBridge.runtimeMode()).toLowerCase(Locale.ROOT));
        for (final var stats : dev.iyanz.sourbycraft.bridge.AuroraBridge.allStats()) {
            add(lines, stats.plugin() + " redirects / handoffs / rejected / violations",
                stats.schedulerRedirects() + " / " + stats.ownerHandoffs() + " / " + stats.rejectedOperations()
                    + " / " + stats.fatalViolations() + (stats.quarantined() ? " (quarantined)" : ""));
        }
    }

    private static void renderHistory(final List<Component> lines) {
        final var samples = dev.iyanz.sourbycraft.perf.PerformanceHistory.GLOBAL.samples();
        if (samples.isEmpty()) {
            add(lines, "History", "no minute recorded yet");
            return;
        }
        add(lines, "Minutes kept", samples.size() + " of " + dev.iyanz.sourbycraft.perf.PerformanceHistory.CAPACITY
            + " (worst region TPS / MSPT over each minute, process CPU, heap)");
        final java.time.format.DateTimeFormatter clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .withZone(java.time.ZoneId.systemDefault());
        // Newest first, at most 15 lines: chat is not a chart.
        for (int i = samples.size() - 1, shown = 0; i >= 0 && shown < 15; i--, shown++) {
            final var s = samples.get(i);
            add(lines, clock.format(java.time.Instant.ofEpochMilli(s.epochMillis())),
                TpsCommand.value(s.worstTps(), 2) + " TPS / " + TpsCommand.ms(s.worstMspt()) + " / "
                    + percent(s.processCpuPercent()) + " CPU / " + bytes(s.heapUsedBytes()) + " of "
                    + bytes(s.heapMaxBytes()));
        }
    }

    /**
     * The player's own view, gathered on the thread that owns the player (their entity
     * scheduler) and sent from there: a command thread may not read an entity it does not own.
     */
    private static void renderPlayer(final CommandSender sender, final String name) {
        final org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(Component.text(name + " is not online", SourbyCraftColors.DIM));
            return;
        }
        final var scheduled = player.getScheduler().run(dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin.INSTANCE,
            task -> {
                final List<Component> lines = new ArrayList<>();
                final org.bukkit.Location at = player.getLocation();
                add(lines, "Player", player.getName());
                add(lines, "World / block", at.getWorld().getName() + " / " + at.getBlockX() + ", " + at.getBlockY()
                    + ", " + at.getBlockZ());
                add(lines, "Chunk", (at.getBlockX() >> 4) + ", " + (at.getBlockZ() >> 4));
                add(lines, "Ping", player.getPing() + " ms");
                add(lines, "View / simulation distance", player.getViewDistance() + " / "
                    + player.getSimulationDistance());
                add(lines, "Owning thread", Thread.currentThread().getName());
                lines.forEach(sender::sendMessage);
            }, () -> sender.sendMessage(Component.text(name + " left before the view was taken", SourbyCraftColors.DIM)));
        if (scheduled == null) {
            sender.sendMessage(Component.text(name + " is being removed", SourbyCraftColors.DIM));
        }
    }

    private static void renderStorage(final List<Component> lines) {
        final var totals = dev.iyanz.sourbycraft.perf.RegionIoCounters.GLOBAL.totals();
        add(lines, "Chunk-system reads / writes / deletes", totals.reads() + " / " + totals.writes() + " / "
            + totals.deletes() + " since start");
        final var queues = dev.iyanz.sourbycraft.perf.RegionIoQueue.INSTANCE.sample();
        if (queues.isEmpty()) {
            add(lines, "Pending storage I/O", "no world loaded");
            return;
        }
        for (final var queue : queues) {
            add(lines, queue.world() + " pending I/O (chunk / poi / entity)",
                queue.chunk() + " / " + queue.poi() + " / " + queue.entity());
        }
    }

    private static void renderAwf(final List<Component> lines) {
        final var engine = dev.iyanz.sourbycraft.awf.AwfEngine.ifStarted();
        final var storages = engine == null ? List.<dev.iyanz.sourbycraft.awf.AwfRegionStorage>of() : engine.storages();
        if (storages.isEmpty()) {
            add(lines, "Aurora World Fabric", "no world is stored in AWF (aurora.awf.worlds)");
            return;
        }
        add(lines, "AWF settings", engine.settings().persistence() + ", commit every "
            + engine.settings().commitIntervalSeconds() + "s, " + engine.settings().residentChunks() + " resident chunks per storage");
        for (final var storage : storages) {
            final var stats = storage.stats();
            final var world = stats.world();
            final String name = shortStorageName(stats.name());
            add(lines, name + " resident / dirty / evicted",
                world.residentChunks() + " / " + world.dirtyChunks() + " / " + stats.evicted());
            add(lines, name + " pending commits / oldest", world.saveQueueDepth() + " / " + world.oldestPendingSaveMillis() + " ms");
            final var commit = world.backend();
            add(lines, name + " commit p50 / p95 / p99", commit.samples() == 0 ? "no commit yet"
                : ms(commit.p50()) + " / " + ms(commit.p95()) + " / " + ms(commit.p99())
                    + " (last " + commit.samples() + " commits)");
            add(lines, name + " reads / from region files / deletes",
                stats.reads() + " / " + stats.baseFallthroughs() + " / " + stats.deletes());
            add(lines, name + " retries / failures / written", world.retries() + " / " + world.failures() + " / "
                + (world.bytesWritten() / 1024) + " KiB");
        }
    }

    /** The last three path elements: world/dimension/region rather than the whole absolute path. */
    static String shortStorageName(final String path) {
        final String[] parts = path.replace('\\', '/').split("/");
        final int from = Math.max(0, parts.length - 3);
        return String.join("/", java.util.Arrays.copyOfRange(parts, from, parts.length));
    }

    private static String ms(final double millis) {
        return String.format(java.util.Locale.ROOT, "%.1f ms", millis);
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
