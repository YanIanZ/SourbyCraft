package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;

/**
 * /stack — toggle ground item stacking on/off.
 * Status info lives in /sys and /perf.
 */
public class WildstackerDebugCommand extends Command {
    public WildstackerDebugCommand(String n) {
        super(n);
        this.description = "Toggle item stack";
        this.usageMessage = "/stack";
        this.setPermission("sourbycraft.command.stack");
    }

    @Override
    public boolean execute(CommandSender s, String a, String[] args) {
        if (!testPermission(s)) return true;
        SourbyCraftConfig.wildstackerEnabled = !SourbyCraftConfig.wildstackerEnabled;
        boolean on = SourbyCraftConfig.wildstackerEnabled;
        s.sendMessage(text().append(text("Stack ", SourbyCraftColors.LABEL))
            .append(text(on ? "ENABLED" : "DISABLED",
                on ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER)));
        return true;
    }
}
