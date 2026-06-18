package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.BarUtil;
import dev.iyanz.sourbycraft.wildstacker.WildstackerManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import static net.kyori.adventure.text.Component.text;

/**
 * /rambar — same BossBar style as {@link TpsBarCommand}, but driven by
 * the JVM heap usage. Title renders a 20-glyph SourbyCraft bar plus
 * the percentage so the widget reads consistent with /sys / /tps.
 */
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
        double frac = max == Long.MAX_VALUE ? (double) used / total : (double) used / max;
        double pct = frac * 100.0;
        String lbl = max == Long.MAX_VALUE ? BarUtil.INFINITY : (max / 1048576) + "MB";
        TextColor color = pct < 50 ? SourbyCraftColors.SUCCESS
            : pct < 80 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
        bar.progress((float) Math.min(frac, 1.0));
        bar.color(pct < 50 ? BossBar.Color.GREEN : pct < 80 ? BossBar.Color.YELLOW : BossBar.Color.RED);
        bar.name(text()
            .append(text("RAM ", SourbyCraftColors.HEADER))
            .append(text(BarUtil.bar(pct, 20), color))
            .append(text(" " + (int) Math.round(pct) + "%", color))
            .append(text("  " + (used / 1048576) + "/" + lbl, SourbyCraftColors.VALUE))
            .build());
        p.showBossBar(bar);
        Plugin owner = WildstackerManager.ownerPlugin();
        if (owner != null) {
            Bukkit.getScheduler().runTaskLater(owner, () -> p.hideBossBar(bar), 200L);
        }
        return true;
    }
}
