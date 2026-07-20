package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.util.BarUtil;
import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static net.kyori.adventure.text.Component.text;

/**
 * Custom /ver. Boxed banner with SourbyCraft branding header and a
 * matching {@link BarUtil#FILLED} divider so the layout reads as one
 * coherent SourbyCraft panel rather than the bare Paper /version dump.
 */
public class VerCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);
    // GMT+7 day-name format matching the GH release title convention
    // (e.g. "Friday, 19 June 2026 10:45"). Sourced from the buildTimestamp in
    // META-INF/sourbycraft-build.properties so /ver shows when the jar was
    // built, not when the server happened to boot.
    private static final DateTimeFormatter BUILD_DATE_FMT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy HH:mm", Locale.ENGLISH);
    private static final ZoneId GMT_PLUS_7 = ZoneId.of("Asia/Jakarta");

    public VerCommand(String n) {
        super(n);
        this.description = "Version info";
        this.usageMessage = "/ver";
        this.setPermission("sourbycraft.command.ver");
        this.setAliases(java.util.List.of("version", "about"));
    }

    /** Renders the branded version panel: SourbyCraft version/build date, MC/Bukkit API, uptime, git ref. */
    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        BuildInfo brand = BuildInfo.load();

        String buildDate;
        try {
            Instant inst = Instant.parse(brand.buildTimestamp());
            buildDate = ZonedDateTime.ofInstant(inst, GMT_PLUS_7).format(BUILD_DATE_FMT);
        } catch (Exception ignored) {
            buildDate = "";
        }

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        var headerLine = text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
            .append(text(brand.displayVersion(), SourbyCraftColors.VALUE));
        if (!buildDate.isEmpty()) {
            headerLine.append(text("  " + buildDate, SourbyCraftColors.DIM));
        }
        s.sendMessage(headerLine.build());

        s.sendMessage(line("Minecraft", bi.minecraftVersionId() + "  (" + bi.minecraftVersionName() + ")"));
        s.sendMessage(line("Bukkit API", Bukkit.getBukkitVersion()));

        long u = ManagementFactory.getRuntimeMXBean().getUptime();
        long d = u / 86400000, h = (u % 86400000) / 3600000, m = (u % 3600000) / 60000;
        s.sendMessage(line("Uptime", d + "d " + h + "h " + m + "m"));

        s.sendMessage(text()
            .append(text("  Git: ", SourbyCraftColors.LABEL))
            .append(text(bi.gitBranch().orElse("?") + "@" + bi.gitCommit().orElse("?"), SourbyCraftColors.DIM))
            .build());
        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }

    private static Component line(String label, String value) {
        return text()
            .append(text("  " + label + ": ", SourbyCraftColors.LABEL))
            .append(text(value, SourbyCraftColors.VALUE))
            .build();
    }
}
