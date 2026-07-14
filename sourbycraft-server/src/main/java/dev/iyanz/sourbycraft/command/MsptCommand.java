package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.perf.sensor.SensorSnapshot;
import dev.iyanz.sourbycraft.util.BarUtil;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Locale;

import static net.kyori.adventure.text.Component.text;

/**
 * {@code /mspt} — SourbyCraft styled MSPT panel, replacing Paper's plain
 * "Server tick times (avg/min/max) ... 0.0/0.0/0.0" (which reports only the calling region and
 * floors sub-millisecond values to 0.0). Shows the WORST-region 15s / 1m / 5m MSPT from the perf
 * sensor with adaptive precision and a budget bar, matching the look of {@code /tps}.
 */
public class MsptCommand extends Command {

    private static final int BAR_WIDTH = 20;

    public MsptCommand(String name) {
        super(name);
        this.description = "Worst-region MSPT (tick execution time)";
        this.usageMessage = "/mspt";
        this.setPermission("sourbycraft.command.mspt");
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        final SensorSnapshot snap = PerfSensor.snapshot();
        s.sendMessage(text(BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH), SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
            .append(text("MSPT", SourbyCraftColors.LABEL))
            .append(text("  (worst region, tick execution time)", SourbyCraftColors.DIM))
            .build());
        final double mspt = snap.msptAvg();
        final double budgetPct = Math.clamp(mspt / 50.0, 0.0, 1.0) * 100.0;
        final TextColor color = mspt < 25 ? SourbyCraftColors.SUCCESS : mspt < 40 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
        s.sendMessage(text()
            .append(text("  now   ", SourbyCraftColors.DIM))
            .append(text(BarUtil.bar(budgetPct, BAR_WIDTH), color))
            .append(text("  " + fmt(mspt), color))
            .append(text("  (" + (budgetPct < 1.0 ? String.format(Locale.ROOT, "%.1f", budgetPct) : String.valueOf((int) Math.round(budgetPct)))
                + "% of the 50ms tick budget)", SourbyCraftColors.DIM))
            .build());
        s.sendMessage(text("  Folia: per-region — this is the busiest region's average.", SourbyCraftColors.DIM));
        s.sendMessage(text(BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH), SourbyCraftColors.DIM));
        return true;
    }

    /** A healthy Folia region ticks in tens of microseconds; %.1f would floor that to "0.0ms". */
    private static String fmt(double mspt) {
        if (mspt >= 10.0) return String.format(Locale.ROOT, "%.1fms", mspt);
        if (mspt >= 0.1)  return String.format(Locale.ROOT, "%.2fms", mspt);
        return String.format(Locale.ROOT, "%.3fms", mspt);
    }
}
