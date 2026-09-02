package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.api.metrics.Freshness;
import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import dev.iyanz.sourbycraft.perf.MetricsRuntime;
import dev.iyanz.sourbycraft.perf.Tier;
import dev.iyanz.sourbycraft.util.BarUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;

/** Region-aware TPS panel rendered from one cached performance snapshot. */
public class TpsCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);
    private static final int TPS_BAR_WIDTH = 20;

    public TpsCommand(final String name) {
        super(name);
        this.description = "SourbyCraft TPS panel (TPS/MSPT, tier, memory)";
        this.usageMessage = "/tps";
        this.setPermission("sourbycraft.command.tps");
        this.setAliases(List.of("lag"));
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
        final List<Component> lines = new ArrayList<>();
        lines.add(text(DIVIDER, SourbyCraftColors.PRIMARY));
        lines.add(text().append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
            .append(text("TPS", SourbyCraftColors.LABEL)).build());

        final double target = snapshot.targetTps();
        lines.add(text("  Target " + value(target, 2) + " TPS", SourbyCraftColors.DIM));
        renderWindow(lines, "1m ", snapshot.window(MetricWindow.ONE_MINUTE), target);
        renderWindow(lines, "5m ", snapshot.window(MetricWindow.FIVE_MINUTES), target);
        renderWindow(lines, "15m", snapshot.window(MetricWindow.FIFTEEN_MINUTES), target);

        final WindowMetrics recent = snapshot.window(MetricWindow.FIVE_SECONDS);
        lines.add(text().append(text("  Worst average MSPT: ", SourbyCraftColors.LABEL))
            .append(text(ms(recent.worstAverageMspt()), msptColor(recent.worstAverageMspt(), target))).build());
        final boolean topologyAvailable = snapshot.freshness().state() == MetricState.AVAILABLE
            || snapshot.freshness().state() == MetricState.STALE;
        lines.add(text().append(text("  Active regions: ", SourbyCraftColors.LABEL))
            .append(text(topologyAvailable ? Integer.toString(snapshot.activeRegionCount()) : "unavailable",
                topologyAvailable ? SourbyCraftColors.VALUE : SourbyCraftColors.DIM)).build());

        final WindowMetrics global = MetricsRuntime.globalWindow(snapshot, MetricWindow.FIVE_SECONDS);
        final String globalStatus = available(global.worstTps()) || available(global.worstAverageMspt())
            ? "TPS " + value(global.worstTps(), 2) + " / MSPT " + ms(global.worstAverageMspt())
            : "unavailable";
        lines.add(text().append(text("  Global: ", SourbyCraftColors.LABEL))
            .append(text(globalStatus, SourbyCraftColors.VALUE)).build());

        renderRuntime(lines, snapshot.runtime());
        final double worstTps = recent.worstTps();
        if (available(worstTps) && available(target) && target > 0.0) {
            final Tier tier = tierFor(worstTps, recent.worstAverageMspt(), target);
            lines.add(text().append(text("  Tier: ", SourbyCraftColors.LABEL))
                .append(text(tier.name(), tierColor(tier))).build());
        } else {
            lines.add(text("  Tier: unavailable", SourbyCraftColors.DIM));
        }
        lines.add(freshness(snapshot.freshness()));
        lines.add(text(DIVIDER, SourbyCraftColors.DIM));
        return List.copyOf(lines);
    }

    private static void renderWindow(final List<Component> lines, final String label,
                                     final WindowMetrics window, final double target) {
        if (!available(window.worstTps()) || !available(target) || target <= 0.0) {
            lines.add(text("  " + label + "  unavailable", SourbyCraftColors.DIM));
            return;
        }
        final double progress = Math.clamp(window.worstTps() / target, 0.0, 1.0) * 100.0;
        final TextColor color = tpsColor(window.worstTps(), target);
        lines.add(text().append(text("  " + label + " ", SourbyCraftColors.DIM))
            .append(text(BarUtil.bar(progress, TPS_BAR_WIDTH), color))
            .append(text("  Worst " + value(window.worstTps(), 2), color))
            .append(text(" / Median " + value(window.medianTps(), 2)
                + " / Aggregate " + value(window.aggregateTps(), 2), SourbyCraftColors.DIM)).build());
    }

    private static void renderRuntime(final List<Component> lines, final RuntimeMetrics runtime) {
        if (runtime.heapMaxBytes() > 0L && runtime.heapUsedBytes() >= 0L) {
            final double heap = 100.0 * runtime.heapUsedBytes() / runtime.heapMaxBytes();
            lines.add(text("  Memory " + value(heap, 1) + "%", memoryColor(heap)));
        }
        if (available(runtime.gcTimePercent())) {
            lines.add(text("  GC: " + value(runtime.gcTimePercent(), 1) + "% time  "
                + value(runtime.gcCollectionsPerMinute(), 1) + "/min  ~"
                + value(runtime.averageGcPauseMs(), 1) + "ms avg", SourbyCraftColors.DIM));
        }
    }

    static Component freshness(final Freshness freshness) {
        final String age = freshness.state() == MetricState.STALE
            ? " (" + freshness.ageMillis() + "ms old)" : "";
        final String diagnostic = freshness.diagnostic().isBlank() ? "" : ": " + freshness.diagnostic();
        return text("  Freshness: " + freshness.state().name() + age + diagnostic,
            freshness.state() == MetricState.AVAILABLE
                ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DIM);
    }

    public static String ms(final double value) {
        if (!available(value)) return "unavailable";
        if (value >= 10.0) return String.format(Locale.ROOT, "%.1fms", value);
        if (value >= 0.1) return String.format(Locale.ROOT, "%.2fms", value);
        return String.format(Locale.ROOT, "%.3fms", value);
    }

    public static String value(final double value, final int precision) {
        return available(value) ? String.format(Locale.ROOT, "%." + precision + "f", value) : "unavailable";
    }

    public static boolean available(final double value) {
        return Double.isFinite(value);
    }

    public static TextColor tpsColor(final double tps, final double target) {
        if (!available(tps) || !available(target) || target <= 0.0) return SourbyCraftColors.DIM;
        return tps >= target * 0.95 ? SourbyCraftColors.SUCCESS
            : tps >= target * 0.85 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    public static TextColor msptColor(final double mspt, final double target) {
        if (!available(mspt) || !available(target) || target <= 0.0) return SourbyCraftColors.DIM;
        final double budget = 1_000.0 / target;
        return mspt < budget * 0.8 ? SourbyCraftColors.SUCCESS
            : mspt < budget ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    private static Tier tierFor(final double tps, final double mspt, final double target) {
        if (!available(tps) || !available(target) || target <= 0.0) return Tier.RED;
        final double budget = 1_000.0 / target;
        if (tps >= target * 0.9 && (!available(mspt) || mspt < budget * 0.8)) return Tier.GREEN;
        if (tps >= target * 0.75 && (!available(mspt) || mspt < budget)) return Tier.YELLOW;
        return Tier.RED;
    }

    private static TextColor tierColor(final Tier tier) {
        return switch (tier) {
            case GREEN -> SourbyCraftColors.SUCCESS;
            case YELLOW -> SourbyCraftColors.PRIMARY;
            case RED -> SourbyCraftColors.DANGER;
        };
    }

    private static TextColor memoryColor(final double percent) {
        return percent < 75.0 ? SourbyCraftColors.SUCCESS
            : percent < 92.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }
}
