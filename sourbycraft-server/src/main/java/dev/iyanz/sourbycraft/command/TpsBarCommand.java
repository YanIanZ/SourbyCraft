package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static net.kyori.adventure.text.Component.text;

/**
 * /tpsbar — toggle a persistent BossBar that mirrors live TPS + MSPT.
 *
 * <p>First invocation shows the bar; second invocation hides it. The
 * bar is owned by {@link BarToggleManager} which runs a 1s refresh
 * timer so the readings stay current, instead of the previous "show
 * once then auto-hide after 10s" behaviour that operators were filing
 * as a bug.
 */
public class TpsBarCommand extends Command {

    public TpsBarCommand(String n) {
        super(n);
        this.description = "Toggle a persistent TPS BossBar with live refresh";
        this.usageMessage = "/tpsbar";
        this.setPermission("sourbycraft.command.tpsbar");
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only");
            return true;
        }
        if (!testPermission(s)) return true;
        boolean shown = BarToggleManager.toggle(p, BarToggleManager.Kind.TPS);
        p.sendMessage(text(shown ? "TPS bar shown" : "TPS bar hidden",
                shown ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DIM));
        return true;
    }
}
