package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.RuntimeMetrics;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics;
import dev.iyanz.sourbycraft.perf.MetricsRuntime;
import dev.iyanz.sourbycraft.util.BarUtil;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;

import static net.kyori.adventure.text.Component.text;

/**
 * Custom /sys. Branded multi-line panel with hex-coloured bars for CPU
 * load + RAM usage, plus uptime, Java runtime, world chunk counts, and
 * a plugin roster. Layout matches the look of /ver / /tps so all three
 * commands read as a coherent SourbyCraft suite.
 */
public class SysCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);

    // CPU identity via OSHI is expensive to enumerate (WMI on Windows) — resolve it ONCE on a
    // virtual thread at registration; the command only ever reads these volatile snapshots.
    private static volatile String cpuName;
    private static volatile String cpuCores;
    private static final java.lang.management.OperatingSystemMXBean OS_BEAN =
        ManagementFactory.getOperatingSystemMXBean();

    public SysCommand(String name) {
        super(name);
        this.description = "Server specs";
        this.usageMessage = "/sys";
        this.setPermission("sourbycraft.command.sys");
        prewarmCpuIdentity();
    }

    private static void prewarmCpuIdentity() {
        if (cpuName != null) return;
        dev.iyanz.sourbycraft.util.VirtualExecutor.run(() -> {
            try {
                var cpu = new oshi.SystemInfo().getHardware().getProcessor();
                cpuCores = cpu.getPhysicalProcessorCount() + "c/" + cpu.getLogicalProcessorCount() + "t";
                cpuName = cpu.getProcessorIdentifier().getName().trim();
            } catch (Throwable ignored) {
                // OSHI absent/unsupported -> CPU identity line is simply omitted.
            }
        });
    }

    /**
     * Renders the full system panel: uptime, CPU identity/load, RAM, Java runtime, per-world
     * chunk counts, and a plugin roster including {@link PluginLoadDiagnostics#recent()} entries
     * for plugins that never reached the plugin manager.
     */
    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        final PerformanceSnapshot snapshot = MetricsRuntime.provider().snapshot();

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
            .append(text("System Info", SourbyCraftColors.LABEL))
            .build());

        long u = ManagementFactory.getRuntimeMXBean().getUptime();
        long d = u / 86400000, h = (u % 86400000) / 3600000, m = (u % 3600000) / 60000;
        s.sendMessage(text()
            .append(text("  Uptime: ", SourbyCraftColors.LABEL))
            .append(text(d + "d " + h + "h " + m + "m", SourbyCraftColors.VALUE))
            .build());

        // Non-blocking CPU readout: OSHI's getSystemCpuLoad(1000) SLEEPS 1s (a full region stall on
        // Folia) — use the JMX bean's sampled load instead and the prewarmed identity strings.
        String name = cpuName, cores = cpuCores;
        if (name != null) {
            s.sendMessage(text()
                .append(text("  CPU: ", SourbyCraftColors.LABEL))
                .append(text(name, SourbyCraftColors.VALUE))
                .append(text("  (" + cores + ")", SourbyCraftColors.DIM))
                .build());
        }
        if (OS_BEAN instanceof com.sun.management.OperatingSystemMXBean osb) {
            double load = osb.getCpuLoad() * 100;
            if (load >= 0) {
                s.sendMessage(text()
                    .append(text("  Load: ", SourbyCraftColors.LABEL))
                    .append(BarUtil.coloredBar(load, BarUtil.DEFAULT_WIDTH))
                    .build());
            }
        }

        final RuntimeMetrics runtime = snapshot.runtime();
        if (runtime.heapMaxBytes() > 0L && runtime.heapUsedBytes() >= 0L) {
            final double percent = 100.0 * runtime.heapUsedBytes() / runtime.heapMaxBytes();
            s.sendMessage(text()
                .append(text("  RAM:  ", SourbyCraftColors.LABEL))
                .append(BarUtil.ramBar(percent, BarUtil.DEFAULT_WIDTH))
                .append(text("  " + (runtime.heapUsedBytes() / 1048576) + " / "
                    + (runtime.heapMaxBytes() / 1048576) + " MB", SourbyCraftColors.VALUE))
                .build());
        } else {
            s.sendMessage(text("  RAM: unavailable", SourbyCraftColors.DIM));
        }

        s.sendMessage(text()
            .append(text("  Java: ", SourbyCraftColors.LABEL))
            .append(text(System.getProperty("java.version"), SourbyCraftColors.VALUE))
            .append(text("  (" + System.getProperty("java.vm.name") + ")", SourbyCraftColors.DIM))
            .build());

        renderPerformance(snapshot).forEach(s::sendMessage);

        Plugin[] pl = Bukkit.getPluginManager().getPlugins();
        int active = 0;
        int inactive = 0;
        for (Plugin p : pl) {
            if (p.isEnabled()) active++;
            else inactive++;
        }
        var failures = PluginLoadDiagnostics.recent();
        s.sendMessage(text()
            .append(text("  Plugins: ", SourbyCraftColors.HEADER))
            .append(text(active + " active", SourbyCraftColors.SUCCESS))
            .append(text("  /  ", SourbyCraftColors.DIM))
            .append(text(pl.length + " loaded", SourbyCraftColors.VALUE))
            .append(text("  /  ", SourbyCraftColors.DIM))
            .append(text((inactive + failures.size()) + " errored",
                (inactive + failures.size()) > 0 ? SourbyCraftColors.DANGER : SourbyCraftColors.DIM))
            .build());
        // Disabled-but-registered plugins (enable phase aborted post-load).
        for (Plugin p : pl) {
            if (p.isEnabled()) continue;
            s.sendMessage(text()
                .append(text("    ✗ ", SourbyCraftColors.DANGER))
                .append(text(p.getName(), SourbyCraftColors.VALUE))
                .append(text("  disabled", SourbyCraftColors.DIM))
                .build());
        }
        // Plugins that never reached the manager (load-time failure captured
        // by PluginLoadDiagnostics).
        for (PluginLoadDiagnostics.Entry entry : failures) {
            s.sendMessage(text()
                .append(text("    ✗ ", SourbyCraftColors.DANGER))
                .append(text(entry.pluginJar(), SourbyCraftColors.VALUE))
                .append(text("  " + entry.reason(), SourbyCraftColors.DIM))
                .build());
        }

        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }

    /**
     * The accurate, region-threading-aware performance block — the "hitungan baru" that goes beyond
     * the single TPS/MSPT pair: a distilled health score, per-region distribution (worst vs median vs
     * tail), region utilisation and CPU starvation, GC overhead (invisible to TPS/MSPT), and load.
     */
    /** Reads the provider once and renders the cached performance block. */
    public static List<Component> renderPerformance(final SourbyMetrics metrics) {
        final PerformanceSnapshot snapshot = metrics.snapshot();
        return renderPerformance(snapshot);
    }

    private static List<Component> renderPerformance(final PerformanceSnapshot snapshot) {
        final List<Component> lines = new ArrayList<>();
        final WindowMetrics recent = snapshot.window(MetricWindow.FIVE_SECONDS);
        final RuntimeMetrics runtime = snapshot.runtime();
        lines.add(text()
            .append(text("  Performance ", SourbyCraftColors.HEADER))
            .append(text("(region-threaded)", SourbyCraftColors.DIM))
            .build());

        if (hasHealthData(recent)) {
            final int health = healthScore(recent, runtime);
            final TextColor hColor = health >= 80 ? SourbyCraftColors.SUCCESS
                : health >= 50 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
            lines.add(text()
                .append(text("  Health ", SourbyCraftColors.LABEL))
                .append(text(BarUtil.bar(health, BarUtil.DEFAULT_WIDTH), hColor))
                .append(text("  " + health + "/100", hColor))
                .build());
        } else {
            lines.add(text("  Health unavailable", SourbyCraftColors.DIM));
        }

        if (TpsCommand.available(recent.worstTps())) {
            lines.add(text()
                .append(text("  TPS: ", SourbyCraftColors.LABEL))
                .append(text(TpsCommand.value(recent.worstTps(), 2),
                    TpsCommand.tpsColor(recent.worstTps(), snapshot.targetTps())))
                .append(text("  worst active", SourbyCraftColors.DIM))
                .build());
        }
        if (TpsCommand.available(recent.worstAverageMspt())) {
            lines.add(text()
                .append(text("  MSPT: ", SourbyCraftColors.LABEL))
                .append(text("worst " + TpsCommand.ms(recent.worstAverageMspt()),
                    TpsCommand.msptColor(recent.worstAverageMspt(), snapshot.targetTps())))
                .append(text("  median " + TpsCommand.ms(recent.medianAverageMspt()), SourbyCraftColors.VALUE))
                .append(text("  estimated p95 " + TpsCommand.ms(recent.estimatedP95Mspt()), SourbyCraftColors.DIM))
                .build());
        }

        if (snapshot.activeRegionCount() > 0) {
            lines.add(text()
                .append(text("  Regions: ", SourbyCraftColors.LABEL))
                .append(text(snapshot.activeRegionCount() + " active", SourbyCraftColors.VALUE))
                .append(text("  / " + snapshot.retainedGenerationCount() + " retained", SourbyCraftColors.DIM))
                .build());
        }
        if (TpsCommand.available(recent.busiestUtilisation())) {
            lines.add(text()
                .append(text("  Region load ", SourbyCraftColors.LABEL))
                .append(BarUtil.coloredBar(recent.busiestUtilisation(), BarUtil.DEFAULT_WIDTH))
                .append(text("  busiest " + fmtPct(recent.busiestUtilisation())
                    + " / avg " + fmtPct(recent.averageUtilisation()),
                    SourbyCraftColors.DIM))
                .build());
            if (recent.totalMissingCpuMs() > 1.0) {
                lines.add(text()
                    .append(text("  ! CPU starvation: ", SourbyCraftColors.DANGER))
                    .append(text(TpsCommand.ms(recent.totalMissingCpuMs()) + "/tick owed but not scheduled",
                        SourbyCraftColors.VALUE))
                    .append(text("  (raise threaded-regions.threads)", SourbyCraftColors.DIM))
                    .build());
            }
        }

        if (TpsCommand.available(runtime.gcTimePercent())) {
            final TextColor gcColor = runtime.gcTimePercent() < 3.0 ? SourbyCraftColors.SUCCESS
                : runtime.gcTimePercent() < 8.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
            lines.add(text()
                .append(text("  GC: ", SourbyCraftColors.LABEL))
                .append(text(fmt1(runtime.gcTimePercent()) + "% time", gcColor))
                .append(text("  " + fmt1(runtime.gcCollectionsPerMinute()) + "/min", SourbyCraftColors.VALUE))
                .append(text("  ~" + fmt1(runtime.averageGcPauseMs()) + "ms avg", SourbyCraftColors.DIM))
                .build());
        }

        if (runtime.heapMaxBytes() > 0L && runtime.heapUsedBytes() >= 0L) {
            final double heapPercent = 100.0 * runtime.heapUsedBytes() / runtime.heapMaxBytes();
            lines.add(text()
                .append(text("  Heap: ", SourbyCraftColors.LABEL))
                .append(text(fmt1(heapPercent) + "%", SourbyCraftColors.VALUE))
                .build());
        }
        if (TpsCommand.available(runtime.rssPercent())) {
            lines.add(text()
                .append(text("  RSS: ", SourbyCraftColors.LABEL))
                .append(text(fmt1(runtime.rssPercent()) + "%", SourbyCraftColors.VALUE))
                .build());
        }
        lines.add(TpsCommand.freshness(snapshot.freshness()));
        return List.copyOf(lines);
    }

    private static String fmtPct(double p) { return Double.isNaN(p) ? "?" : String.format(java.util.Locale.ROOT, "%.0f%%", p); }
    private static String fmt1(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }

    private static boolean hasHealthData(final WindowMetrics window) {
        return TpsCommand.available(window.busiestUtilisation())
            || TpsCommand.available(window.estimatedP95Mspt());
    }

    private static int healthScore(final WindowMetrics window, final RuntimeMetrics runtime) {
        double score = 100.0;
        if (TpsCommand.available(window.busiestUtilisation())) {
            score -= Math.max(0.0, window.busiestUtilisation() - 70.0);
        }
        if (TpsCommand.available(window.estimatedP95Mspt())) {
            score -= Math.min(30.0, Math.max(0.0, window.estimatedP95Mspt() - 40.0) * 0.5);
        }
        if (TpsCommand.available(runtime.gcTimePercent())) {
            score -= Math.min(20.0, runtime.gcTimePercent() * 2.0);
        }
        if (runtime.heapMaxBytes() > 0L && runtime.heapUsedBytes() >= 0L) {
            final double heapPercent = 100.0 * runtime.heapUsedBytes() / runtime.heapMaxBytes();
            score -= Math.min(20.0, Math.max(0.0, heapPercent - 80.0));
        }
        if (window.totalMissingCpuMs() > 5.0) {
            score -= Math.min(15.0, window.totalMissingCpuMs() - 5.0);
        }
        return (int)Math.round(Math.clamp(score, 0.0, 100.0));
    }
}
