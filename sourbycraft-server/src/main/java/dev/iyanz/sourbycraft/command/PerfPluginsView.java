package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/** {@code /perf plugins}: plugins by compatibility state, slowest startups, Aurora Bridge counters. */
final class PerfPluginsView implements PerfView {

    @Override public String name() { return "plugins"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        final var plugins = org.bukkit.Bukkit.getPluginManager().getPlugins();
        final java.util.Map<dev.iyanz.sourbycraft.bridge.CompatibilityState, Integer> counts =
            new java.util.EnumMap<>(dev.iyanz.sourbycraft.bridge.CompatibilityState.class);
        for (final var plugin : plugins) counts.merge(PluginsCommand.stateOf(plugin), 1, Integer::sum);
        // Load failures (one per jar), as /plugins counts them: a plugin refused while aurora.bridge.mode = off
        // never reaches the plugin manager, and "failed 0" here beside "Failed 1" in /plugins contradicted it.
        final long loadFailures = dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics.recent().stream()
            .map(dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics.Entry::pluginJar).distinct().count();
        if (loadFailures > 0) {
            counts.merge(dev.iyanz.sourbycraft.bridge.CompatibilityState.FAILED, (int)loadFailures, Integer::sum);
        }
        final StringBuilder summary = new StringBuilder();
        for (final var state : dev.iyanz.sourbycraft.bridge.CompatibilityState.values()) {
            if (summary.length() > 0) summary.append(" / ");
            summary.append(state.name().toLowerCase(Locale.ROOT)).append(' ').append(counts.getOrDefault(state, 0));
        }
        add(lines, "Plugins by state", summary.toString());
        add(lines, "Aurora Bridge mode", String.valueOf(dev.iyanz.sourbycraft.bridge.AuroraBridge.runtimeMode()).toLowerCase(Locale.ROOT));
        add(lines, "Thread checks", dev.iyanz.sourbycraft.guards.ThreadChecks.describe()
            + (dev.iyanz.sourbycraft.guards.ThreadChecks.RELAXED_SCOPE
                ? "; relaxed hits " + dev.iyanz.sourbycraft.guards.ThreadChecks.relaxedHits()
                    + " (outside bridged bodies " + dev.iyanz.sourbycraft.guards.ThreadChecks.relaxedHitsOutsideBridge() + ")"
                : ""));
        renderSlowestStartups(lines, dev.iyanz.sourbycraft.startup.PluginTimings.GLOBAL);
        for (final var stats : dev.iyanz.sourbycraft.bridge.AuroraBridge.allStats()) {
            add(lines, stats.plugin() + " redirects / handoffs / rejected / violations",
                stats.schedulerRedirects() + " / " + stats.ownerHandoffs() + " / " + stats.rejectedOperations()
                    + " / " + stats.fatalViolations() + (stats.quarantined() ? " (quarantined)" : "")
                    + (stats.relaxedOwnershipHits() > 0 ? "; relaxed ownership hits " + stats.relaxedOwnershipHits() : ""));
            final var region = stats.bodyTimes().region();
            final var global = stats.bodyTimes().global();
            add(lines, stats.plugin() + " sync body total / p99 (region; global)", region.count() + global.count() == 0
                ? "none yet"
                : TpsCommand.ms(region.totalMillis()) + " / " + TpsCommand.ms(region.p99Millis()) + "; "
                    + TpsCommand.ms(global.totalMillis()) + " / " + TpsCommand.ms(global.p99Millis()));
        }
    }

    /** The five slowest plugins by onLoad + onEnable wall time, this run. */
    static void renderSlowestStartups(final List<Component> lines, final dev.iyanz.sourbycraft.startup.PluginTimings timings) {
        final var slowest = timings.slowest(5);
        if (slowest.isEmpty()) {
            add(lines, "Slowest plugin startup", "none recorded");
            return;
        }
        for (int i = 0; i < slowest.size(); i++) {
            final var timing = slowest.get(i);
            add(lines, "Slowest startup #" + (i + 1) + " " + timing.plugin(), timing.describe());
        }
        if (timings.dropped() > 0) {
            add(lines, "Startup timings not kept", timings.dropped() + " plugin name(s) past the "
                + dev.iyanz.sourbycraft.startup.PluginTimings.MAX_PLUGINS + " limit");
        }
    }
}
