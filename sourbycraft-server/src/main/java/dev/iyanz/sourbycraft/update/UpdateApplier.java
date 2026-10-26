package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies a staged update and self-restarts (apply-mode=auto, or {@code /update apply}).
 *
 * <p><b>Preflight before any broadcast.</b> The launch jar (the {@code -jar} argument of this JVM)
 * must be known, its directory writable and its file store must have room for the kept
 * {@code <jar>.prev} plus one temporary copy of the new jar. When any of that fails nothing is
 * announced, nothing is swapped and the server keeps running.
 *
 * <p><b>Atomic swap, after the previous jar is kept.</b> The launch jar is first copied to
 * {@code <jar>.prev} ({@link UpdateRollbackGuard#keepPrevious}). The staged jar is then copied to a
 * temporary file in the launch jar's own directory, forced to disk and its SHA-256 checked again
 * against the release digest; only those verified bytes are renamed over the launch jar with
 * {@code ATOMIC_MOVE}. Because the rename never crosses a file system (a reference Docker image
 * stages on the {@code /data} volume and launches {@code /app/server.jar}), the launch path holds
 * either the complete old jar or the complete new one. The running JVM keeps its already-open file.
 * The guard records the apply <em>before</em> the rename so the next boot can restore {@code .prev}
 * if it never reaches ready (audit SEC-3). If the {@code .prev} copy, the temporary copy, its digest
 * or that record fails, or the file system cannot rename atomically, the launch jar is left as it
 * was; a record already written for this apply is replaced by the earlier one.
 *
 * <p><b>A failed apply never restarts.</b> A preflight failure or any swap result other than
 * {@link SwapResult#SWAPPED} logs one ERROR with the manual steps, writes
 * {@code auto_update/}{@value #APPLY_FAILED_FILE} with the tag, and keeps the server running. While
 * that marker names the newest release, scheduled checks only notify about it
 * ({@link SourbyUpdater#check}); {@code /update apply} tries again. No code in this server swaps a
 * staged jar at boot: {@code auto_update/core.path} is only a pointer for operators.
 *
 * <p><b>Restart modes</b> ({@code updater.restart-mode}):
 * <ul>
 *   <li>{@code auto} — Pterodactyl/Pelican container detected (P_SERVER_UUID env) → {@code exit};
 *       otherwise {@code stop}.</li>
 *   <li>{@code spigot} — treated as {@code stop}: Spigot's restart command (and with it
 *       {@code restart-script}) is disabled under region threading, so a clean stop is all it could
 *       ever do here.</li>
 *   <li>{@code exit} — clean shutdown, then exit code 70 so a panel's crash-detection restarts the
 *       server. The code is delivered through the server's own exit path:
 *       {@code MinecraftServer.abnormalExit} is set and {@code DedicatedServer.onServerExit()} calls
 *       {@code System.exit(abnormalExit ? 70 : 0)} once {@code stopServer()} has saved worlds and
 *       players and the region shutdown thread has finished. No shutdown hook, no
 *       {@code Runtime.halt}: nothing is cut short (audit SEC-9).</li>
 *   <li>{@code stop} — clean stop only (exit 0); systemd/loop-script/panel brings the server back.
 *       Without such a supervisor the server stays down after the update.</li>
 * </ul>
 */
public final class UpdateApplier {

    /** Marker naming the release whose apply failed; later checks only notify about that tag. */
    public static final String APPLY_FAILED_FILE = "apply-failed.tag";
    /** Marker an older build wrote when its swap failed; read as {@link #APPLY_FAILED_FILE}, never written. */
    public static final String LEGACY_PENDING_FILE = "pending.tag";

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private UpdateApplier() {}

    /** True while an apply is counting down or swapping (it ends in a restart, or is released on failure). */
    static boolean inFlight() {
        return IN_FLIGHT.get();
    }

    /**
     * Preflight the swap, then broadcast a countdown, swap the jar, write the applied marker and
     * restart. Returns {@code false} without broadcasting when another apply is in flight or the
     * preflight fails (that failure is logged and recorded in {@value #APPLY_FAILED_FILE}).
     *
     * @param expectedSha256 the release asset digest the staged jar was verified against; the copy
     *                       renamed over the launch jar must still match it
     */
    public static boolean scheduleApplyAndRestart(final String tag, final Path stagedJar, final String expectedSha256,
                                                  final int delaySeconds, final String restartMode) {
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            SourbyLogger.info("auto-updater: an apply/restart is already in flight, skipping duplicate for " + tag);
            return false;
        }
        final Path launchJar = launchJarPath();
        final String problem = preflight(launchJar, stagedJar);
        if (problem != null) {
            applyFailed(tag, stagedJar, launchJar, problem);
            IN_FLIGHT.set(false);
            return false;
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
        // The copies and the SHA-256 streaming hashes run here on the updater async callback,
        // never on a region, tick or main-server thread.
        Bukkit.getAsyncScheduler().runDelayed(owner,
            t -> applyNow(tag, stagedJar, expectedSha256, launchJar, restartMode), delayMs, TimeUnit.MILLISECONDS);
        return true;
    }

    private static void applyNow(final String tag, final Path stagedJar, final String expectedSha256,
                                 final Path launchJar, final String restartMode) {
        try {
            SwapResult result = swapInPlace(tag, stagedJar, expectedSha256, launchJar, UpdateRollbackGuard.SERVER);
            if (result != SwapResult.SWAPPED) {
                broadcast("SourbyCraft update " + tag + " could not be installed — the server is NOT restarting.");
                applyFailed(tag, stagedJar, launchJar, result == SwapResult.ABORTED
                    ? "the swap was aborted before the launch jar changed (see the error above)"
                    : "the rename over the launch jar failed (see the error above)");
                IN_FLIGHT.set(false);
                return;
            }
            writeApplied(tag);
            // The stage is consumed: drop its pointers, the copy and any failure marker.
            deleteQuietly(Path.of("auto_update", "core.path"));
            deleteQuietly(stagedJar.resolveSibling("latest.path"));
            deleteQuietly(Path.of("auto_update", APPLY_FAILED_FILE));
            deleteQuietly(Path.of("auto_update", LEGACY_PENDING_FILE));
            deleteQuietly(stagedJar);
            restart(restartMode);
        } catch (Throwable t) {
            SourbyLogger.error("auto-updater: apply/restart failed", t);
            IN_FLIGHT.set(false);
        }
    }

    /**
     * Why the swap cannot work here, or {@code null} when it can: the launch jar must be known and
     * not be the staged jar itself (a successful apply deletes the staged copy), the staged jar
     * present, the launch directory writable, and its file store must hold the kept {@code .prev}
     * plus the temporary copy of the new jar.
     */
    static @Nullable String preflight(@Nullable Path launchJar, Path stagedJar) {
        if (launchJar == null) {
            return "the launch jar (the -jar argument of this JVM) could not be determined";
        }
        if (launchJar.toAbsolutePath().normalize().equals(stagedJar.toAbsolutePath().normalize())) {
            return "the server was started from the staged jar " + stagedJar + " itself";
        }
        if (!Files.isRegularFile(stagedJar)) {
            return "the staged jar " + stagedJar + " is missing";
        }
        Path dir = launchJar.toAbsolutePath().getParent();
        if (dir == null || !Files.isWritable(dir)) {
            return "the launch jar's directory " + dir + " is not writable";
        }
        try {
            long needed = Files.size(launchJar) + Files.size(stagedJar);
            long usable = Files.getFileStore(dir).getUsableSpace();
            if (usable < needed) {
                return dir + " has " + usable + " bytes free; keeping the previous jar and copying the new one needs "
                    + needed;
            }
        } catch (IOException e) {
            return "the free space beside " + launchJar + " could not be checked (" + e.getMessage() + ")";
        }
        return null;
    }

    /** What {@link #swapInPlace} did. */
    enum SwapResult {
        /** The new jar is at the launch path and the guard tracks its first boot. */
        SWAPPED,
        /** Nothing changed at the launch path and no new guard record remains; do not restart. */
        ABORTED,
        /** The rename failed after the apply was recorded; the launch jar is still the old build; do not restart. */
        SWAP_FAILED
    }

    /**
     * Keep the previous jar, copy and re-verify the staged jar beside the launch jar, record the
     * apply and rename the verified copy over the launch jar. Package-private and free of Bukkit so
     * the ordering is unit-tested.
     */
    static SwapResult swapInPlace(final String tag, final Path stagedJar, final String expectedSha256,
                                  final Path launchJar, final UpdateRollbackGuard guard) {
        Path prev;
        try {
            prev = guard.keepPrevious(launchJar);
        } catch (Exception e) {
            SourbyLogger.error("auto-updater: could not keep the previous jar as "
                + UpdateRollbackGuard.prevPathFor(launchJar) + " (" + e.getMessage()
                + ") — NOT swapping " + tag + " and NOT restarting; the staged jar stays at " + stagedJar + ".");
            return SwapResult.ABORTED;
        }
        Path verified = null;
        try {
            try {
                verified = copyBeside(stagedJar, launchJar, expectedSha256);
            } catch (Exception e) {
                SourbyLogger.error("auto-updater: could not place a verified copy of " + stagedJar + " beside "
                    + launchJar + " (" + e.getMessage() + ") — NOT swapping " + tag + " and NOT restarting.");
                return SwapResult.ABORTED;
            }
            // Record the apply BEFORE the new jar takes the launch path: a restart must never run a
            // new jar the guard does not track. A crash between this record and the rename leaves
            // APPLIED over the old jar, which the next boot confirms (or restores to the same bytes).
            final Optional<UpdateRollbackGuard.State> before;
            try {
                before = guard.currentState();
                guard.recordApplied(tag, launchJar, prev, Instant.now());
            } catch (Exception e) {
                SourbyLogger.error("auto-updater: could not record the apply for rollback (" + e.getMessage()
                    + ") — NOT swapping " + tag + " and NOT restarting; the staged jar stays at " + stagedJar + ".");
                return SwapResult.ABORTED;
            }
            try {
                // Same directory, so one rename(2): the path serves either the whole old jar or the
                // whole new one. The running JVM keeps its open (old) file.
                Files.move(verified, launchJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                verified = null;
            } catch (Exception e) {
                SwapResult result = e instanceof AtomicMoveNotSupportedException ? SwapResult.ABORTED : SwapResult.SWAP_FAILED;
                try {
                    guard.restoreState(before);
                } catch (Exception undo) {
                    result = SwapResult.SWAP_FAILED;
                    SourbyLogger.warn("auto-updater: could not take back the apply record (" + undo.getMessage()
                        + "); the next boot confirms or restores the unchanged old jar.");
                }
                SourbyLogger.error("auto-updater: "
                    + (e instanceof AtomicMoveNotSupportedException
                        ? "the file system of " + launchJar + " cannot rename atomically"
                        : "renaming the verified copy over " + launchJar + " failed (" + e.getMessage() + ")")
                    + " — the old jar is unchanged; NOT restarting.");
                return result;
            }
        } finally {
            if (verified != null) deleteQuietly(verified);
        }
        SourbyLogger.info("auto-updater: swapped " + launchJar + " -> " + tag
            + " (previous build kept at " + prev + ").");
        return SwapResult.SWAPPED;
    }

    /**
     * Copy {@code stagedJar} to a temporary file in the launch jar's directory, force it to disk,
     * give it the launch jar's POSIX mode and check its SHA-256; returns the verified copy.
     */
    private static Path copyBeside(final Path stagedJar, final Path launchJar, final String expectedSha256)
            throws IOException {
        Path dir = launchJar.toAbsolutePath().getParent();
        Path copy = Files.createTempFile(dir, launchJar.getFileName() + ".update-", ".part");
        boolean done = false;
        try {
            try (InputStream in = Files.newInputStream(stagedJar)) {
                Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
            }
            try (FileChannel channel = FileChannel.open(copy, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            String actual = sha256(copy);
            if (expectedSha256 == null || !actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("the copy's SHA-256 " + actual + " differs from the release digest " + expectedSha256);
            }
            UpdateRollbackGuard.copyPermissions(launchJar, copy);
            done = true;
            return copy;
        } finally {
            if (!done) deleteQuietly(copy);
        }
    }

    private static String sha256(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
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

    /** One ERROR with the manual steps, and the marker that keeps later checks from retrying on their own. */
    private static void applyFailed(final String tag, final Path stagedJar, final @Nullable Path launchJar,
                                    final String reason) {
        SourbyLogger.error("auto-updater: NOT applying " + tag + ": " + reason + ". The server keeps running "
            + "its current jar and is NOT restarted. To install it by hand: stop the server, copy " + stagedJar
            + " over " + (launchJar != null ? launchJar : "the jar your start command launches")
            + " (keep a copy of the old jar), start it again. Or fix the cause and run /update apply. Until then "
            + "checks only announce " + tag + " (auto_update/" + APPLY_FAILED_FILE + ").");
        try {
            Files.createDirectories(Path.of("auto_update"));
            Files.writeString(Path.of("auto_update", APPLY_FAILED_FILE), tag, StandardCharsets.UTF_8);
        } catch (Exception e) {
            SourbyLogger.warn("auto-updater: could not write " + APPLY_FAILED_FILE + ": " + e.getMessage());
        }
    }

    private static void writeApplied(String tag) {
        try {
            Files.createDirectories(Path.of("auto_update"));
            Files.writeString(Path.of("auto_update", "applied.tag"), tag, StandardCharsets.UTF_8);
        } catch (Exception e) {
            SourbyLogger.warn("auto-updater: could not write applied.tag: " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a leftover pointer, marker or temporary copy is harmless; the guard state is authoritative
        }
    }

    /** How the server is brought down after a swap. */
    enum RestartPlan {
        /** Clean shutdown; the process exits 0 and something outside restarts it. */
        STOP,
        /** Clean shutdown; the process exits 70 through {@code onServerExit()} so a panel restarts it. */
        EXIT_70;

        /**
         * Resolve the configured mode: {@code exit}, and {@code auto} inside a Pterodactyl/Pelican
         * container, give {@link #EXIT_70}; everything else ({@code stop}, {@code spigot}, {@code auto}
         * elsewhere, an unknown value) gives {@link #STOP}.
         */
        static RestartPlan resolve(String mode, boolean pterodactylDetected) {
            String m = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
            if ("auto".equals(m)) {
                return pterodactylDetected ? EXIT_70 : STOP;
            }
            return "exit".equals(m) ? EXIT_70 : STOP;
        }
    }

    private static void restart(String mode) {
        RestartPlan plan = RestartPlan.resolve(mode, System.getenv("P_SERVER_UUID") != null);
        String m = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        if ("spigot".equals(m)) {
            SourbyLogger.warn("auto-updater: restart-mode=spigot cannot run Spigot's restart-script under region "
                + "threading (its restart command is disabled); doing a clean stop with exit code 0 instead.");
        }
        SourbyLogger.info("auto-updater: restarting now (plan=" + plan + ")."
            + (plan == RestartPlan.STOP ? " The server stops with exit code 0; systemd, a loop script or a panel"
                + " must start it again, or it stays down." : ""));
        if (plan == RestartPlan.EXIT_70) {
            // The exit code rides the normal shutdown path: DedicatedServer.onServerExit() calls
            // System.exit(abnormalExit ? 70 : 0) after stopServer() has saved worlds/players.
            requestExitCode70();
        }
        Bukkit.shutdown();
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
