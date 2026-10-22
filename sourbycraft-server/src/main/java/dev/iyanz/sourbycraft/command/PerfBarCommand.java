package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.hud.HudBars;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Combined TPS/MSPT/RAM/CPU HUD toggle. Like {@code /tpsbar} and {@code /rambar} it supports a
 * per-player {@code bossbar} (default) or {@code actionbar} delivery mode; the choice is stored in
 * the player's data and survives a rejoin.
 */
public final class PerfBarCommand extends Command {
    public PerfBarCommand() {
        super("perfbar");
        this.description = "Toggle the combined TPS/MSPT/RAM/CPU HUD";
        this.usageMessage = "/perfbar [on|off|bossbar|actionbar]";
        setPermission("sourbycraft.command.perfbar");
    }

    @Override public boolean execute(CommandSender sender, String alias, String[] args) {
        if (!testPermission(sender)) return true;
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only: /perfbar is a client HUD.");
            return true;
        }
        if (args.length > 1) {
            sender.sendMessage(usageMessage);
            return true;
        }
        final boolean shown;
        String suffix = "";
        if (args.length == 0) {
            shown = HudBars.setPerf(player, null);
        } else {
            final String argument = args[0].toLowerCase(Locale.ROOT);
            final HudBars.DisplayMode mode = HudBars.DisplayMode.parse(argument);
            if (mode != null) {
                shown = HudBars.setPerfMode(player, mode);
                suffix = " (" + argument + ")";
            } else if (argument.equals("on") || argument.equals("off")) {
                shown = HudBars.setPerf(player, argument.equals("on"));
            } else {
                sender.sendMessage(usageMessage);
                return true;
            }
        }
        sender.sendMessage("SourbyCraft performance bar " + (shown ? "shown" : "hidden") + suffix);
        return true;
    }
}
