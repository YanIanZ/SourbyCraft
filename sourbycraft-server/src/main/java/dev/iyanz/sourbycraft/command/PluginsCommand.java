package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.BarUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;

import static net.kyori.adventure.text.Component.text;

/**
 * Custom /plugins. Branded header with the SourbyCraft divider, then a
 * single comma-separated line of "Name vX" entries coloured by enabled
 * state (green = enabled, dim = disabled).
 */
public class PluginsCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);

    public PluginsCommand(String n) {
        super(n);
        this.description = "Plugin list";
        this.usageMessage = "/plugins";
        this.setPermission("sourbycraft.command.plugins");
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        Plugin[] pl = Bukkit.getPluginManager().getPlugins();
        long active = Arrays.stream(pl).filter(Plugin::isEnabled).count();

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("Plugins ", SourbyCraftColors.HEADER))
            .append(text("(" + active + "/" + pl.length + " active)", SourbyCraftColors.LABEL))
            .build());

        var line = text();
        for (int i = 0; i < pl.length; i++) {
            Plugin p = pl[i];
            line.append(text(p.getName() + " v" + p.getPluginMeta().getVersion(),
                p.isEnabled() ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DIM));
            if (i < pl.length - 1) line.append(text(", ", SourbyCraftColors.DIM));
        }
        s.sendMessage(text().append(text("  ", SourbyCraftColors.DIM)).append(line.build()).build());
        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }
}
