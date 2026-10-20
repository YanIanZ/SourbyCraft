package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.perf.MetricsRuntime;
import dev.iyanz.sourbycraft.perf.RegionLocator;
import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/**
 * {@code /perf region}: bare, the region overview rows; {@code /perf region <world> <x> <z>},
 * the region owning the chunk at that block. Every location form is handled here, so a
 * malformed one names its own usage instead of the generic view list.
 */
final class PerfRegionView implements PerfView {

    @Override public String name() { return "region"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        final var tick = snapshot.window(MetricWindow.FIVE_SECONDS);
        lines.add(UiPanel.section("Regions"));
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
        add(lines, "Per-location region metrics", "/perf region <world> <x> <z>");
        add(lines, "Region coordinates / queues", "not instrumented");
    }

    @Override
    public boolean execute(final CommandSender sender, final String command, final String[] args) {
        if (args.length <= 1) {
            return false;                 // Bare "/perf region" is the overview rows above.
        }
        if (args.length == 4) {
            renderRegionAt(sender, command, args[1], args[2], args[3]);
        } else {
            sendRegionUsage(sender, command);
        }
        return true;
    }

    @Override
    public List<String> complete(final String[] args) {
        if (args.length != 2) return List.of();
        final String prefix = args[1].toLowerCase(Locale.ROOT);
        return org.bukkit.Bukkit.getWorlds().stream()
            .map(org.bukkit.World::getName)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
            .toList();
    }

    /** {@code /perf region <world> <x> <z>}: the region owning the chunk at that block. */
    private static void sendRegionUsage(final CommandSender sender, final String command) {
        sender.sendMessage(Component.text("/" + command + " region <world> <x> <z>", SourbyCraftColors.DIM));
    }

    private static void renderRegionAt(final CommandSender sender, final String command, final String world,
                                       final String xArg, final String zArg) {
        final int x;
        final int z;
        try {
            x = Integer.parseInt(xArg);
            z = Integer.parseInt(zArg);
        } catch (final NumberFormatException invalid) {
            sendRegionUsage(sender, command);
            return;
        }
        final RegionLocator.Result result = RegionLocator.locate(world, x, z, System.nanoTime());
        renderRegion(MetricsRuntime.provider(), world, x, z, result).forEach(sender::sendMessage);
    }

    /**
     * Renders a resolved per-location region. Read-only: the region was located from published
     * snapshots, and the {@code metrics} snapshot supplies only the freshness line.
     */
    static List<Component> renderRegion(final SourbyMetrics metrics, final String world, final int x,
                                        final int z, final RegionLocator.Result result) {
        final PerformanceSnapshot snapshot = metrics.snapshot();
        final List<Component> lines = new ArrayList<>();
        lines.add(UiPanel.header("Performance / region"));
        add(lines, "World / block", world + " / " + x + ", " + z);
        switch (result.status()) {
            case FOUND -> {
                add(lines, "Chunk", result.chunkX() + ", " + result.chunkZ());
                add(lines, "Region", "world #" + result.worldId() + " region #" + result.regionId()
                    + " gen " + result.generationId() + (result.active() ? "" : " (inactive)"));
                renderRegionWindows(lines, result.snapshot());
            }
            case NO_REGION_AT_CHUNK -> add(lines, "Region", "no region resolved for chunk " + result.chunkX() + ", "
                + result.chunkZ() + " (approximate lookup: the region may be merging or splitting, or the chunk is not loaded)");
            case UNKNOWN_WORLD -> add(lines, "Region", "unknown world '" + world + "'");
            case NO_SERVER -> add(lines, "Region", "no server running");
            case NO_TELEMETRY -> add(lines, "Region", "world #" + result.worldId() + " region #"
                + result.regionId() + " has no recorded tick metrics yet");
        }
        lines.add(TpsCommand.freshness(snapshot.freshness()));
        lines.add(UiPanel.footer());
        return List.copyOf(lines);
    }

    private static void renderRegionWindows(final List<Component> lines, final RegionTickMetrics.Snapshot snapshot) {
        final RegionTickMetrics.WindowSnapshot five = snapshot.fiveSeconds();
        final RegionTickMetrics.WindowSnapshot minute = snapshot.oneMinute();
        add(lines, "TPS (5s / 1m)", TpsCommand.value(five.tps(), 2) + " / " + TpsCommand.value(minute.tps(), 2));
        add(lines, "Average MSPT (5s / 1m)", TpsCommand.ms(five.mspt()) + " / " + TpsCommand.ms(minute.mspt()));
        add(lines, "Maximum MSPT (5s)", five.sampleCount() > 0L
            ? TpsCommand.ms(five.maximumNanos() / 1_000_000.0) : "unavailable");
        add(lines, "Samples (5s)", five.sampleCount() + " ticks");
        if (five.truncated()) {
            add(lines, "Window", "truncated (ring overflow)");
        }
    }
}
