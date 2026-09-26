package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics;
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
import java.util.Map;

import static net.kyori.adventure.text.Component.text;

/**
 * Custom /plugins. Branded header with the SourbyCraft divider, then a single comma-separated
 * line of "Name vX" entries coloured by Aurora compatibility state (see
 * {@code docs/architecture/aurora-plugin-bridge.md}): blue NATIVE, green BRIDGED, red FAILED,
 * grey DISABLED. Plugins that never loaded are not in the plugin manager; they are listed after
 * the roster from the captured load failures, in red.
 */
public class PluginsCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);

    public PluginsCommand(String n) {
        super(n);
        this.description = "Plugin list";
        this.usageMessage = "/plugins";
        this.setPermission("sourbycraft.command.plugins");
    }

    /** The state a loaded plugin is shown in, from what the server has observed about it. */
    static CompatibilityState stateOf(final Plugin plugin) {
        return CompatibilityClassifier.classify(new CompatibilityClassifier.Evidence(
            plugin.getPluginMeta().isFoliaSupported(),
            plugin.isEnabled(),
            PluginLoadDiagnostics.enableFailed(plugin.getName()),
            // No bridge adapter exists yet; the base refuses undeclared plugins at load.
            false,
            false));
    }

    static TextColor colorOf(final CompatibilityState state) {
        return switch (state) {
            case NATIVE -> SourbyCraftColors.PLUGIN_NATIVE;
            case BRIDGED -> SourbyCraftColors.PLUGIN_BRIDGED;
            case FAILED -> SourbyCraftColors.PLUGIN_FAILED;
            case DISABLED -> SourbyCraftColors.PLUGIN_DISABLED;
        };
    }

    /** Renders the branded plugin roster as one comma-separated "Name vX" line per plugin. */
    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
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
            String pluginVer = p.getPluginMeta().getVersion();
            // Strip leading v/V if plugin already prefixes — avoids "vv10".
            final String label = pluginVer != null && (pluginVer.startsWith("v") || pluginVer.startsWith("V"))
                ? p.getName() + " " + pluginVer
                : p.getName() + " v" + (pluginVer == null ? "?" : pluginVer);
            if (!first) line.append(text(", ", SourbyCraftColors.DIM));
            line.append(text(label, colorOf(state)));
            first = false;
        }
        for (PluginLoadDiagnostics.Entry failure : loadFailures) {
            counts.merge(CompatibilityState.FAILED, 1, Integer::sum);
            if (!first) line.append(text(", ", SourbyCraftColors.DIM));
            line.append(text(failure.pluginJar(), SourbyCraftColors.PLUGIN_FAILED));
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
