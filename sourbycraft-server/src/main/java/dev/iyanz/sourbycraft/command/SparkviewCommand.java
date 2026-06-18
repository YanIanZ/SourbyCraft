package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.perf.spark.SparkBridge;
import dev.iyanz.sourbycraft.util.BarUtil;
import me.lucko.spark.api.gc.GarbageCollector;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.Map;

import static net.kyori.adventure.text.Component.text;

/**
 * Comprehensive Spark viewer for SourbyCraft.
 *
 * <p>Pulls the full set of rolling statistics that the spark-api
 * exposes (TPS 5s/10s/1m/5m/15m, MSPT 10s/1m/5m mean+max+95th
 * percentile, process CPU + system CPU 10s/1m/15m, per-GC totals) and
 * renders them through the SourbyCraft hex palette + {@code ▰▱} bar
 * style so the readout is consistent with the rest of /tps /sys /ver.
 *
 * <p>This command gracefully degrades when spark-paper has not yet
 * registered its {@code SparkProvider} singleton at server-boot time:
 * a short "spark not ready" notice is printed instead and the panel
 * frame is still drawn for visual continuity.
 */
public class SparkviewCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);

    public SparkviewCommand(String name) {
        super(name);
        this.description = "Comprehensive Spark profiler view (TPS / MSPT / CPU / GC)";
        this.usageMessage = "/sparkview";
        this.setPermission("sourbycraft.command.sparkview");
        this.setAliases(java.util.List.of("sparkv", "spk"));
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
            .append(text("Spark Viewer", SourbyCraftColors.LABEL))
            .build());

        if (!SparkBridge.enabled()) {
            s.sendMessage(text("  Spark bridge disabled in sourbycraft.yml (spark.enabled = false).", SourbyCraftColors.DIM));
            s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
            return true;
        }
        if (!SparkBridge.isReady()) {
            s.sendMessage(text("  Spark not yet initialised. The spark plugin registers SparkProvider during", SourbyCraftColors.DIM));
            s.sendMessage(text("  plugin enable; retry this command a few seconds after server boot.", SourbyCraftColors.DIM));
            s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
            return true;
        }

        renderTps(s);
        renderMspt(s);
        renderCpu(s);
        renderGc(s);
        renderUptime(s);

        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }

    private static void renderTps(CommandSender s) {
        double[] win = SparkBridge.tpsWindows();
        if (win == null || win.length == 0) return;
        String[] labels = {"5s", "10s", "1m", "5m", "15m"};
        s.sendMessage(text("  TPS rolling windows:", SourbyCraftColors.LABEL));
        for (int i = 0; i < win.length && i < labels.length; i++) {
            double tps = win[i];
            TextColor color = tpsColor(tps);
            double pct = Math.clamp(tps / 20.0, 0.0, 1.0) * 100.0;
            s.sendMessage(text()
                .append(text("    " + pad(labels[i], 3) + " ", SourbyCraftColors.DIM))
                .append(text(BarUtil.bar(pct, 24), color))
                .append(text("  " + String.format(Locale.ROOT, "%.2f", tps), color))
                .build());
        }
    }

    private static void renderMspt(CommandSender s) {
        DoubleAverageInfo[] win = SparkBridge.msptWindows();
        if (win == null || win.length == 0) return;
        String[] labels = {"10s", "1m", "5m"};
        s.sendMessage(text("  MSPT (mean / max / 95th):", SourbyCraftColors.LABEL));
        for (int i = 0; i < win.length && i < labels.length; i++) {
            DoubleAverageInfo info = win[i];
            if (info == null) continue;
            TextColor color = msptColor(info.mean());
            s.sendMessage(text()
                .append(text("    " + pad(labels[i], 3) + " ", SourbyCraftColors.DIM))
                .append(text(String.format(Locale.ROOT, "%6.2fms", info.mean()), color))
                .append(text("   max ", SourbyCraftColors.DIM))
                .append(text(String.format(Locale.ROOT, "%6.2fms", info.max()), msptColor(info.max())))
                .append(text("   95th ", SourbyCraftColors.DIM))
                .append(text(String.format(Locale.ROOT, "%6.2fms", info.percentile95th()), msptColor(info.percentile95th())))
                .build());
        }
    }

    private static void renderCpu(CommandSender s) {
        double[] proc = SparkBridge.cpuProcess();
        double[] sys  = SparkBridge.cpuSystem();
        if ((proc == null || proc.length == 0) && (sys == null || sys.length == 0)) return;
        String[] labels = {"10s", "1m", "15m"};
        s.sendMessage(text("  CPU (process / system):", SourbyCraftColors.LABEL));
        for (int i = 0; i < labels.length; i++) {
            double p = (proc != null && i < proc.length) ? proc[i] * 100.0 : Double.NaN;
            double y = (sys  != null && i < sys.length)  ? sys[i]  * 100.0 : Double.NaN;
            Component procPart = Double.isNaN(p)
                ? text("    -", SourbyCraftColors.DIM)
                : text(String.format(Locale.ROOT, "%5.1f%%", p), cpuColor(p));
            Component sysPart  = Double.isNaN(y)
                ? text("    -", SourbyCraftColors.DIM)
                : text(String.format(Locale.ROOT, "%5.1f%%", y), cpuColor(y));
            s.sendMessage(text()
                .append(text("    " + pad(labels[i], 3) + " proc ", SourbyCraftColors.DIM))
                .append(procPart)
                .append(text("   sys ", SourbyCraftColors.DIM))
                .append(sysPart)
                .build());
        }
    }

    private static void renderGc(CommandSender s) {
        Map<String, GarbageCollector> gcs = SparkBridge.garbageCollectors();
        if (gcs == null || gcs.isEmpty()) return;
        s.sendMessage(text("  GC (collections / total time / avg):", SourbyCraftColors.LABEL));
        for (Map.Entry<String, GarbageCollector> entry : gcs.entrySet()) {
            GarbageCollector gc = entry.getValue();
            s.sendMessage(text()
                .append(text("    " + entry.getKey() + ": ", SourbyCraftColors.LABEL))
                .append(text(gc.totalCollections() + " coll", SourbyCraftColors.VALUE))
                .append(text("   ", SourbyCraftColors.DIM))
                .append(text(gc.totalTime() + "ms", SourbyCraftColors.VALUE))
                .append(text("   avg ", SourbyCraftColors.DIM))
                .append(text(String.format(Locale.ROOT, "%.2fms", gc.avgTime()), SourbyCraftColors.VALUE))
                .build());
        }
    }

    private static void renderUptime(CommandSender s) {
        long u = ManagementFactory.getRuntimeMXBean().getUptime();
        long h = u / 3600000, m = (u % 3600000) / 60000, sec = (u % 60000) / 1000;
        s.sendMessage(text()
            .append(text("  Uptime: ", SourbyCraftColors.LABEL))
            .append(text(h + "h " + m + "m " + sec + "s", SourbyCraftColors.VALUE))
            .build());
    }

    private static TextColor tpsColor(double t) {
        return t > 19.0 ? SourbyCraftColors.SUCCESS
            : t > 16.0 ? SourbyCraftColors.PRIMARY
            : t > 12.0 ? SourbyCraftColors.WARNING : SourbyCraftColors.DANGER;
    }

    private static TextColor msptColor(double m) {
        return m < 40.0 ? SourbyCraftColors.SUCCESS
            : m < 50.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    private static TextColor cpuColor(double p) {
        return p < 60.0 ? SourbyCraftColors.SUCCESS
            : p < 85.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }
}
