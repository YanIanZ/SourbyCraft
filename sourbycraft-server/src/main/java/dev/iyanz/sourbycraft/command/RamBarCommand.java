package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.BarUtil;
import dev.iyanz.sourbycraft.wildstacker.WildstackerManager;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import static net.kyori.adventure.text.Component.text;

public class RamBarCommand extends Command {
    private static BossBar bar;

    public RamBarCommand(String n) {
        super(n);
        this.description = "BossBar RAM";
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
        if (bar == null) {
            bar = BossBar.bossBar(text("RAM", SourbyCraftColors.HEADER), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        }
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long used = total - rt.freeMemory();
        double pct = max == Long.MAX_VALUE ? (double) used / total : (double) used / max;
        String lbl = max == Long.MAX_VALUE ? BarUtil.INFINITY : (max / 1048576) + "MB";
        bar.progress((float) Math.min(pct, 1.0));
        bar.color(pct < 0.5 ? BossBar.Color.GREEN : pct < 0.8 ? BossBar.Color.YELLOW : BossBar.Color.RED);
        bar.name(text(String.format(java.util.Locale.ROOT, "RAM: %d/%s (%.0f%%)", used / 1048576, lbl, pct * 100), SourbyCraftColors.VALUE));
        p.showBossBar(bar);
        Plugin owner = WildstackerManager.ownerPlugin();
        if (owner != null) {
            Bukkit.getScheduler().runTaskLater(owner, () -> p.hideBossBar(bar), 200L);
        }
        return true;
    }
}
