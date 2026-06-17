package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.wildstacker.WildstackerManager;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import static net.kyori.adventure.text.Component.text;

public class TpsBarCommand extends Command {
    private static BossBar bar;

    public TpsBarCommand(String n) {
        super(n);
        this.description = "BossBar TPS";
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
        if (bar == null) {
            bar = BossBar.bossBar(text("TPS", SourbyCraftColors.HEADER), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        }
        double[] tps = Bukkit.getTPS();
        double mspt = Bukkit.getAverageTickTime();
        bar.progress((float) (tps[0] / 20.0));
        bar.color(tps[0] > 18 ? BossBar.Color.GREEN : tps[0] > 15 ? BossBar.Color.YELLOW : BossBar.Color.RED);
        bar.name(text(String.format(java.util.Locale.ROOT, "TPS: %.1f  MSPT: %.1fms", tps[0], mspt), SourbyCraftColors.VALUE));
        p.showBossBar(bar);
        Plugin owner = WildstackerManager.ownerPlugin();
        if (owner != null) {
            Bukkit.getScheduler().runTaskLater(owner, () -> p.hideBossBar(bar), 200L);
        }
        return true;
    }
}
