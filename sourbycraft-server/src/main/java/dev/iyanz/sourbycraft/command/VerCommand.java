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

/** Build identity and runtime information in the shared command panel. */
public class VerCommand extends Command {

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

        s.sendMessage(UiPanel.header("Version"));
        s.sendMessage(UiPanel.row("Release", brand.releaseIdentity()));
        s.sendMessage(UiPanel.row("Built (Asia/Jakarta)", buildDate.isEmpty() ? "unavailable" : buildDate));
        s.sendMessage(line("Minecraft", bi.minecraftVersionId() + "  (" + bi.minecraftVersionName() + ")"));
        // Transition §14 names the build identity an operator should be able to read back:
        // SourbyCraft, Minecraft, Aurora Engine, build, Java, commit, channel. The first two and
        // the commit were already here; an engine, a runtime and a channel that only exist in
        // the boot log cannot be checked by whoever is looking at a server months later.
        s.sendMessage(line("Engine", brand.engineName() + " Engine"));
        // Build 47: whether the runtime came up whole, and whether legacy plugins are admitted,
        // are properties of this run an operator should not have to dig out of the boot log.
        s.sendMessage(line("Aurora runtime", dev.iyanz.sourbycraft.core.AuroraRuntime.state().name()));
        s.sendMessage(line("Aurora Bridge", dev.iyanz.sourbycraft.bridge.AuroraBridge.runtimeMode().name().toLowerCase(java.util.Locale.ROOT)));
        s.sendMessage(line("Bukkit API", Bukkit.getBukkitVersion()));
        s.sendMessage(line("Java", Runtime.version().toString()));
        final String channel = dev.iyanz.sourbycraft.update.AutoUpdateSettings.channel;
        s.sendMessage(line("Update channel",
            channel == null || channel.isBlank() ? "auto-detected from this build" : channel));

        long u = ManagementFactory.getRuntimeMXBean().getUptime();
        long d = u / 86400000, h = (u % 86400000) / 3600000, m = (u % 3600000) / 60000;
        s.sendMessage(line("Uptime", d + "d " + h + "h " + m + "m"));

        s.sendMessage(text()
            .append(text("  Git: ", SourbyCraftColors.LABEL))
            .append(text(bi.gitBranch().orElse("?") + "@" + bi.gitCommit().orElse("?"), SourbyCraftColors.DIM))
            .build());
        s.sendMessage(UiPanel.actions(java.util.List.of("/aurora", "/sourbycraft config")));
        s.sendMessage(UiPanel.footer());
        return true;
    }

    private static Component line(String label, String value) {
        return UiPanel.row(label, value);
    }
}
