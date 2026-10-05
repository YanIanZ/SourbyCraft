package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics;
import dev.iyanz.sourbycraft.bridge.AuroraBridge;
import dev.iyanz.sourbycraft.bridge.BridgeTelemetry;
import dev.iyanz.sourbycraft.bridge.CompatibilityClassifier;
import dev.iyanz.sourbycraft.bridge.CompatibilityState;
import dev.iyanz.sourbycraft.util.BarUtil;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static net.kyori.adventure.text.Component.text;

/**
 * Custom /plugins, also answering /pl so both show the same roster. Branded header with the
 * SourbyCraft divider, then a single comma-separated line of plugin names (no versions) coloured
 * by Aurora compatibility state (see
 * {@code docs/architecture/aurora-plugin-bridge.md}): blue NATIVE, green BRIDGED, red FAILED,
 * grey DISABLED. Plugins that never loaded are not in the plugin manager; they are listed after
 * the roster from the captured load failures, in red.
 */
public class PluginsCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);
    /** A trailing version in a jar name: "-2.22.1-dev+26", "_v1.0", " 5.12.0". */
    private static final Pattern JAR_VERSION = Pattern.compile("[-_ ]v?\\d[\\w.+-]*$");

    public PluginsCommand(String n) {
        super(n);
        this.description = "Plugin list";
        this.usageMessage = "/plugins [plugin]";
        this.setAliases(List.of("pl"));
        this.setPermission("sourbycraft.command.plugins");
    }

    /**
     * What a plugin that never loaded is listed as: its jar name without the extension or the
     * version, so the roster reads the same as the loaded plugins, which show only their names.
     */
    static String failureName(final String jarName) {
        String name = jarName.endsWith(".jar") ? jarName.substring(0, jarName.length() - 4) : jarName;
        final String stripped = JAR_VERSION.matcher(name).replaceFirst("");
        return stripped.isEmpty() ? name : stripped;
    }

    /** The state a loaded plugin is shown in, from what the server has observed about it. */
    static CompatibilityState stateOf(final Plugin plugin) {
        return CompatibilityClassifier.classify(new CompatibilityClassifier.Evidence(
            plugin.getPluginMeta().isFoliaSupported(),
            plugin.isEnabled(),
            PluginLoadDiagnostics.enableFailed(plugin.getName()),
            AuroraBridge.bridgeInitialized(plugin),
            AuroraBridge.fatalViolation(plugin)));
    }

    static TextColor colorOf(final CompatibilityState state) {
        return switch (state) {
            case NATIVE -> SourbyCraftColors.PLUGIN_NATIVE;
            case BRIDGED -> SourbyCraftColors.PLUGIN_BRIDGED;
            case FAILED -> SourbyCraftColors.PLUGIN_FAILED;
            case DISABLED -> SourbyCraftColors.PLUGIN_DISABLED;
        };
    }

    /** One plugin's compatibility state and, when bridged, its bridge telemetry. */
    private boolean detail(final CommandSender s, final String name) {
        final Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        if (plugin == null) {
            s.sendMessage(text("No loaded plugin named " + name, SourbyCraftColors.DANGER));
            return true;
        }
        final CompatibilityState state = stateOf(plugin);
        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(plugin.getName() + " ", SourbyCraftColors.HEADER))
            .append(text(state.display(), colorOf(state)))
            .build());
        final BridgeTelemetry.PluginStats stats = AuroraBridge.stats(plugin);
        if (stats == null) {
            s.sendMessage(text("  Not bridged: declares region-threading support or was not admitted.",
                SourbyCraftColors.LABEL));
        } else {
            line(s, "Scheduler redirects", Long.toString(stats.schedulerRedirects()));
            line(s, "Owner handoffs", Long.toString(stats.ownerHandoffs()));
            line(s, "Rejected operations", Long.toString(stats.rejectedOperations()));
            line(s, "Fatal violations", Long.toString(stats.fatalViolations()));
            line(s, "Quarantined", stats.quarantined() ? "yes" : "no");
            line(s, "Startup cache", stats.startupCacheState());
            line(s, "Last failure", stats.lastFailure() == null ? "none" : stats.lastFailure());
        }
        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }

    private static void line(final CommandSender s, final String label, final String value) {
        s.sendMessage(text()
            .append(text("  " + label + ": ", SourbyCraftColors.LABEL))
            .append(text(value, SourbyCraftColors.VALUE))
            .build());
    }

    /** Renders the branded plugin roster as one comma-separated line of plugin names. */
    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        if (args.length == 1) {
            return detail(s, args[0]);
        }
        Plugin[] pl = Bukkit.getPluginManager().getPlugins();
        long active = Arrays.stream(pl).filter(Plugin::isEnabled).count();
        var loadFailures = PluginLoadDiagnostics.recent();

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("Plugins ", SourbyCraftColors.HEADER))
            .append(text("(" + active + "/" + pl.length + " active)", SourbyCraftColors.LABEL))
            .build());

        final Map<CompatibilityState, Integer> counts = new EnumMap<>(CompatibilityState.class);
        var line = text();
        boolean first = true;
        for (Plugin p : pl) {
            final CompatibilityState state = stateOf(p);
            counts.merge(state, 1, Integer::sum);
            if (!first) line.append(text(", ", SourbyCraftColors.DIM));
            line.append(text(p.getName(), colorOf(state)));
            first = false;
        }
        for (PluginLoadDiagnostics.Entry failure : loadFailures) {
            counts.merge(CompatibilityState.FAILED, 1, Integer::sum);
            if (!first) line.append(text(", ", SourbyCraftColors.DIM));
            line.append(text(failureName(failure.pluginJar()), SourbyCraftColors.PLUGIN_FAILED));
            first = false;
        }
        s.sendMessage(text().append(text("  ", SourbyCraftColors.DIM)).append(line.build()).build());

        var legend = text().append(text("  ", SourbyCraftColors.DIM));
        for (CompatibilityState state : CompatibilityState.values()) {
            legend.append(text(state.display() + " " + counts.getOrDefault(state, 0) + "  ", colorOf(state)));
        }
        s.sendMessage(legend.build());
        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }
}
