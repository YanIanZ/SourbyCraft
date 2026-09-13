package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.hud.HudBars;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Combined HUD preference is changed only on the command player's owning region. */
public final class PerfBarCommand extends Command {
    public PerfBarCommand() {
        super("perfbar");
        this.description = "Toggle the combined TPS/MSPT/RAM/CPU HUD";
        this.usageMessage = "/perfbar [on|off]";
        setPermission("sourbycraft.command.perfbar");
    }

    @Override public boolean execute(CommandSender sender, String alias, String[] args) {
        if (!testPermission(sender)) return true;
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only: /perfbar is a client HUD.");
            return true;
        }
        if (args.length > 1 || args.length == 1
            && !args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off")) {
            sender.sendMessage(usageMessage);
            return true;
        }
        final boolean shown = HudBars.setPerf(player, args.length == 0 ? null : args[0].equalsIgnoreCase("on"));
        sender.sendMessage("SourbyCraft performance bar " + (shown ? "shown" : "hidden"));
        return true;
    }
}
