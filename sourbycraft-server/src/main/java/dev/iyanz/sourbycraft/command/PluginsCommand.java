package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Arrays;

import static net.kyori.adventure.text.Component.text;

public class PluginsCommand extends Command {
    public PluginsCommand(String n) {
        super(n);
        this.description = "Plugin list";
        this.usageMessage = "/plugins";
        this.setPermission("sourbycraft.command.plugins");
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        var pl = Bukkit.getPluginManager().getPlugins();
        s.sendMessage(text().append(text("Plugins (" + pl.length + "): ", SourbyCraftColors.HEADER))
            .append(text(Arrays.stream(pl)
                .map(p -> p.getName() + " v" + p.getPluginMeta().getVersion())
                .reduce((x, y) -> x + ", " + y).orElse("none"), SourbyCraftColors.VALUE)));
        return true;
    }
}
