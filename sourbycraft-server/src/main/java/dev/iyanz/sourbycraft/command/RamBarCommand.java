package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static net.kyori.adventure.text.Component.text;

/**
 * /rambar — toggle a persistent BossBar that mirrors live JVM heap
 * usage. Same toggle semantics as {@link TpsBarCommand}: first
 * invocation shows the bar + starts a 1s refresh, second invocation
 * hides it. Replaces the previous "show once, auto-hide after 10s"
 * behaviour.
 */
public class RamBarCommand extends Command {

    public RamBarCommand(String n) {
        super(n);
        this.description = "Toggle a persistent RAM BossBar with live refresh";
        this.usageMessage = "/rambar";
        this.setPermission("sourbycraft.command.rambar");
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only");
            return true;
        }
        if (!testPermission(s)) return true;
        boolean shown = BarToggleManager.toggle(p, BarToggleManager.Kind.RAM);
        p.sendMessage(text(shown ? "RAM bar shown" : "RAM bar hidden",
                shown ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DIM));
        return true;
    }
}
