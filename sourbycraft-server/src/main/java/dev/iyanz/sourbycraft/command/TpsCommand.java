package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;

import java.text.DecimalFormat;

import static net.kyori.adventure.text.Component.text;

public class TpsCommand extends Command {

    private static final ThreadLocal<DecimalFormat> ONE = ThreadLocal.withInitial(
        () -> new DecimalFormat("########0.0"));
    private static final ThreadLocal<DecimalFormat> TWO = ThreadLocal.withInitial(
        () -> new DecimalFormat("########0.00"));

    private boolean memWarned;

    public TpsCommand(String n) {
        super(n);
        this.description = "Server TPS and MSPT";
        this.usageMessage = "/tps [mem]";
        this.setPermission("bukkit.command.tps");
    }

    @Override
    public boolean execute(CommandSender s, String a, String[] args) {
        if (!testPermission(s)) return true;
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();

        long nanos = server.getAverageTickTimeNanos();
        double tps = nanos > 0 ? Math.min(20.0, 1_000_000_000.0 / nanos) : 20.0;
        double mspt = nanos / 1_000_000.0;
        double[] btps = Bukkit.getTPS();

        s.sendMessage(text()
            .append(text("TPS: ", SourbyCraftColors.HEADER))
            .append(text(TWO.get().format(tps), tpsColor(tps)))
            .append(text("  MSPT: ", SourbyCraftColors.LABEL))
            .append(text(String.format("%.1fms", mspt), msptColor(mspt)))
            .build());

        s.sendMessage(text()
            .append(text("  1m/5m/15m: ", SourbyCraftColors.DIM))
            .append(text(ONE.get().format(btps[0]), tpsColor(btps[0])))
            .append(text(" / ", SourbyCraftColors.DIM))
            .append(text(ONE.get().format(btps[1]), tpsColor(btps[1])))
            .append(text(" / ", SourbyCraftColors.DIM))
            .append(text(ONE.get().format(btps[2]), tpsColor(btps[2])))
            .build());

        if (args.length > 0 && args[0].equals("mem") && s.hasPermission("bukkit.command.tpsmemory")) {
            Runtime r = Runtime.getRuntime();
            long used = (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
            long total = r.totalMemory() / (1024 * 1024);
            long max = r.maxMemory() / (1024 * 1024);
            s.sendMessage(text()
                .append(text("Memory: ", SourbyCraftColors.LABEL))
                .append(text(used + "/" + total + " MB (Max: " + max + " MB)", SourbyCraftColors.SUCCESS))
                .build());
            if (!memWarned) {
                s.sendMessage(text("Modern GC memory usage fluctuates — high usage is normal.", SourbyCraftColors.DIM));
                memWarned = true;
            }
        }
        return true;
    }

    private static TextColor tpsColor(double t) {
        return t > 19.0 ? SourbyCraftColors.SUCCESS : t > 16.0 ? SourbyCraftColors.PRIMARY
            : t > 12.0 ? SourbyCraftColors.WARNING : SourbyCraftColors.DANGER;
    }

    private static TextColor msptColor(double m) {
        return m < 40.0 ? SourbyCraftColors.SUCCESS : m < 50.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }
}
