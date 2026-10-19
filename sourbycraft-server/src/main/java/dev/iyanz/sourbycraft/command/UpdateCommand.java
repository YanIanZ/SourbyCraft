package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.update.AutoUpdateSettings;
import dev.iyanz.sourbycraft.update.SourbyUpdater;
import dev.iyanz.sourbycraft.update.UpdateRollbackGuard;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.kyori.adventure.text.Component.text;

/**
 * {@code /update} — manual driver for the auto-updater.
 *
 * <ul>
 *   <li>{@code /update} / {@code /update check} — run one check now with the CONFIGURED apply
 *       mode (so an auto server downloads+applies, a notify server just reports).</li>
 *   <li>{@code /update apply} — force a full download + verify + swap + self-restart regardless
 *       of the configured mode. This is the "update me NOW" button.</li>
 *   <li>{@code /update status} — running build, channel/mode config, staged/applied markers and
 *       the rollback-guard state.</li>
 *   <li>{@code /update resume} — lift a rollback hold ({@link UpdateRollbackGuard#clearHold()}).</li>
 * </ul>
 *
 * <p>The check does network + disk I/O, so it runs off-thread; the result goes back to the sender
 * through {@link SourbyReply#run}, the Folia-correct hop for that sender (a command block's reply
 * writes block-entity state and must run on the block's region thread — a direct
 * {@code sendMessage} from the worker throws there). The constructor seams exist for the test
 * that proves the hop is used.
 */
public class UpdateCommand extends Command {

    private final Supplier<SourbyUpdater> updaterSource;
    private final Consumer<Runnable> async;
    private final BiConsumer<CommandSender, Runnable> replyHop;
    private final UpdateRollbackGuard guard;

    public UpdateCommand(String name) {
        this(name, AutoUpdateSettings::updater, VirtualExecutor::run, SourbyReply::run, UpdateRollbackGuard.SERVER);
    }

    /** Injectable form: the updater, the off-thread executor, the reply hop and the rollback guard. */
    public UpdateCommand(String name, Supplier<SourbyUpdater> updaterSource, Consumer<Runnable> async,
                         BiConsumer<CommandSender, Runnable> replyHop, UpdateRollbackGuard guard) {
        super(name);
        this.description = "Check for / apply SourbyCraft updates";
        this.usageMessage = "/update [check|apply|status|resume]";
        this.setPermission("sourbycraft.command.update");
        this.updaterSource = updaterSource;
        this.async = async;
        this.replyHop = replyHop;
        this.guard = guard;
    }

    /** Dispatches {@code check}/{@code apply}/{@code status}/{@code resume} (default {@code check}). */
    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "check";
        switch (sub) {
            case "status" -> status(s);
            case "resume" -> resume(s);
            case "apply", "force", "now" -> runCheck(s, SourbyUpdater.ApplyMode.AUTO);
            case "check" -> runCheck(s, null);
            default -> s.sendMessage(text("Usage: " + this.usageMessage, SourbyCraftColors.DANGER));
        }
        return true;
    }

    private void runCheck(CommandSender s, SourbyUpdater.@org.jetbrains.annotations.Nullable ApplyMode override) {
        SourbyUpdater updater = updaterSource.get();
        if (updater == null) {
            s.sendMessage(text("Updater unavailable (config module not loaded).", SourbyCraftColors.DANGER));
            return;
        }
        s.sendMessage(text(override == SourbyUpdater.ApplyMode.AUTO
            ? "Checking + applying latest release..." : "Checking for updates...", SourbyCraftColors.LABEL));
        async.accept(() -> {
            SourbyUpdater.Outcome outcome;
            try {
                outcome = updater.check(override);
            } catch (Throwable t) {
                reply(s, text("Update check failed: " + t.getMessage(), SourbyCraftColors.DANGER));
                return;
            }
            reply(s, describe(outcome));
        });
    }

    /** One line per outcome; public so the test can assert every outcome has one. */
    public static Component describe(SourbyUpdater.Outcome outcome) {
        return switch (outcome) {
            case DISABLED   -> text("Updater is disabled (misc.auto_update.apply_mode=off).", SourbyCraftColors.DIM);
            case NO_RELEASE -> text("No matching release on this channel.", SourbyCraftColors.DIM);
            case UP_TO_DATE -> text("Already up to date (build " + BuildInfo.load().build() + ").", SourbyCraftColors.SUCCESS);
            case NOTIFIED   -> text("Update available — see console (apply_mode=notify; use /update apply to install).", SourbyCraftColors.PRIMARY);
            case STAGED     -> text("Update staged — applies on the next restart.", SourbyCraftColors.SUCCESS);
            case APPLYING   -> text("Update downloaded — restart countdown started.", SourbyCraftColors.SUCCESS);
            case STAGE_FAILED -> text("Download/verify failed — see console.", SourbyCraftColors.DANGER);
            case HELD       -> text("Updater is HELD after a rollback — see console; /update resume lifts it.", SourbyCraftColors.DANGER);
            case RATE_LIMITED -> text("GitHub rate limit — check skipped until the backoff window ends (see console).", SourbyCraftColors.DIM);
            case MC_VERSION_MISMATCH -> text("Update available but for another Minecraft version — not staged (see console).", SourbyCraftColors.PRIMARY);
        };
    }

    /** Deliver an off-thread result on the thread that owns the sender. */
    private void reply(CommandSender s, Component message) {
        replyHop.accept(s, () -> s.sendMessage(message));
    }

    private void resume(CommandSender s) {
        try {
            boolean held = guard.isHeld();
            boolean removed = guard.clearHold();
            if (held && removed) {
                s.sendMessage(text("Rollback hold lifted; the next check runs normally. auto_update/applied.tag still "
                    + "blocks the rolled-back tag — delete it to retry that exact release.", SourbyCraftColors.SUCCESS));
            } else if (removed) {
                s.sendMessage(text("No hold was active; cleared the apply record.", SourbyCraftColors.DIM));
            } else {
                s.sendMessage(text("Nothing to resume (no rollback state on disk).", SourbyCraftColors.DIM));
            }
        } catch (Exception e) {
            s.sendMessage(text("Could not clear the hold: " + e.getMessage(), SourbyCraftColors.DANGER));
        }
    }

    private void status(CommandSender s) {
        BuildInfo info = BuildInfo.load();
        s.sendMessage(text()
            .append(text("Running: ", SourbyCraftColors.LABEL))
            .append(text(info.version() + " build " + info.build() + " (Minecraft " + info.mcVersion() + ")", SourbyCraftColors.VALUE))
            .build());
        s.sendMessage(text()
            .append(text("Updater: ", SourbyCraftColors.LABEL))
            .append(text((AutoUpdateSettings.enabled ? "enabled" : "disabled")
                + " mode=" + AutoUpdateSettings.applyMode
                + " interval=" + AutoUpdateSettings.checkIntervalMinutes + "m"
                + " repo=" + AutoUpdateSettings.repo, SourbyCraftColors.VALUE))
            .build());
        appendMarker(s, "Applied", Path.of("auto_update", "applied.tag"));
        appendMarker(s, "Pending", Path.of("auto_update", "pending.tag"));
        if (Files.isRegularFile(Path.of("auto_update", "core.path"))) {
            s.sendMessage(text("A staged jar is waiting for the next restart.", SourbyCraftColors.PRIMARY));
        }
        guard.state().ifPresent(st -> s.sendMessage(text()
            .append(text("Rollback guard: ", SourbyCraftColors.LABEL))
            .append(text(st.phase() + " " + st.tag() + (st.note().isBlank() ? "" : " — " + st.note()),
                guard.isHeld() ? SourbyCraftColors.DANGER : SourbyCraftColors.VALUE))
            .build()));
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

    /** Suggests the sub-commands for the first argument only. */
    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("check", "apply", "status", "resume");
        }
        return List.of();
    }
}
