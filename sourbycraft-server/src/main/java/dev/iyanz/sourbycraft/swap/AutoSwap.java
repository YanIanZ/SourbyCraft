package dev.iyanz.sourbycraft.swap;

import dev.iyanz.sourbycraft.util.ContainerMemory;
import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Auto-create-OS-swap: on boot, if the host has no swap and the operator opted in, attempt to
 * {@code fallocate}/{@code mkswap}/{@code swapon} a sized swapfile so the OS has spill headroom
 * before a hard OOM-kill.
 *
 * <h2>Privilege constraint (read this before filing a bug)</h2>
 * {@code swapon} requires root / {@code CAP_SYS_ADMIN}. Server processes normally run unprivileged
 * (best practice, and the norm on Pterodactyl/Pelican/rootless-Docker) — so on the overwhelming
 * majority of real deployments this WILL attempt the OS calls, hit a permission error, and skip
 * gracefully with a one-line log telling the operator the exact commands to run themselves. This is
 * expected, not a bug: {@link #attempt} never throws, never blocks boot, and never retries in a
 * loop.
 *
 * <h2>Scope</h2>
 * Linux-only — swapfile semantics ({@code fallocate}/{@code mkswap}/{@code swapon}) are a Linux-
 * specific mechanism. macOS and Windows manage their own paging file automatically; both are
 * skipped with a one-line note (never attempted, never an error).
 *
 * <p>Config-gated, default OFF ({@code swap.auto-create.enabled=false}) — creating a multi-
 * gigabyte file and mutating host swap state is more invasive than SourbyCraft's other boot
 * defaults, so it is opt-in. See {@link dev.iyanz.sourbycraft.SourbyCraftConfig} for the seeded
 * keys. Dispatched off the boot thread ({@link dev.iyanz.sourbycraft.util.VirtualExecutor}) by the
 * caller so an unusual filesystem's slow {@code dd} fallback can never delay "Done".
 */
public final class AutoSwap {

    private AutoSwap() {}

    private static final String[] FALLOCATE_CANDIDATES = {"/usr/bin/fallocate", "/bin/fallocate", "fallocate"};
    private static final String[] MKSWAP_CANDIDATES = {"/sbin/mkswap", "/usr/sbin/mkswap", "/bin/mkswap", "mkswap"};
    private static final String[] SWAPON_CANDIDATES = {"/sbin/swapon", "/usr/sbin/swapon", "/bin/swapon", "swapon"};
    private static final String[] DD_CANDIDATES = {"/bin/dd", "/usr/bin/dd", "dd"};

    private static final long MIN_SIZE_MB = 64L;
    private static final long PROCESS_TIMEOUT_SECONDS = 180L;

    /**
     * Entry point — never throws, never blocks the caller beyond actually running the OS commands
     * (callers should dispatch this off-thread; see {@link dev.iyanz.sourbycraft.util.VirtualExecutor}).
     *
     * @param enabled   {@code swap.auto-create.enabled} — feature opt-in.
     * @param pathStr   {@code swap.auto-create.path} — where to create the swapfile.
     * @param maxSizeMb {@code swap.auto-create.max-size-mb} — hard cap; actual size is
     *                  {@code min(detected RAM/container limit, this cap)}.
     */
    public static void attempt(final boolean enabled, final String pathStr, final long maxSizeMb) {
        if (!enabled) {
            SourbyLogger.info("Auto-swap: disabled (swap.auto-create.enabled=false) — SourbyCraft will not "
                + "attempt to create OS swap. Enable it in sourbycraft_config for automatic spill headroom "
                + "against a hard OOM-kill (needs root/CAP_SYS_ADMIN; skips gracefully otherwise).");
            return;
        }
        try {
            attempt0(pathStr, maxSizeMb);
        } catch (Throwable t) {
            SourbyLogger.warn("Auto-swap: unexpected failure, skipping gracefully (" + t.getMessage() + ")");
        }
    }

    private static void attempt0(final String pathStr, final long maxSizeMb) {
        final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("linux")) {
            SourbyLogger.info("Auto-swap: skipped (" + prettyOs(osName) + " manages its own paging/swap "
                + "automatically — nothing for SourbyCraft to do here).");
            return;
        }

        if (swapAlreadyPresent()) {
            SourbyLogger.info("Auto-swap: skipped (swap already present on this host).");
            return;
        }

        final long ramBytes = ContainerMemory.limitBytes();
        final long capBytes = Math.max(MIN_SIZE_MB, maxSizeMb) * 1024L * 1024L;
        final long sizeBytes = ramBytes > 0 ? Math.min(ramBytes, capBytes) : capBytes;
        final long sizeMb = Math.max(MIN_SIZE_MB, sizeBytes / (1024 * 1024));

        final Path path = Path.of(pathStr);
        boolean createdHere = false;
        try {
            if (!Files.exists(path)) {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                if (!runOk(fallocateCommand(path, sizeMb)) && !runOk(ddCommand(path, sizeMb))) {
                    SourbyLogger.warn("Auto-swap: could not allocate " + sizeMb + "M at " + path
                        + " (fallocate + dd fallback both failed) — skipping. To add swap yourself: "
                        + manualHint(path, sizeMb));
                    return;
                }
                createdHere = true;
            }

            try {
                Files.setPosixFilePermissions(path, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (Throwable ignored) {
                // Non-POSIX filesystem, or already correct — best-effort only, never fatal.
            }

            if (!runOk(new String[]{resolve(MKSWAP_CANDIDATES), path.toString()})) {
                SourbyLogger.warn("Auto-swap: mkswap failed on " + path + " — most likely missing "
                    + "root/CAP_SYS_ADMIN (normal for a non-root server process). Skipping gracefully. "
                    + "To add swap yourself, run as root: " + manualHint(path, sizeMb));
                cleanupIfCreatedHere(path, createdHere);
                return;
            }

            if (!runOk(new String[]{resolve(SWAPON_CANDIDATES), path.toString()})) {
                SourbyLogger.warn("Auto-swap: swapon failed on " + path + " — most likely missing "
                    + "root/CAP_SYS_ADMIN (normal for a non-root server process, or CAP_SYS_ADMIN not "
                    + "granted in this container). Skipping gracefully. To add swap yourself, run as root: "
                    + manualHint(path, sizeMb));
                cleanupIfCreatedHere(path, createdHere);
                return;
            }

            SourbyLogger.info("Auto-swap: created + enabled a " + sizeMb + "M swapfile at " + path
                + " (spill headroom against a hard OOM-kill; the HudBars Swap readout will now show it). "
                + "This does not persist across a host reboot — add it to /etc/fstab if you want that: '"
                + path.toAbsolutePath() + " none swap sw 0 0'.");
        } catch (IOException e) {
            SourbyLogger.warn("Auto-swap: I/O error while creating " + path + ", skipping gracefully ("
                + e.getMessage() + ")");
            cleanupIfCreatedHere(path, createdHere);
        }
    }

    private static void cleanupIfCreatedHere(final Path path, final boolean createdHere) {
        if (createdHere) {
            try {
                Files.deleteIfExists(path);
            } catch (Throwable ignored) {
                // best-effort
            }
        }
    }

    private static boolean swapAlreadyPresent() {
        try {
            final var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun && sun.getTotalSwapSpaceSize() > 0) {
                return true;
            }
        } catch (Throwable ignored) {
            // fall through to /proc/swaps
        }
        try {
            final var lines = Files.readAllLines(Path.of("/proc/swaps"));
            return lines.size() > 1; // header line + at least one active swap device/file
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String[] fallocateCommand(final Path path, final long sizeMb) {
        return new String[]{resolve(FALLOCATE_CANDIDATES), "-l", sizeMb + "M", path.toString()};
    }

    private static String[] ddCommand(final Path path, final long sizeMb) {
        return new String[]{resolve(DD_CANDIDATES), "if=/dev/zero", "of=" + path, "bs=1M", "count=" + sizeMb};
    }

    /** Prefers an absolute, confirmed-executable path (covers /sbin not being on a non-root PATH); falls back to the bare command name and lets exec's own PATH search try (or fail cleanly). */
    private static String resolve(final String[] candidates) {
        for (final String c : candidates) {
            if (c.startsWith("/") && Files.isExecutable(Path.of(c))) return c;
        }
        return candidates[candidates.length - 1];
    }

    private static boolean runOk(final String[] cmd) {
        try {
            final ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            try (var in = p.getInputStream()) {
                in.readAllBytes(); // drain so a full output pipe can never hang the child
            }
            final boolean finished = p.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false; // binary missing, permission denied to exec, interrupted, ...
        }
    }

    private static String manualHint(final Path path, final long sizeMb) {
        return "sudo fallocate -l " + sizeMb + "M " + path + " && sudo chmod 600 " + path
            + " && sudo mkswap " + path + " && sudo swapon " + path;
    }

    private static String prettyOs(final String lowerOsName) {
        if (lowerOsName.contains("mac") || lowerOsName.contains("darwin")) return "macOS";
        if (lowerOsName.contains("win")) return "Windows";
        return lowerOsName.isBlank() ? "this OS" : lowerOsName;
    }
}
