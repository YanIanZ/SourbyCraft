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
 * /tpsbar — pops a 10s BossBar with the SourbyCraft-style filled/empty
 * parallelogram readout in the title. The BossBar progress fraction
 * mirrors the bar so the in-world widget tracks the text-based view.
 */
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
        double pct = Math.clamp(tps[0] / 20.0, 0.0, 1.0);
        TextColor color = tps[0] > 18 ? SourbyCraftColors.SUCCESS
            : tps[0] > 15 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
        bar.progress((float) pct);
        bar.color(tps[0] > 18 ? BossBar.Color.GREEN : tps[0] > 15 ? BossBar.Color.YELLOW : BossBar.Color.RED);
        int warmupTicks = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.warmupRemainingTicks();
        net.kyori.adventure.text.TextComponent.Builder line = text()
            .append(text("TPS ", SourbyCraftColors.HEADER))
            .append(text(BarUtil.bar(pct * 100.0, 20), color))
            .append(text(" " + String.format(java.util.Locale.ROOT, "%.1f", tps[0]), color))
            .append(text("  MSPT ", SourbyCraftColors.LABEL))
            .append(text(String.format(java.util.Locale.ROOT, "%.1fms", mspt), SourbyCraftColors.VALUE));
        if (warmupTicks > 0) {
            line.append(text("  warmup " + (int) Math.ceil(warmupTicks / 20.0) + "s", SourbyCraftColors.WARNING));
        }
        bar.name(line.build());
        p.showBossBar(bar);
        Plugin owner = WildstackerManager.ownerPlugin();
        if (owner != null) {
            Bukkit.getScheduler().runTaskLater(owner, () -> p.hideBossBar(bar), 200L);
        }
        return true;
    }
}
