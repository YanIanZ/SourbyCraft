package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.wildstacker.WildstackerManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

public class WildstackerDebugCommand extends Command {
    public WildstackerDebugCommand(String n) {
        super(n);
        this.description = "Wildstacker debug status";
        this.usageMessage = "/wildstack";
        this.setPermission("sourbycraft.command.wildstack");
    }

    @Override
    public boolean execute(CommandSender s, String a, String[] args) {
        if (!testPermission(s)) return true;
        for (String line : WildstackerManager.status().split("\n")) {
            s.sendMessage(line);
        }
        return true;
    }
}
