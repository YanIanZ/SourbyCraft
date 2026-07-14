package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies a staged update and self-restarts (apply_mode=auto).
 *
 * <p><b>Jar swap is an atomic rename.</b> The staged jar is {@code Files.move}d over the LAUNCH
 * jar (the {@code -jar} argument of this JVM). POSIX rename replaces the directory entry while the
 * running JVM keeps its already-open file — the live process is never corrupted. On Windows the
 * rename fails on the lock; {@code auto_update/core.path} stays behind and
 * {@code SourbyBootstrap}'s phase-1 consumer performs the same swap on the NEXT boot, when the old
 * jar is no longer held open.
 *
 * <p><b>Restart modes</b> ({@code misc.auto_update.restart_mode}):
 * <ul>
 *   <li>{@code auto} — Pterodactyl/Pelican container detected (P_SERVER_UUID env) → {@code exit};
 *       otherwise {@code spigot}.</li>
 *   <li>{@code spigot} — Spigot's restart path (runs {@code restart-script} when configured, else
 *       a clean stop).</li>
 *   <li>{@code exit} — clean Folia shutdown, then a JVM shutdown hook flips the exit code to 70 so
 *       a panel's crash-detection restarts the server. The hook fires AFTER the region shutdown
 *       thread has saved worlds/players (Folia exits through the normal path first).</li>
 *   <li>{@code stop} — clean stop only; systemd/loop-script/panel brings the server back.</li>
 * </ul>
 */
public final class UpdateApplier {

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private UpdateApplier() {}

    /** Broadcast a countdown, swap the jar, write the applied marker, restart. Idempotent. */
    public static void scheduleApplyAndRestart(final String tag, final Path stagedJar,
                                               final int delaySeconds, final String restartMode) {
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            SourbyLogger.info("auto-updater: an apply/restart is already in flight, skipping duplicate for " + tag);
            return;
        }
        SourbyLogger.info("auto-updater: applying " + tag + " — restart in " + delaySeconds + "s (mode="
            + restartMode + ").");
        broadcast("SourbyCraft update " + tag + " downloaded — server restarts in " + delaySeconds + "s");
        // Follow-up warnings at 30s/10s (when inside the window), then the apply.
        long delayMs = Math.max(5, delaySeconds) * 1000L;
        var owner = MinecraftInternalPlugin.INSTANCE;
        if (delaySeconds > 30) {
            Bukkit.getAsyncScheduler().runDelayed(owner,
                t -> broadcast("Server restarts in 30s (SourbyCraft " + tag + ")"),
                delayMs - 30_000L, TimeUnit.MILLISECONDS);
        }
        if (delaySeconds > 10) {
            Bukkit.getAsyncScheduler().runDelayed(owner,
                t -> broadcast("Server restarts in 10s"),
                delayMs - 10_000L, TimeUnit.MILLISECONDS);
        }
        Bukkit.getAsyncScheduler().runDelayed(owner,
            t -> applyNow(tag, stagedJar, restartMode), delayMs, TimeUnit.MILLISECONDS);
    }

    private static void applyNow(final String tag, final Path stagedJar, final String restartMode) {
        try {
            Path launchJar = launchJarPath();
            boolean swapped = false;
            if (launchJar != null) {
                try {
                    // Atomic rename: the running JVM keeps its open (old) file; the path now
                    // serves the new jar for the next boot. REPLACE_EXISTING is required.
                    Files.move(stagedJar, launchJar, StandardCopyOption.REPLACE_EXISTING);
                    swapped = true;
                    writeApplied(tag);
                    // The stage is consumed — remove the pointer so the bootstrap fallback
                    // does not try to re-apply a jar that no longer exists.
                    Files.deleteIfExists(Path.of("auto_update", "core.path"));
                    SourbyLogger.info("auto-updater: swapped " + launchJar + " -> " + tag + ".");
                } catch (Exception e) {
                    SourbyLogger.warn("auto-updater: in-place swap failed (" + e.getMessage()
                        + ") — leaving auto_update/core.path for the bootstrap to swap on next boot.");
                }
            } else {
                SourbyLogger.warn("auto-updater: could not determine the launch jar path — "
                    + "leaving auto_update/core.path for the bootstrap to swap on next boot.");
            }
            if (!swapped) {
                // Bootstrap consumer needs the marker AFTER its swap; it writes applied.tag itself.
                writePending(tag);
            }
            restart(restartMode);
        } catch (Throwable t) {
            SourbyLogger.error("auto-updater: apply/restart failed", t);
            IN_FLIGHT.set(false);
        }
    }

    /** The {@code -jar <path>} argument of THIS JVM (the slim paperclip the panel launches). */
    static Path launchJarPath() {
        try {
            var cmd = ProcessHandle.current().info().arguments();
            if (cmd.isPresent()) {
                String[] args = cmd.get();
                for (int i = 0; i < args.length - 1; i++) {
                    if ("-jar".equals(args[i])) {
                        Path p = Path.of(args[i + 1]).toAbsolutePath().normalize();
                        return Files.isRegularFile(p) ? p : null;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void writeApplied(String tag) {
        try {
            Files.createDirectories(Path.of("auto_update"));
            Files.writeString(Path.of("auto_update", "applied.tag"), tag, StandardCharsets.UTF_8);
        } catch (Exception e) {
            SourbyLogger.warn("auto-updater: could not write applied.tag: " + e.getMessage());
        }
    }

    private static void writePending(String tag) {
        try {
            Files.createDirectories(Path.of("auto_update"));
            Files.writeString(Path.of("auto_update", "pending.tag"), tag, StandardCharsets.UTF_8);
        } catch (Exception e) {
            SourbyLogger.warn("auto-updater: could not write pending.tag: " + e.getMessage());
        }
    }

    private static void restart(String mode) {
        String m = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(m)) {
            m = System.getenv("P_SERVER_UUID") != null ? "exit" : "spigot";
        }
        SourbyLogger.info("auto-updater: restarting now (mode=" + m + ").");
        switch (m) {
            case "exit" -> {
                // Clean Folia shutdown first (worlds/players saved by the region shutdown thread);
                // the hook then flips the exit code so panel crash-detection restarts the server.
                Runtime.getRuntime().addShutdownHook(new Thread(() -> Runtime.getRuntime().halt(70),
                    "SourbyCraft-Update-Restart-Exit70"));
                Bukkit.shutdown();
            }
            case "stop" -> Bukkit.shutdown();
            default -> {
                try {
                    org.spigotmc.RestartCommand.restart();
                } catch (Throwable t) {
                    SourbyLogger.warn("auto-updater: spigot restart failed (" + t.getMessage()
                        + ") — falling back to a clean stop.");
                    Bukkit.shutdown();
                }
            }
        }
    }

    private static void broadcast(String message) {
        try {
            Bukkit.broadcast(Component.text(message, SourbyCraftColors.PRIMARY));
        } catch (Throwable ignored) {
            // Broadcast is best-effort (e.g. too early in boot).
        }
    }
}
