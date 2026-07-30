package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics;
import dev.iyanz.sourbycraft.util.BarUtil;
import net.kyori.adventure.text.format.TextColor;
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

        Runtime rt = Runtime.getRuntime();
        long maxM = rt.maxMemory();
        long usedM = rt.totalMemory() - rt.freeMemory();
        double pct = maxM == Long.MAX_VALUE ? (double) usedM / rt.totalMemory() * 100 : (double) usedM / maxM * 100;
        String lbl = maxM == Long.MAX_VALUE ? BarUtil.INFINITY : (maxM / 1048576) + " MB";
        s.sendMessage(text()
            .append(text("  RAM:  ", SourbyCraftColors.LABEL))
            .append(BarUtil.ramBar(pct, BarUtil.DEFAULT_WIDTH))
            .append(text("  " + (usedM / 1048576) + " / " + lbl, SourbyCraftColors.VALUE))
            .build());

        s.sendMessage(text()
            .append(text("  Java: ", SourbyCraftColors.LABEL))
            .append(text(System.getProperty("java.version"), SourbyCraftColors.VALUE))
            .append(text("  (" + System.getProperty("java.vm.name") + ")", SourbyCraftColors.DIM))
            .build());

        renderPerformance(s);

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
    private static void renderPerformance(CommandSender s) {
        final dev.iyanz.sourbycraft.perf.PerfMetrics.Snapshot m;
        try {
            m = dev.iyanz.sourbycraft.perf.PerfMetrics.snapshot();
        } catch (Throwable ignored) {
            return; // never break /sys over the perf readout
        }

        s.sendMessage(text()
            .append(text("  Performance ", SourbyCraftColors.HEADER))
            .append(text("(region-threaded)", SourbyCraftColors.DIM))
            .build());

        // Health score — one honest number distilled from utilisation + tail MSPT + GC + heap.
        final int health = m.healthScore();
        final TextColor hColor = health >= 80 ? SourbyCraftColors.SUCCESS
            : health >= 50 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
        s.sendMessage(text()
            .append(text("  Health ", SourbyCraftColors.LABEL))
            .append(text(BarUtil.bar(health, BarUtil.DEFAULT_WIDTH), hColor))
            .append(text("  " + health + "/100", hColor))
            .build());

        // TPS (scheduler) + worst-region MSPT with its p95 tail — the pair that tells the real story.
        if (!Double.isNaN(m.tps1m())) {
            s.sendMessage(text()
                .append(text("  TPS: ", SourbyCraftColors.LABEL))
                .append(text(fmt2(m.tps1m()), tpsColor(m.tps1m())))
                .append(text("  (scheduler rate)", SourbyCraftColors.DIM))
                .build());
        }
        if (!Double.isNaN(m.worstRegionMspt())) {
            s.sendMessage(text()
                .append(text("  MSPT: ", SourbyCraftColors.LABEL))
                .append(text("worst " + fmtMs(m.worstRegionMspt()), msptColor(m.worstRegionMspt())))
                .append(text("  median " + fmtMs(m.medianRegionMspt()), SourbyCraftColors.VALUE))
                .append(text("  p95 " + fmtMs(m.worstRegionP95Mspt()), SourbyCraftColors.DIM))
                .build());
        }

        // Region spread + utilisation — one bad region vs a systemically loaded box look identical on /tps.
        if (m.regionCount() > 0 && !Double.isNaN(m.maxUtilisation())) {
            s.sendMessage(text()
                .append(text("  Regions: ", SourbyCraftColors.LABEL))
                .append(text(m.regionCount() + " ticking", SourbyCraftColors.VALUE))
                .append(text("  on " + m.tickThreads() + " threads", SourbyCraftColors.DIM))
                .build());
            s.sendMessage(text()
                .append(text("  Region load ", SourbyCraftColors.LABEL))
                .append(BarUtil.coloredBar(m.maxUtilisation(), BarUtil.DEFAULT_WIDTH))
                .append(text("  busiest " + fmtPct(m.maxUtilisation()) + " / avg " + fmtPct(m.avgUtilisation()),
                    SourbyCraftColors.DIM))
                .build());
            // CPU starvation: a region owed CPU it didn't get = too few tick threads for the live regions.
            if (m.totalMissingCpuMs() > 1.0) {
                s.sendMessage(text()
                    .append(text("  ! CPU starvation: ", SourbyCraftColors.DANGER))
                    .append(text(fmtMs(m.totalMissingCpuMs()) + "/tick owed but not scheduled", SourbyCraftColors.VALUE))
                    .append(text("  (raise threaded-regions.threads)", SourbyCraftColors.DIM))
                    .build());
            }
        }

        // GC overhead — the lag source that TPS and MSPT never show.
        final dev.iyanz.sourbycraft.perf.GcTracker.Gc gc = m.gc();
        if (gc != null && gc.hasData()) {
            final TextColor gcColor = gc.gcTimePercent() < 3.0 ? SourbyCraftColors.SUCCESS
                : gc.gcTimePercent() < 8.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
            s.sendMessage(text()
                .append(text("  GC: ", SourbyCraftColors.LABEL))
                .append(text(fmt1(gc.gcTimePercent()) + "% time", gcColor))
                .append(text("  " + fmt1(gc.collectionsPerMin()) + "/min", SourbyCraftColors.VALUE))
                .append(text("  ~" + fmt1(gc.avgPauseMs()) + "ms avg", SourbyCraftColors.DIM))
                .build());
        }

        // Container RSS (if in a container) — the real number a panel OOM-kills on, not heap %.
        if (!Double.isNaN(m.rssPercent())) {
            final TextColor rColor = m.rssPercent() < 80.0 ? SourbyCraftColors.SUCCESS
                : m.rssPercent() < 92.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
            s.sendMessage(text()
                .append(text("  Container RSS ", SourbyCraftColors.LABEL))
                .append(BarUtil.ramBar(m.rssPercent(), BarUtil.DEFAULT_WIDTH))
                .append(text("  " + fmt1(m.rssPercent()) + "%", rColor))
                .build());
        }

        // Load line — players / chunks / entities.
        final StringBuilder load = new StringBuilder();
        if (m.players() >= 0) load.append(m.players()).append(" players");
        if (m.loadedChunks() >= 0) load.append(load.length() > 0 ? "  " : "").append(m.loadedChunks()).append(" chunks");
        if (m.entities() >= 0) load.append(load.length() > 0 ? "  " : "").append(m.entities()).append(" entities");
        if (load.length() > 0) {
            s.sendMessage(text()
                .append(text("  Load: ", SourbyCraftColors.LABEL))
                .append(text(load.toString(), SourbyCraftColors.VALUE))
                .build());
        }
    }

    private static String fmtMs(double ms) {
        if (Double.isNaN(ms)) return "?";
        if (ms >= 10.0) return String.format(java.util.Locale.ROOT, "%.1fms", ms);
        if (ms >= 0.1) return String.format(java.util.Locale.ROOT, "%.2fms", ms);
        return String.format(java.util.Locale.ROOT, "%.3fms", ms);
    }

    private static String fmtPct(double p) { return Double.isNaN(p) ? "?" : String.format(java.util.Locale.ROOT, "%.0f%%", p); }
    private static String fmt1(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }
    private static String fmt2(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }

    private static TextColor tpsColor(double t) {
        return t >= 19.0 ? SourbyCraftColors.SUCCESS : t >= 17.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    private static TextColor msptColor(double m) {
        return m < 40.0 ? SourbyCraftColors.SUCCESS : m < 50.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }
}
