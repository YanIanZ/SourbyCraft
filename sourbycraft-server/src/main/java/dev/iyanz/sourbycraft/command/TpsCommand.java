package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.perf.Tier;
import dev.iyanz.sourbycraft.util.BarUtil;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Locale;

import static net.kyori.adventure.text.Component.text;

/**
 * /tps — compact hex TPS panel for SourbyCraft (Canvas re-platform, feat/canvas-engine).
 *
 * <p>Renders one boxed, hex-coloured readout consistent with /sys /ver, reading straight off the
 * native server API — no custom sensor class, no sampling loop:
 * <ul>
 *   <li>Rolling TPS 1m / 5m / 15m from {@link Bukkit#getTPS()} — each window drawn as a
 *       {@code ▰▱} bar and coloured to a simple display ladder (green ≥19, yellow ≥17, red &lt;17).</li>
 *   <li>MSPT from {@link Bukkit#getAverageTickTime()}, coloured to the MSPT band.</li>
 *   <li>Region-threading context line: Canvas ticks regions independently, so the
 *       loaded-world count is shown as a cheap proxy for tick-region spread (an
 *       exact per-region count needs the NMS regioniser and is intentionally not
 *       walked here to keep this command allocation-light).</li>
 *   <li>A display-only {@link Tier} derived inline from the TPS/MSPT numbers above, + heap %.
 *       The self-tuning perf-engine this used to read from is DEFERRED on this benchmark build.</li>
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
        this.description = "SourbyCraft TPS panel (TPS/MSPT, tier, memory)";
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

        double[] tps = safeTps();
        double mspt = safeMspt();

        renderTps(s, tps);
        renderMspt(s, mspt);
        renderEngine(s);
        renderTierAndMem(s, tps[0], mspt);

        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }

    private static void renderTps(CommandSender s, double[] tps) {
        String[] labels = {"1m ", "5m ", "15m"};
        s.sendMessage(text("  TPS (1m / 5m / 15m):", SourbyCraftColors.LABEL));
        for (int i = 0; i < labels.length; i++) {
            double v = i < tps.length ? tps[i] : Double.NaN;
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

    private static void renderMspt(CommandSender s, double mspt) {
        if (Double.isNaN(mspt)) {
            s.sendMessage(text()
                .append(text("  MSPT: ", SourbyCraftColors.DIM))
                .append(text("(unavailable)", SourbyCraftColors.DIM))
                .build());
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

    /** A healthy region ticks in tens of microseconds; %.1f would floor that to "0.0ms". */
    private static String fmtMspt(double mspt) {
        if (mspt >= 10.0) return String.format(Locale.ROOT, "%.1fms", mspt);
        if (mspt >= 0.1)  return String.format(Locale.ROOT, "%.2fms", mspt);
        return String.format(Locale.ROOT, "%.3fms", mspt);
    }

    private static void renderEngine(CommandSender s) {
        int worlds = 0;
        try {
            worlds = Bukkit.getWorlds().size();
        } catch (Throwable ignored) {
            // Very early boot — leave at 0.
        }
        s.sendMessage(text()
            .append(text("  Engine: ", SourbyCraftColors.LABEL))
            .append(text("Canvas (region-threading)", SourbyCraftColors.VALUE))
            .build());
        s.sendMessage(text()
            .append(text("    Loaded worlds: ", SourbyCraftColors.DIM))
            .append(text(String.valueOf(worlds), SourbyCraftColors.VALUE))
            .append(text("  (each world's regions tick independently)", SourbyCraftColors.DIM))
            .build());
    }

    private static void renderTierAndMem(CommandSender s, double tps1m, double mspt) {
        Tier tier = tierFor(tps1m, mspt);
        TextColor tierColor = tierColor(tier);

        s.sendMessage(text()
            .append(text("  Tier: ", SourbyCraftColors.LABEL))
            .append(text(tier.name(), tierColor))
            .build());

        double memPct = memUsagePercent();
        TextColor memColor = memColor(memPct);
        s.sendMessage(text()
            .append(text("  Memory ", SourbyCraftColors.DIM))
            .append(text(BarUtil.bar(memPct, TPS_BAR_WIDTH), memColor))
            .append(text("  " + String.format(Locale.ROOT, "%.1f%%", memPct), memColor))
            .build());
    }

    // --- signal readers (defensive; the Bukkit API can throw very early at boot) ---

    private static double[] safeTps() {
        try {
            double[] t = Bukkit.getTPS();
            return new double[]{clampTps(t[0]), clampTps(t.length > 1 ? t[1] : t[0]), clampTps(t.length > 2 ? t[2] : t[0])};
        } catch (Throwable ignored) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }
    }

    private static double safeMspt() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

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

    /** Display-only tier derived inline from the TPS/MSPT readout — no sensor, no state. */
    private static Tier tierFor(double tps1m, double mspt) {
        if (Double.isNaN(tps1m)) return Tier.GREEN;
        boolean msptOk40 = Double.isNaN(mspt) || mspt < 40.0;
        boolean msptOk50 = Double.isNaN(mspt) || mspt < 50.0;
        if (tps1m >= 18.0 && msptOk40) return Tier.GREEN;
        if (tps1m >= 15.0 && msptOk50) return Tier.YELLOW;
        return Tier.RED;
    }

    // --- colour ladders: display-only (green >=19, yellow >=17, red <17) ---

    private static TextColor tpsColor(double t) {
        return t >= 19.0 ? SourbyCraftColors.SUCCESS
            : t >= 17.0 ? SourbyCraftColors.PRIMARY
            : SourbyCraftColors.DANGER;
    }

    private static TextColor msptColor(double m) {
        return m < 40.0 ? SourbyCraftColors.SUCCESS
            : m < 50.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    private static TextColor memColor(double p) {
        return p < 75.0 ? SourbyCraftColors.SUCCESS
            : p < 92.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    private static TextColor tierColor(Tier tier) {
        return switch (tier) {
            case GREEN  -> SourbyCraftColors.SUCCESS;
            case YELLOW -> SourbyCraftColors.PRIMARY;
            case RED    -> SourbyCraftColors.DANGER;
        };
    }
}
