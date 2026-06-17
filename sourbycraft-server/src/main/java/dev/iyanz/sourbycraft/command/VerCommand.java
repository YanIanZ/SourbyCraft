package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import io.papermc.paper.ServerBuildInfo;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.lang.management.ManagementFactory;

import static net.kyori.adventure.text.Component.text;

public class VerCommand extends Command {
    public VerCommand(String n) {
        super(n);
        this.description = "Version info";
        this.usageMessage = "/ver";
        this.setPermission("sourbycraft.command.ver");
        this.setAliases(java.util.List.of("version", "about"));
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        s.sendMessage(text().append(text("SourbyCraft ", SourbyCraftColors.HEADER))
            .append(text(bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL), SourbyCraftColors.VALUE)));
        s.sendMessage(text().append(text("Minecraft: ", SourbyCraftColors.LABEL))
            .append(text(bi.minecraftVersionId() + " (" + bi.minecraftVersionName() + ")", SourbyCraftColors.VALUE)));
        s.sendMessage(text().append(text("API: ", SourbyCraftColors.LABEL))
            .append(text(Bukkit.getBukkitVersion(), SourbyCraftColors.VALUE)));
        long u = ManagementFactory.getRuntimeMXBean().getUptime();
        long d = u / 86400000, h = (u % 86400000) / 3600000, m = (u % 3600000) / 60000;
        s.sendMessage(text().append(text("Uptime: ", SourbyCraftColors.LABEL))
            .append(text(d + "d " + h + "h " + m + "m", SourbyCraftColors.VALUE)));
        s.sendMessage(text().append(text("Git: ", SourbyCraftColors.LABEL))
            .append(text(bi.gitBranch().orElse("?") + "@" + bi.gitCommit().orElse("?"), SourbyCraftColors.DIM)));
        return true;
    }
}
