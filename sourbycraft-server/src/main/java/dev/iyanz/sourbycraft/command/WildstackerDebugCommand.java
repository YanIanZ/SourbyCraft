package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.wildstacker.WildstackerManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

public class WildstackerDebugCommand extends Command {
    public WildstackerDebugCommand(String n) {
        super(n);
        this.description = "Ground item stack status";
        this.usageMessage = "/stack";
        this.setPermission("sourbycraft.command.stack");
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
