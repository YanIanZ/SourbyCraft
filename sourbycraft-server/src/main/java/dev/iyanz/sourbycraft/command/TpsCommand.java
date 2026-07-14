package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.perf.sensor.SensorSnapshot;
import dev.iyanz.sourbycraft.perf.sensor.Tier;
import dev.iyanz.sourbycraft.util.BarUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Locale;

import static net.kyori.adventure.text.Component.text;

/**
 * /tps — compact hex TPS panel for SourbyCraft (Folia 26.2).
 *
 * <p>Renders one boxed, hex-coloured readout consistent with /perf /sys /ver:
 * <ul>
 *   <li>Aggregate rolling TPS 1m / 5m / 15m from {@link Bukkit#getTPS()} (Folia
 *       aggregates these server-wide across every region thread) — each window
 *       drawn as a {@code ▰▱} bar and coloured to the perf-engine's TPS ladder
 *       (green ≥19, yellow ≥17, red &lt;17).</li>
 *   <li>Aggregate MSPT from {@link Bukkit#getAverageTickTime()} (Folia averages
 *       tick time across region threads), coloured to the MSPT band.</li>
 *   <li>Folia context line: this build ticks every region on its own thread, so
 *       the numbers above are the server-wide aggregate; the loaded-world count
 *       is shown as a cheap proxy for tick-region spread (an exact per-region
 *       region count needs the NMS regioniser and is intentionally not walked
 *       here to keep this command allocation-light).</li>
 *   <li>Current perf {@link Tier} + heap % straight off {@link PerfSensor#snapshot()},
 *       with the sensor warmup state noted while the rolling averages settle.</li>
 * </ul>
 *
 * <p>Console-runnable and player-runnable. Guarded by
 * {@code sourbycraft.command.tps}; registered with the {@code sourbycraft}
 * fallback prefix so {@code /sourbycraft:tps} always reaches us even when a
 * plugin or Paper built-in claims the bare {@code /tps} slot.
 */
public class TpsCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);
    /** Narrower bar so the three TPS windows align inside the panel. */
    private static final int TPS_BAR_WIDTH = 20;

    public TpsCommand(String name) {
        super(name);
        this.description = "SourbyCraft TPS panel (aggregate TPS/MSPT, perf tier, memory)";
        this.usageMessage = "/tps";
        this.setPermission("sourbycraft.command.tps");
        this.setAliases(java.util.List.of("lag"));
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
            .append(text("TPS", SourbyCraftColors.LABEL))
            .build());

        renderTps(s);
        renderMspt(s);
        renderFolia(s);
        renderTierAndMem(s);

        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }

    private static void renderTps(CommandSender s) {
        // Worst-region windows from the perf sensor (NOT Bukkit.getTPS(), which is only the
        // CALLING region's report on Folia). "now" = 15s window: recovers within seconds of a
        // join/chunk-load burst instead of dragging sub-20 for a whole minute.
        dev.iyanz.sourbycraft.perf.sensor.SensorSnapshot snap =
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.snapshot();
        double[] tps = {snap.tps1s(), snap.tps30s(), snap.tps5m()};
        String[] labels = {"now", "1m ", "5m "};
        s.sendMessage(text("  Worst-region TPS (15s / 1m / 5m):", SourbyCraftColors.LABEL));
        for (int i = 0; i < labels.length; i++) {
            double v = (tps != null && i < tps.length) ? clampTps(tps[i]) : Double.NaN;
            if (Double.isNaN(v)) {
                s.sendMessage(text("    " + labels[i] + "  (unavailable)", SourbyCraftColors.DIM));
                continue;
            }
            TextColor color = tpsColor(v);
            double pct = Math.clamp(v / 20.0, 0.0, 1.0) * 100.0;
            s.sendMessage(text()
                .append(text("    " + labels[i] + " ", SourbyCraftColors.DIM))
                .append(text(BarUtil.bar(pct, TPS_BAR_WIDTH), color))
                .append(text("  " + String.format(Locale.ROOT, "%5.2f", v), color))
                .build());
        }
    }

    private static void renderMspt(CommandSender s) {
        // Worst-region 15s MSPT from the sensor (Bukkit.getAverageTickTime is calling-region only).
        double mspt = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.snapshot().msptAvg();
        if (Double.isNaN(mspt)) {
            s.sendMessage(line("  MSPT", "(unavailable)", SourbyCraftColors.DIM));
            return;
        }
        TextColor color = msptColor(mspt);
        // 50 ms is the 20-TPS tick budget; show how much of it is being spent.
        double budgetPct = Math.clamp(mspt / 50.0, 0.0, 1.0) * 100.0;
        s.sendMessage(text()
            .append(text("  MSPT   ", SourbyCraftColors.DIM))
            .append(text(BarUtil.bar(budgetPct, TPS_BAR_WIDTH), color))
            .append(text("  " + fmtMspt(mspt), color))
            .append(text("  (" + (budgetPct < 1.0 ? String.format(Locale.ROOT, "%.1f", budgetPct) : String.valueOf((int) Math.round(budgetPct)))
                + "% of 50ms budget)", SourbyCraftColors.DIM))
            .build());
    }

    /** A healthy Folia region ticks in tens of microseconds; %.1f would floor that to "0.0ms". */
    private static String fmtMspt(double mspt) {
        if (mspt >= 10.0) return String.format(Locale.ROOT, "%.1fms", mspt);
        if (mspt >= 0.1)  return String.format(Locale.ROOT, "%.2fms", mspt);
        return String.format(Locale.ROOT, "%.3fms", mspt);
    }

    private static void renderFolia(CommandSender s) {
        int worlds = 0;
        try {
            worlds = Bukkit.getWorlds().size();
        } catch (Throwable ignored) {
            // Very early boot — leave at 0.
        }
        s.sendMessage(text()
            .append(text("  Folia: ", SourbyCraftColors.LABEL))
            .append(text("per-region threading", SourbyCraftColors.VALUE))
            .append(text("  —  values above are the server-wide aggregate", SourbyCraftColors.DIM))
            .build());
        s.sendMessage(text()
            .append(text("    Loaded worlds: ", SourbyCraftColors.DIM))
            .append(text(String.valueOf(worlds), SourbyCraftColors.VALUE))
            .append(text("  (each world's regions tick independently)", SourbyCraftColors.DIM))
            .build());
    }

    private static void renderTierAndMem(CommandSender s) {
        SensorSnapshot snap = PerfSensor.snapshot();
        Tier tier = snap == null ? Tier.GREEN : snap.tier();
        TextColor tierColor = tierColor(tier);
        int warmup = PerfSensor.warmupRemainingTicks();

        s.sendMessage(text()
            .append(text("  Perf tier: ", SourbyCraftColors.LABEL))
            .append(text(tier.name(), tierColor))
            .append(warmup > 0
                ? text("  (sensor warmup " + (int) Math.ceil(warmup / 20.0) + "s)", SourbyCraftColors.WARNING)
                : text("  (sensor live)", SourbyCraftColors.DIM))
            .build());

        double memPct = snap != null ? snap.memPct() : memUsagePercent();
        TextColor memColor = memColor(memPct);
        s.sendMessage(text()
            .append(text("  Memory ", SourbyCraftColors.DIM))
            .append(text(BarUtil.bar(memPct, TPS_BAR_WIDTH), memColor))
            .append(text("  " + String.format(Locale.ROOT, "%.1f%%", memPct), memColor))
            .build());
    }

    // --- signal readers (defensive; the Bukkit API can throw very early at boot) ---


    private static double memUsagePercent() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        return max > 0 ? 100.0 * used / max : 0.0;
    }

    private static double clampTps(double v) {
        if (Double.isNaN(v) || v < 0.0) return Double.NaN;
        return Math.min(20.0, v);
    }

    private static Component line(String label, String value, TextColor color) {
        return text()
            .append(text(label + ": ", SourbyCraftColors.DIM))
            .append(text(value, color))
            .build();
    }

    // --- colour ladders: aligned to PerfSensor thresholds (green >=19, yellow >=17, red <17) ---

    private static TextColor tpsColor(double t) {
        return t >= 19.0 ? SourbyCraftColors.SUCCESS
            : t >= 17.0 ? SourbyCraftColors.PRIMARY
            : SourbyCraftColors.DANGER;
    }

    private static TextColor msptColor(double m) {
        // 40 ms mirrors the sensor's YELLOW MSPT threshold; 50 ms is the full tick budget.
        return m < 40.0 ? SourbyCraftColors.SUCCESS
            : m < 50.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    private static TextColor memColor(double p) {
        return p < 75.0 ? SourbyCraftColors.SUCCESS
            : p < 92.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    private static TextColor tierColor(Tier tier) {
        return switch (tier) {
            case GREEN     -> SourbyCraftColors.SUCCESS;
            case YELLOW    -> SourbyCraftColors.PRIMARY;
            case ORANGE    -> SourbyCraftColors.WARNING;
            case RED       -> SourbyCraftColors.DANGER;
            case EMERGENCY -> SourbyCraftColors.ACCENT;
        };
    }
}
