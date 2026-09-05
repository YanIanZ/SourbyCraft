package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import dev.iyanz.sourbycraft.perf.MetricsRuntime;
import dev.iyanz.sourbycraft.util.BarUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;

/** Region-aware MSPT panel rendered from one cached performance snapshot. */
public class MsptCommand extends Command {

    private static final int BAR_WIDTH = 20;

    public MsptCommand(final String name) {
        super(name);
        this.description = "MSPT (worst region tick time)";
        this.usageMessage = "/mspt";
        this.setPermission("sourbycraft.command.mspt");
    }

    @Override
    public boolean execute(final CommandSender sender, final String alias, final String[] args) {
        if (!this.testPermission(sender)) return true;
        final PerformanceSnapshot snapshot = MetricsRuntime.provider().snapshot();
        render(snapshot).forEach(sender::sendMessage);
        return true;
    }

    /** Reads the provider once, then renders every line from that immutable generation. */
    public static List<Component> render(final SourbyMetrics metrics) {
        final PerformanceSnapshot snapshot = metrics.snapshot();
        return render(snapshot);
    }

    private static List<Component> render(final PerformanceSnapshot snapshot) {
        final WindowMetrics recent = snapshot.window(MetricWindow.FIVE_SECONDS);
        final List<Component> lines = new ArrayList<>();
        lines.add(text(BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH), SourbyCraftColors.PRIMARY));
        lines.add(text().append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
            .append(text("MSPT", SourbyCraftColors.LABEL))
            .append(text("  (tick execution time)", SourbyCraftColors.DIM)).build());

        final double average = recent.worstAverageMspt();
        final double target = snapshot.targetTps();
        if (!TpsCommand.available(average) || !TpsCommand.available(target) || target <= 0.0) {
            lines.add(text("  Worst average unavailable", SourbyCraftColors.DIM));
        } else {
            final double budget = 1_000.0 / target;
            final double budgetPercent = Math.clamp(average / budget, 0.0, 1.0) * 100.0;
            final TextColor color = TpsCommand.msptColor(average, target);
            lines.add(text().append(text("  Worst average ", SourbyCraftColors.DIM))
                .append(text(TpsCommand.ms(average), color))
                .append(text("  " + BarUtil.bar(budgetPercent, BAR_WIDTH), color))
                .append(text("  (" + String.format(Locale.ROOT, "%.0f", budgetPercent)
                    + "% of " + TpsCommand.ms(budget) + " budget)", SourbyCraftColors.DIM)).build());
        }

        lines.add(text("  Estimated p95 " + TpsCommand.ms(recent.estimatedP95Mspt())
            + " / p99 " + TpsCommand.ms(recent.estimatedP99Mspt()) + " (approximate)",
            SourbyCraftColors.VALUE));
        lines.add(text("  Exact recent max " + TpsCommand.ms(recent.maximumMspt()), SourbyCraftColors.VALUE));
        lines.add(TpsCommand.freshness(snapshot.freshness()));
        lines.add(text(BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH), SourbyCraftColors.DIM));
        return List.copyOf(lines);
    }
}
