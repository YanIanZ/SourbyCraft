package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.update.AutoUpdateSettings;
import dev.iyanz.sourbycraft.update.SourbyUpdater;
import dev.iyanz.sourbycraft.update.UpdateApplier;
import dev.iyanz.sourbycraft.update.UpdateRollbackGuard;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
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
 *       of the configured mode, also for a release whose earlier apply failed. This is the "update me
 *       NOW" button.</li>
 *   <li>{@code /update status} — running build, channel/mode config, staged/applied/apply-failed
 *       markers and the rollback-guard state.</li>
 *   <li>{@code /update resume} — lift a rollback hold ({@link UpdateRollbackGuard#clearHold()}).</li>
 * </ul>
 *
 * <p>A check shares {@link SourbyUpdater#tryCheck}'s guard with the scheduled checks: while one runs,
 * {@code /update} answers that a check is already running instead of starting a second download.
 *
 * <p>The check and the status read do network or disk I/O, so they run off-thread; the result goes back to the sender
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
        try {
            async.accept(() -> {
                Optional<SourbyUpdater.Outcome> outcome;
                try {
                    // The scheduled checks share this guard: never two downloads/stages at once.
                    outcome = updater.tryCheck(override);
                } catch (Throwable t) {
                    reply(s, text("Update check failed: " + t.getMessage(), SourbyCraftColors.DANGER));
                    return;
                }
                reply(s, outcome.map(UpdateCommand::describe).orElseGet(() -> text(
                    "An update check is already running — try again when it has finished (see console).",
                    SourbyCraftColors.DIM)));
            });
        } catch (RejectedExecutionException stopped) {
            s.sendMessage(text("Update check not started: the server's I/O workers are stopped.", SourbyCraftColors.DANGER));
        }
    }

    /** One line per outcome; public so the test can assert every outcome has one. */
    public static Component describe(SourbyUpdater.Outcome outcome) {
        return switch (outcome) {
            case DISABLED   -> text("Updater is disabled (updater.apply-mode=off).", SourbyCraftColors.DIM);
            case NO_RELEASE -> text("No matching release on this channel.", SourbyCraftColors.DIM);
            case UP_TO_DATE -> text("Already up to date (build " + BuildInfo.load().build() + ").", SourbyCraftColors.SUCCESS);
            case NOTIFIED   -> text("Update available — see console (not installed automatically; use /update apply to install).", SourbyCraftColors.PRIMARY);
            case STAGED     -> text("Update downloaded and verified — a restart does NOT install it; run /update apply.", SourbyCraftColors.SUCCESS);
            case APPLYING   -> text("Update downloaded — restart countdown started.", SourbyCraftColors.SUCCESS);
            case APPLY_FAILED -> text("Update downloaded but it cannot be installed here — nothing restarted (see console for the manual steps).", SourbyCraftColors.DANGER);
            case STAGE_FAILED -> text("Download/verify failed — see console.", SourbyCraftColors.DANGER);
            case HELD       -> text("Updater is HELD after a rollback — see console; /update resume lifts it.", SourbyCraftColors.DANGER);
            case RATE_LIMITED -> text("GitHub rate limit — check skipped until the backoff window ends (see console).", SourbyCraftColors.DIM);
            case MC_VERSION_MISMATCH -> text("Update available but for another Minecraft version — not staged (see console).", SourbyCraftColors.PRIMARY);
            case CLIP_INCOMPATIBLE -> text("Update available but its launcher cannot be handed over to — not staged (see console).", SourbyCraftColors.PRIMARY);
            case YANKED -> text("Every newer release is yanked by its signed manifest — nothing to install.", SourbyCraftColors.DIM);
            case ROLLOUT_DEFERRED -> text("A newer release is in staged rollout and has not reached this server yet.", SourbyCraftColors.DIM);
            case MANIFEST_REJECTED -> text("Newer release refused: its signed release manifest is missing or invalid (see console).", SourbyCraftColors.DANGER);
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

    /** The marker and state files are read off the command thread; the lines come back through the reply hop. */
    private void status(CommandSender s) {
        try {
            async.accept(() -> {
                List<Component> lines = statusLines();
                replyHop.accept(s, () -> lines.forEach(s::sendMessage));
            });
        } catch (RejectedExecutionException stopped) {
            s.sendMessage(text("Status unavailable: the server's I/O workers are stopped.", SourbyCraftColors.DANGER));
        }
    }

    private List<Component> statusLines() {
        List<Component> lines = new ArrayList<>();
        BuildInfo info = BuildInfo.load();
        lines.add(text()
            .append(text("Running: ", SourbyCraftColors.LABEL))
            .append(text(info.version() + " build " + info.build() + " (Minecraft " + info.mcVersion() + ")", SourbyCraftColors.VALUE))
            .build());
        lines.add(text()
            .append(text("Updater: ", SourbyCraftColors.LABEL))
            .append(text((AutoUpdateSettings.enabled ? "enabled" : "disabled")
                + " mode=" + AutoUpdateSettings.applyMode
                + " interval=" + AutoUpdateSettings.intervalSummary(runningInterval(), AutoUpdateSettings.checkIntervalMinutes)
                + " repo=" + AutoUpdateSettings.repo, SourbyCraftColors.VALUE))
            .build());
        appendMarker(lines, "Applied", Path.of("auto_update", "applied.tag"));
        appendMarker(lines, "Apply failed (checks only announce it; /update apply retries)",
            Path.of("auto_update", UpdateApplier.APPLY_FAILED_FILE));
        appendMarker(lines, "Apply failed (older build's marker)", Path.of("auto_update", UpdateApplier.LEGACY_PENDING_FILE));
        if (Files.isRegularFile(Path.of("auto_update", "core.path"))) {
            lines.add(text("A verified jar is staged (auto_update/core.path); a restart does not install it — /update apply does.",
                SourbyCraftColors.PRIMARY));
        }
        guard.state().ifPresent(st -> lines.add(text()
            .append(text("Rollback guard: ", SourbyCraftColors.LABEL))
            .append(text(st.phase() + " " + st.tag() + (st.note().isBlank() ? "" : " — " + st.note()),
                guard.isHeld() ? SourbyCraftColors.DANGER : SourbyCraftColors.VALUE))
            .build()));
        return lines;
    }

    /** The interval of the schedule built at boot, or -1 when none runs in this process. */
    private int runningInterval() {
        final SourbyUpdater updater = updaterSource.get();
        return updater == null ? -1 : updater.runningIntervalMinutes();
    }

    private static void appendMarker(List<Component> lines, String label, Path file) {
        try {
            if (Files.isRegularFile(file)) {
                lines.add(text()
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
