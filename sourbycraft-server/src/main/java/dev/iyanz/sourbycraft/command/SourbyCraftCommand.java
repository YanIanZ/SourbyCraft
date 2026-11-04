package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.bridge.AuroraBridge;
import dev.iyanz.sourbycraft.core.AuroraRuntime;
import dev.iyanz.sourbycraft.core.SourbyCraftBootstrap;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
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
                sender.sendMessage(UiPanel.row("Release", info.releaseIdentity(),
                    SourbyCraftColors.releaseStage(info)));
                sender.sendMessage(UiPanel.row("Minecraft / channel", info.mcVersion() + " / " + info.version()));
                sender.sendMessage(UiPanel.actions(List.of("/ver", "/aurora")));
                sender.sendMessage(UiPanel.footer());
            }
            case "status" -> status(sender);
            case "config" -> config(sender);
            case "reload" -> reload(sender);
            case "help" -> {
                sender.sendMessage(UiPanel.header("Operator hub"));
                final BuildInfo identity = BuildInfo.load();
                sender.sendMessage(UiPanel.row("Release", identity.releaseIdentity(),
                    SourbyCraftColors.releaseStage(identity)));
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

    /**
     * {@code /sourbycraft reload}: reads and applies the files on a SourbyCraft I/O virtual thread
     * ({@link VirtualExecutor}), never on the tick thread that dispatched the command, and delivers the
     * report through {@link SourbyReply}. Refused while the server is stopping or failed to start
     * ({@link AuroraRuntime#stopping()}) and when the I/O workers are stopped or at capacity.
     *
     * <p>The engine part of the reload runs on that I/O thread as well: its post-load step schedules a
     * player-info resend on each player's own scheduler and sends its broadcast lines to permitted players
     * directly from that thread, and each loaded level's engine configuration is replaced from it (no single
     * tick thread owns every player or level, so the dispatching thread did not own them either). A sender
     * that collects output only while the command runs (RCON) can miss the report.</p>
     */
    private static void reload(CommandSender sender) {
        if (AuroraRuntime.stopping()) {
            sender.sendMessage(text("Reload not started: the server is stopping or failed to start.", SourbyCraftColors.DANGER));
            return;
        }
        sender.sendMessage(text("Reloading configuration...", SourbyCraftColors.LABEL));
        try {
            VirtualExecutor.run(() -> {
                try {
                    var report = SourbyCraftConfig.reloadDetailed();
                    SourbyReply.run(sender, () -> reloadReport(sender, report));
                } catch (Throwable failure) {
                    SourbyLogger.error("/sourbycraft reload failed", failure);
                    SourbyReply.run(sender, () -> sender.sendMessage(text("Reload failed: " + failure
                        + " (see console); some settings may already be applied.", SourbyCraftColors.DANGER)));
                }
            });
        } catch (RejectedExecutionException refused) {
            sender.sendMessage(text("Reload not started: the server's I/O workers are stopped or busy ("
                + refused.getMessage() + ").", SourbyCraftColors.DANGER));
        }
    }

    private static void reloadReport(CommandSender sender, SourbyCraftConfig.ReloadReport report) {
        sender.sendMessage(UiPanel.header("Configuration reload"));
        sender.sendMessage(text("  " + (report.successful() ? "COMPLETE" : "PARTIAL / FAILED"),
            report.successful() ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER));
        report.lines().forEach(line -> sender.sendMessage(UiPanel.hint(line)));
        sender.sendMessage(UiPanel.actions(List.of("/sourbycraft config", "/aurora status")));
        sender.sendMessage(UiPanel.footer());
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
        sender.sendMessage(UiPanel.row("Console, players, messages, plugins, updater", "sourbycraft_config/sourbycraft_global_config.yml"));
        sender.sendMessage(UiPanel.row("Aurora services", "sourbycraft_config/aurora.yml (wins over Aurora keys left in the global file)"));
        sender.sendMessage(UiPanel.row("Region engine", "config/aurora-server.yml + config/aurora-worlds.yml (Build 49 names; canvas-*.yml migrated once)"));
        sender.sendMessage(UiPanel.row("Security limits", "sourbycraft-security.yml"));
        var config = SourbyCraftConfig.aurora();
        sender.sendMessage(UiPanel.section("LIVE · after explicit reload"));
        sender.sendMessage(UiPanel.row("Lane CPU sampling / network counters", config.diagnostics().laneSampling() + " / " + config.network().counters()));
        sender.sendMessage(UiPanel.row("Async pathfinding (experimental)", Boolean.toString(config.entity().asyncPathfinding())));
        sender.sendMessage(UiPanel.row("Bridge route / quarantine threshold", config.bridge().syncRoute().name() + " / " + config.bridge().quarantineAfter()));
        sender.sendMessage(UiPanel.row("Plugin page size", Integer.toString(Math.clamp(SourbyCraftConfig.cfgInt(dev.iyanz.sourbycraft.config.UtilityKeys.PLUGINS_PAGE_SIZE.path(), 12), 1, 40))));
        sender.sendMessage(UiPanel.section("RESTART_REQUIRED · configured values"));
        sender.sendMessage(UiPanel.row("Aurora CPU budget", config.cpu().cores() == 0 ? "auto (JVM-visible processors)" : Integer.toString(config.cpu().cores())));
        sender.sendMessage(UiPanel.row("Bridge configured / active", config.bridge().mode().name() + " / " + AuroraBridge.runtimeMode().name()));
        sender.sendMessage(UiPanel.row("Bridge thread checks configured / effective", config.bridge().threadChecks().configValue() + " / "
            + dev.iyanz.sourbycraft.guards.ThreadChecks.describe()));
        sender.sendMessage(UiPanel.row("Console style", SourbyCraftConfig.cfgGet(dev.iyanz.sourbycraft.config.UtilityKeys.CONSOLE_STYLE.path(), "auto") + " (JVM property may override)"));
        sender.sendMessage(UiPanel.hint("Scheduler budgets, AWF, startup provisioning, MOTD and updater interval also require restart;"
            + " a change to them since boot is listed under Pending tracked changes."));
        sender.sendMessage(UiPanel.row("Pending tracked changes", SourbyCraftConfig.pendingRestartKeys().isEmpty() ? "none" : String.join(", ", SourbyCraftConfig.pendingRestartKeys())));
        sender.sendMessage(UiPanel.hint("Views use loaded values. A reload rewrites an engine file only to add or remove a key (logged);"
            + " SourbyCraft files are never rewritten by a reload. Build 49 migrated old layouts once (copies kept as *.migrated)."));
        sender.sendMessage(UiPanel.actions(List.of("/sourbycraft reload", "/aurora status")));
        sender.sendMessage(UiPanel.footer());
    }

    @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!testPermissionSilent(sender) || args.length != 1) return List.of();
        return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
