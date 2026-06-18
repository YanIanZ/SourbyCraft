package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.BarUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

import static net.kyori.adventure.text.Component.text;

/**
 * Custom /sys. Branded multi-line panel with hex-coloured bars for CPU
 * load + RAM usage, plus uptime, Java runtime, world chunk counts, and
 * a plugin roster. Layout matches the look of /ver / /tps so all three
 * commands read as a coherent SourbyCraft suite.
 */
public class SysCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);

    public SysCommand(String name) {
        super(name);
        this.description = "Server specs";
        this.usageMessage = "/sys";
        this.setPermission("sourbycraft.command.sys");
    }

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

        try {
            oshi.SystemInfo si = new oshi.SystemInfo();
            var cpu = si.getHardware().getProcessor();
            double load = cpu.getSystemCpuLoad(1000) * 100;
            s.sendMessage(text()
                .append(text("  CPU: ", SourbyCraftColors.LABEL))
                .append(text(cpu.getProcessorIdentifier().getName().trim(), SourbyCraftColors.VALUE))
                .append(text("  (" + cpu.getPhysicalProcessorCount() + "c/" + cpu.getLogicalProcessorCount() + "t)", SourbyCraftColors.DIM))
                .build());
            s.sendMessage(text()
                .append(text("  Load: ", SourbyCraftColors.LABEL))
                .append(BarUtil.coloredBar(load, BarUtil.DEFAULT_WIDTH))
                .build());
        } catch (NoClassDefFoundError ignored) {}

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

        int rec = Math.max(3, (Runtime.getRuntime().availableProcessors() * 3) / Math.max(1, Bukkit.getWorlds().size()));
        for (World w : Bukkit.getWorlds()) {
            s.sendMessage(text()
                .append(text("  " + w.getName() + ": ", SourbyCraftColors.LABEL))
                .append(text(w.getLoadedChunks().length + " chunks", SourbyCraftColors.VALUE))
                .append(text("  ~" + rec + " threads", SourbyCraftColors.DIM))
                .build());
        }

        var pl = Bukkit.getPluginManager().getPlugins();
        s.sendMessage(text()
            .append(text("  Plugins (" + pl.length + "): ", SourbyCraftColors.HEADER))
            .append(text(Arrays.stream(pl).map(p -> p.getName()).reduce((x, y) -> x + ", " + y).orElse("none"), SourbyCraftColors.VALUE))
            .build());

        s.sendMessage(text()
            .append(text("  SWM: ", SourbyCraftColors.LABEL))
            .append(text(SourbyCraftConfig.swmEnabled ? "enabled" : "disabled",
                SourbyCraftConfig.swmEnabled ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DIM))
            .build());

        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }
}
