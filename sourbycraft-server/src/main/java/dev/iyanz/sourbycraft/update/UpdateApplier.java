package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies a staged update and self-restarts (apply-mode=auto).
 *
 * <p><b>Jar swap is an atomic rename, after the previous jar is kept.</b> The launch jar (the
 * {@code -jar} argument of this JVM) is first copied to {@code <jar>.prev}
 * ({@link UpdateRollbackGuard#keepPrevious}); only then is the staged jar {@code Files.move}d over
 * it. POSIX rename replaces the directory entry while the running JVM keeps its already-open file
 * — the live process is never corrupted. The guard records the apply so the next boot can restore
 * {@code .prev} if it never reaches ready (audit SEC-3). If the {@code .prev} copy fails (disk
 * full, permissions) the swap does NOT happen and no restart is scheduled: an update without a way
 * back is not applied automatically. On Windows the rename fails on the lock;
 * {@code auto_update/core.path} stays behind for a launcher-side swap on the NEXT boot.
 *
 * <p><b>Restart modes</b> ({@code updater.restart-mode}):
 * <ul>
 *   <li>{@code auto} — Pterodactyl/Pelican container detected (P_SERVER_UUID env) → {@code exit};
 *       otherwise {@code spigot}.</li>
 *   <li>{@code spigot} — Spigot's restart path (runs {@code restart-script} when configured, else
 *       a clean stop).</li>
 *   <li>{@code exit} — clean shutdown, then exit code 70 so a panel's crash-detection restarts the
 *       server. The code is delivered through the server's own exit path:
 *       {@code MinecraftServer.abnormalExit} is set and {@code DedicatedServer.onServerExit()} calls
 *       {@code System.exit(abnormalExit ? 70 : 0)} once {@code stopServer()} has saved worlds and
 *       players and the region shutdown thread has finished. No shutdown hook, no
 *       {@code Runtime.halt}: nothing is cut short (audit SEC-9).</li>
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
                Path prev;
                try {
                    prev = UpdateRollbackGuard.SERVER.keepPrevious(launchJar);
                } catch (Exception e) {
                    SourbyLogger.error("auto-updater: could not keep the previous jar as "
                        + UpdateRollbackGuard.prevPathFor(launchJar) + " (" + e.getMessage()
                        + ") — NOT swapping " + tag + " and NOT restarting; the staged jar stays at " + stagedJar + ".");
                    IN_FLIGHT.set(false);
                    return;
                }
                try {
                    // Atomic rename: the running JVM keeps its open (old) file; the path now
                    // serves the new jar for the next boot. REPLACE_EXISTING is required.
                    Files.move(stagedJar, launchJar, StandardCopyOption.REPLACE_EXISTING);
                    swapped = true;
                    writeApplied(tag);
                    try {
                        UpdateRollbackGuard.SERVER.recordApplied(tag, launchJar, prev, Instant.now());
                    } catch (Exception e) {
                        SourbyLogger.warn("auto-updater: swapped but could not record the apply for rollback ("
                            + e.getMessage() + "); the next boot will not auto-restore " + prev + ".");
                    }
                    // The stage is consumed — remove the pointer so the bootstrap fallback
                    // does not try to re-apply a jar that no longer exists.
                    Files.deleteIfExists(Path.of("auto_update", "core.path"));
                    SourbyLogger.info("auto-updater: swapped " + launchJar + " -> " + tag
                        + " (previous build kept at " + prev + ").");
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

    /** How the server is brought down after a swap. */
    enum RestartPlan {
        /** Spigot's restart path ({@code restart-script}), falling back to a clean stop. */
        SPIGOT,
        /** Clean shutdown; the process exits 0 and something outside restarts it. */
        STOP,
        /** Clean shutdown; the process exits 70 through {@code onServerExit()} so a panel restarts it. */
        EXIT_70;

        /** Resolve the configured mode; {@code auto} picks {@link #EXIT_70} inside a Pterodactyl/Pelican container. */
        static RestartPlan resolve(String mode, boolean pterodactylDetected) {
            String m = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
            if ("auto".equals(m)) {
                return pterodactylDetected ? EXIT_70 : SPIGOT;
            }
            return switch (m) {
                case "exit" -> EXIT_70;
                case "stop" -> STOP;
                default -> SPIGOT;
            };
        }
    }

    private static void restart(String mode) {
        RestartPlan plan = RestartPlan.resolve(mode, System.getenv("P_SERVER_UUID") != null);
        SourbyLogger.info("auto-updater: restarting now (plan=" + plan + ").");
        switch (plan) {
            case EXIT_70 -> {
                // The exit code rides the normal shutdown path: DedicatedServer.onServerExit() calls
                // System.exit(abnormalExit ? 70 : 0) after stopServer() has saved worlds/players.
                requestExitCode70();
                Bukkit.shutdown();
            }
            case STOP -> Bukkit.shutdown();
            case SPIGOT -> {
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

    /** Make the server's own exit path return 70 once the clean shutdown has completed. */
    private static void requestExitCode70() {
        net.minecraft.server.MinecraftServer.getServer().abnormalExit = true;
    }

    private static void broadcast(String message) {
        try {
            Bukkit.broadcast(Component.text(message, SourbyCraftColors.PRIMARY));
        } catch (Throwable ignored) {
            // Broadcast is best-effort (e.g. too early in boot).
        }
    }
}
