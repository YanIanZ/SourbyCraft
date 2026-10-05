package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.bridge.AuroraBridge;
import dev.iyanz.sourbycraft.core.AuroraRuntime;
import dev.iyanz.sourbycraft.core.SourbyCraftBootstrap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import java.util.List;
import java.util.Locale;
import static net.kyori.adventure.text.Component.text;

/** Operator hub and Aurora service diagnostics; reload is explicit and never implicit in a view. */
public class SourbyCraftCommand extends Command {
    private static final List<String> SUBCOMMANDS = List.of("status", "config", "reload", "version", "help");

    public SourbyCraftCommand(String name) {
        super(name);
        description = "SourbyCraft and Aurora operator information";
        usageMessage = "/" + name + " [status|config|reload|version|help]";
        setPermission("sourbycraft.command.admin");
    }

    @Override public boolean execute(CommandSender sender, String alias, String[] args) {
        if (!testPermission(sender)) return true;
        String sub = args.length == 0 ? (getName().equals("aurora") ? "status" : "help") : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1) sub = "invalid";
        switch (sub) {
            case "version", "ver" -> {
                sender.sendMessage(UiPanel.header("Build identity"));
                BuildInfo info = BuildInfo.load();
                sender.sendMessage(UiPanel.row("Release", info.releaseIdentity()));
                sender.sendMessage(UiPanel.row("Minecraft / channel", info.mcVersion() + " / " + info.version()));
                sender.sendMessage(UiPanel.actions(List.of("/ver", "/aurora")));
                sender.sendMessage(UiPanel.footer());
            }
            case "status" -> status(sender);
            case "config" -> config(sender);
            case "reload" -> {
                sender.sendMessage(UiPanel.header("Configuration reload"));
                var report = SourbyCraftConfig.reloadDetailed();
                sender.sendMessage(text("  " + (report.successful() ? "COMPLETE" : "PARTIAL / FAILED"),
                    report.successful() ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER));
                report.lines().forEach(line -> sender.sendMessage(UiPanel.hint(line)));
                sender.sendMessage(UiPanel.actions(List.of("/sourbycraft config", "/aurora status")));
                sender.sendMessage(UiPanel.footer());
            }
            case "help" -> {
                sender.sendMessage(UiPanel.header("Operator hub"));
                sender.sendMessage(UiPanel.row("Release", BuildInfo.load().releaseIdentity()));
                sender.sendMessage(UiPanel.section("Inspect"));
                sender.sendMessage(UiPanel.hint("/sys — system summary · /spec — hardware & JVM"));
                sender.sendMessage(UiPanel.hint("/perf — measurements · /tps and /mspt — region tick health"));
                sender.sendMessage(UiPanel.hint("/plugins or /pl — searchable plugin roster and failure details"));
                sender.sendMessage(UiPanel.section("Configure"));
                sender.sendMessage(UiPanel.hint("/sourbycraft config — files and setting lifecycles"));
                sender.sendMessage(UiPanel.hint("/sourbycraft reload — read disk; restart-only settings remain pending"));
                sender.sendMessage(UiPanel.actions(List.of("/aurora", "/sourbycraft config", "/plugins")));
                sender.sendMessage(UiPanel.footer());
            }
            default -> sender.sendMessage(UiPanel.hint(usageMessage));
        }
        return true;
    }

    private static void status(CommandSender sender) {
        sender.sendMessage(UiPanel.header("Aurora / Services"));
        sender.sendMessage(UiPanel.row("Service state", AuroraRuntime.state().name()));
        sender.sendMessage(UiPanel.row("Bridge mode (this run)", AuroraBridge.runtimeMode().name()));
        sender.sendMessage(UiPanel.hint("Service state describes bootstrap; it does not certify plugin safety or tick health."));
        var stages = SourbyCraftBootstrap.bootStages();
        sender.sendMessage(UiPanel.section("Boot stages · " + stages.size() + " recorded"));
        for (var stage : stages) sender.sendMessage(text("  " + (stage.successful() ? "OK    " : "FAIL  "),
            stage.successful() ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER)
            .append(text(stage.name() + " · " + stage.millis() + "ms", SourbyCraftColors.VALUE)));
        sender.sendMessage(UiPanel.actions(List.of("/perf", "/perf governor", "/perf awf", "/plugins filter failed")));
        sender.sendMessage(UiPanel.footer());
    }

    private static void config(CommandSender sender) {
        sender.sendMessage(UiPanel.header("Configuration"));
        sender.sendMessage(UiPanel.section("Files"));
        sender.sendMessage(UiPanel.row("Utility & UI", "sourbycraft_config/sourbycraft_global_config.toml"));
        sender.sendMessage(UiPanel.row("Aurora services", "sourbycraft_config/aurora.toml (wins over legacy Aurora keys)"));
        sender.sendMessage(UiPanel.row("Region engine", "config/canvas-server.yml + config/canvas-worlds.yml"));
        sender.sendMessage(UiPanel.row("Security limits", "sourbycraft-security.yml"));
        var config = SourbyCraftConfig.aurora();
        sender.sendMessage(UiPanel.section("LIVE · after explicit reload"));
        sender.sendMessage(UiPanel.row("Lane CPU sampling / network counters", config.diagnostics().laneSampling() + " / " + config.network().counters()));
        sender.sendMessage(UiPanel.row("Async pathfinding (experimental)", Boolean.toString(config.entity().asyncPathfinding())));
        sender.sendMessage(UiPanel.row("Bridge route / quarantine threshold", config.bridge().syncRoute().name() + " / " + config.bridge().quarantineAfter()));
        sender.sendMessage(UiPanel.row("Plugin page size", Integer.toString(Math.clamp(SourbyCraftConfig.cfgInt("ui.plugins-page-size", 12), 1, 40))));
        sender.sendMessage(UiPanel.section("RESTART_REQUIRED · configured values"));
        sender.sendMessage(UiPanel.row("Aurora CPU budget", config.cpu().cores() == 0 ? "auto (JVM-visible processors)" : Integer.toString(config.cpu().cores())));
        sender.sendMessage(UiPanel.row("Bridge configured / active", config.bridge().mode().name() + " / " + AuroraBridge.runtimeMode().name()));
        sender.sendMessage(UiPanel.row("Console style", SourbyCraftConfig.cfgGet("ui.console-style", "auto") + " (JVM property may override)"));
        sender.sendMessage(UiPanel.hint("Scheduler budgets, AWF, startup provisioning, MOTD and updater interval also require restart."));
        sender.sendMessage(UiPanel.row("Pending tracked changes", SourbyCraftConfig.pendingRestartKeys().isEmpty() ? "none" : String.join(", ", SourbyCraftConfig.pendingRestartKeys())));
        sender.sendMessage(UiPanel.hint("Views use loaded values. Boot/reload preserves existing utility and Aurora TOML files."));
        sender.sendMessage(UiPanel.actions(List.of("/sourbycraft reload", "/aurora status")));
        sender.sendMessage(UiPanel.footer());
    }

    @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!testPermissionSilent(sender) || args.length != 1) return List.of();
        return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
