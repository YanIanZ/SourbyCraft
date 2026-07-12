package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.update.SourbyUpdater;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import me.earthme.luminol.config.modules.misc.AutoUpdateConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static net.kyori.adventure.text.Component.text;

/**
 * {@code /update} — manual driver for the auto-updater.
 *
 * <ul>
 *   <li>{@code /update} / {@code /update check} — run one check now with the CONFIGURED apply
 *       mode (so an auto server downloads+applies, a notify server just reports).</li>
 *   <li>{@code /update apply} — force a full download + verify + swap + self-restart regardless
 *       of the configured mode. This is the "update me NOW" button.</li>
 *   <li>{@code /update status} — running build, channel/mode config, staged/applied markers.</li>
 * </ul>
 *
 * <p>The check does network + disk I/O, so it always runs on a virtual thread; results are
 * reported back with thread-safe sendMessage.
 */
public class UpdateCommand extends Command {

    public UpdateCommand(String name) {
        super(name);
        this.description = "Check for / apply SourbyCraft updates";
        this.usageMessage = "/update [check|apply|status]";
        this.setPermission("sourbycraft.command.update");
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "check";
        switch (sub) {
            case "status" -> status(s);
            case "apply", "force", "now" -> runCheck(s, SourbyUpdater.ApplyMode.AUTO);
            case "check" -> runCheck(s, null);
            default -> s.sendMessage(text("Usage: " + this.usageMessage, SourbyCraftColors.DANGER));
        }
        return true;
    }

    private void runCheck(CommandSender s, SourbyUpdater.@org.jetbrains.annotations.Nullable ApplyMode override) {
        SourbyUpdater updater = AutoUpdateConfig.updater();
        if (updater == null) {
            s.sendMessage(text("Updater unavailable (config module not loaded).", SourbyCraftColors.DANGER));
            return;
        }
        s.sendMessage(text(override == SourbyUpdater.ApplyMode.AUTO
            ? "Checking + applying latest release..." : "Checking for updates...", SourbyCraftColors.LABEL));
        VirtualExecutor.run(() -> {
            SourbyUpdater.Outcome outcome;
            try {
                outcome = updater.check(override);
            } catch (Throwable t) {
                s.sendMessage(text("Update check failed: " + t.getMessage(), SourbyCraftColors.DANGER));
                return;
            }
            s.sendMessage(switch (outcome) {
                case DISABLED   -> text("Updater is disabled (misc.auto_update.apply_mode=off).", SourbyCraftColors.DIM);
                case NO_RELEASE -> text("No matching release on this channel.", SourbyCraftColors.DIM);
                case UP_TO_DATE -> text("Already up to date (build " + BuildInfo.load().build() + ").", SourbyCraftColors.SUCCESS);
                case NOTIFIED   -> text("Update available — see console (apply_mode=notify; use /update apply to install).", SourbyCraftColors.PRIMARY);
                case STAGED     -> text("Update staged — applies on the next restart.", SourbyCraftColors.SUCCESS);
                case APPLYING   -> text("Update downloaded — restart countdown started.", SourbyCraftColors.SUCCESS);
                case STAGE_FAILED -> text("Download/verify failed — see console.", SourbyCraftColors.DANGER);
            });
        });
    }

    private void status(CommandSender s) {
        BuildInfo info = BuildInfo.load();
        s.sendMessage(text()
            .append(text("Running: ", SourbyCraftColors.LABEL))
            .append(text(info.version() + " build " + info.build(), SourbyCraftColors.VALUE))
            .build());
        s.sendMessage(text()
            .append(text("Updater: ", SourbyCraftColors.LABEL))
            .append(text((AutoUpdateConfig.enabled ? "enabled" : "disabled")
                + " mode=" + AutoUpdateConfig.applyMode
                + " interval=" + AutoUpdateConfig.checkIntervalMinutes + "m"
                + " repo=" + AutoUpdateConfig.repo, SourbyCraftColors.VALUE))
            .build());
        appendMarker(s, "Applied", Path.of("auto_update", "applied.tag"));
        appendMarker(s, "Pending", Path.of("auto_update", "pending.tag"));
        if (Files.isRegularFile(Path.of("auto_update", "core.path"))) {
            s.sendMessage(text("A staged jar is waiting for the next restart.", SourbyCraftColors.PRIMARY));
        }
    }

    private static void appendMarker(CommandSender s, String label, Path file) {
        try {
            if (Files.isRegularFile(file)) {
                s.sendMessage(text()
                    .append(text(label + ": ", SourbyCraftColors.LABEL))
                    .append(text(Files.readString(file).trim(), SourbyCraftColors.VALUE))
                    .build());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("check", "apply", "status");
        }
        return List.of();
    }
}
