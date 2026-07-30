package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.BarUtil;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Locale;

import static net.kyori.adventure.text.Component.text;

/**
 * {@code /mspt} — SourbyCraft styled MSPT panel, replacing Paper's plain
 * "Server tick times (avg/min/max) ... 0.0/0.0/0.0" (which floors sub-millisecond values to 0.0).
 *
 * <p>Reports the <b>worst region's</b> tick time via {@link dev.iyanz.sourbycraft.perf.RegionMspt}
 * (no custom sensor class) rather than {@link Bukkit#getAverageTickTime()}, which on the
 * region-threaded engine only sees the caller's own region — so a console/quiet-region reading
 * showed a healthy {@code 0.88ms} while the spawn region choked at seconds per tick. The worst
 * region is the one an operator needs to see; TPS (in {@code /tps}) stays the scheduler rate.
 * Rendered with adaptive precision and a budget bar, matching the look of {@code /tps}.
 */
public class MsptCommand extends Command {

    private static final int BAR_WIDTH = 20;

    public MsptCommand(String name) {
        super(name);
        this.description = "MSPT (worst region tick time)";
        this.usageMessage = "/mspt";
        this.setPermission("sourbycraft.command.mspt");
    }

    /** Renders the current MSPT reading as a budget bar with adaptive-precision formatting. */
    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        double mspt;
        try {
            mspt = dev.iyanz.sourbycraft.perf.RegionMspt.worstMsptMs();
        } catch (Throwable ignored) {
            mspt = Double.NaN;
        }
        s.sendMessage(text(BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH), SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
            .append(text("MSPT", SourbyCraftColors.LABEL))
            .append(text("  (tick execution time)", SourbyCraftColors.DIM))
            .build());
        if (Double.isNaN(mspt)) {
            s.sendMessage(text("  (unavailable)", SourbyCraftColors.DIM));
            s.sendMessage(text(BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH), SourbyCraftColors.DIM));
            return true;
        }
        final double budgetPct = Math.clamp(mspt / 50.0, 0.0, 1.0) * 100.0;
        final TextColor color = mspt < 25 ? SourbyCraftColors.SUCCESS : mspt < 40 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
        s.sendMessage(text()
            .append(text("  worst ", SourbyCraftColors.DIM))
            .append(text(BarUtil.bar(budgetPct, BAR_WIDTH), color))
            .append(text("  " + fmt(mspt), color))
            .append(text("  (" + (budgetPct < 1.0 ? String.format(Locale.ROOT, "%.1f", budgetPct) : String.valueOf((int) Math.round(budgetPct)))
                + "% of the 50ms tick budget)", SourbyCraftColors.DIM))
            .build());
        s.sendMessage(text(BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH), SourbyCraftColors.DIM));
        return true;
    }

    /** A healthy region ticks in tens of microseconds; %.1f would floor that to "0.0ms". */
    private static String fmt(double mspt) {
        if (mspt >= 10.0) return String.format(Locale.ROOT, "%.1fms", mspt);
        if (mspt >= 0.1)  return String.format(Locale.ROOT, "%.2fms", mspt);
        return String.format(Locale.ROOT, "%.3fms", mspt);
    }
}
