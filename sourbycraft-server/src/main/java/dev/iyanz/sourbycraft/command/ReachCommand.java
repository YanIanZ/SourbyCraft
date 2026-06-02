package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.combat.ReachTracker;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;

/**
 * SourbyCraft v12 PVP — /reach debug command.
 *
 * <p>Prints the last hit recorded by {@link ReachTracker} (attacker → target,
 * distance in blocks, attacker latency, hit-window ms). Useful for tuning the
 * reach/latency window on a PvP arena.
 */
public class ReachCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        var hit = ReachTracker.last();
        if (hit == null) {
            sender.sendMessage("§7[reach] no hits recorded yet");
            return true;
        }
        sender.sendMessage(String.format(
            "§7[reach] last hit: §f%s §7→ §f%s §7· §a%.2f §7blocks · §e%dms §7latency · §bwindow=%dms §a✓",
            hit.attacker(), hit.target(), hit.distance(), hit.latencyMs(), hit.windowMs()));
        return true;
    }
}
