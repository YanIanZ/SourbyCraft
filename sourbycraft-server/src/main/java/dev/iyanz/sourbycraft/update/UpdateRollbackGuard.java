package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Keeps the previous launch jar across an update and restores it when the first boot on the new
 * jar never reaches the ready point (audit SEC-3). Pure file logic over one directory so it is
 * unit-tested with a temporary directory; the Bukkit glue (boot stage + ready listener) lives in
 * {@link AutoUpdateSettings#startUpdater()}.
 *
 * <p><b>Mechanism.</b> One small properties file, {@code auto_update/}{@value #STATE_FILE}, carries
 * a phase:
 * <ol>
 *   <li>{@link UpdateApplier} copies the launch jar to {@code <jar>}{@value #PREV_SUFFIX}, records
 *       {@link Phase#APPLIED} with the tag, both paths and the SHA-256 of {@code .prev}, and only
 *       then swaps; a failed record aborts the apply, a failed swap puts the earlier record back.</li>
 *   <li>The next boot's updater stage ({@link #onBoot()}) sees {@code APPLIED}, moves the phase to
 *       {@link Phase#BOOTING} and asks the caller to watch for ready
 *       ({@link BootDecision#AWAIT_READY}). Ready is Bukkit's {@code ServerLoadEvent STARTUP}: worlds
 *       loaded and plugins enabled, fired right before the server accepts connections and a few
 *       lines before {@code Done (}. {@link #onReady()} then records {@link Phase#OK}.</li>
 *   <li>A boot that instead finds {@code BOOTING} knows the previous boot after the apply ended
 *       before ready. It copies {@code .prev} (never a symbolic link) to a temporary file beside the
 *       launch jar, checks that file's SHA-256, renames it over the launch jar, records
 *       {@link Phase#ROLLED_BACK} and the updater is <em>held</em>: {@link #isHeld()} stays true, and
 *       {@link SourbyUpdater#check} refuses every check and apply, until an operator clears it
 *       ({@link #clearHold()}, {@code /update resume}).</li>
 * </ol>
 *
 * <p><b>Limits, stated plainly.</b>
 * <ul>
 *   <li>A running JVM cannot replace its own jar. The restore rewrites the file on disk; the boot
 *       that performs it is still running the updated (suspect) bytes, and the restored build runs
 *       from the boot after that. With a panel that restarts on exit this is one extra cycle.</li>
 *   <li>"Did not reach ready" is inferred from the phase file alone. A boot the operator stopped
 *       on purpose before ready, a kill by the host, or a crash unrelated to the update all look the
 *       same and trigger the restore. The restore is conservative, not a diagnosis.</li>
 *   <li>A crash before the SourbyCraft bootstrap stage runs (launcher, library download, class
 *       loading) leaves the phase at {@code APPLIED}; the next boot treats itself as the first one
 *       after the apply, so this server-side guard cannot recover that failure. SourbyClip 3.1.4+
 *       can recover before server code loads when its own guard is reached.</li>
 *   <li>The state file, {@code applied.tag} and {@code .prev} are plain files next to the jar; an
 *       operator can delete them. {@code applied.tag} is deliberately left in place after a rollback
 *       so the rolled-back tag is not offered again automatically.</li>
 * </ul>
 */
public final class UpdateRollbackGuard {

    /** Properties file next to the stage dir that carries the apply/boot phase. */
    public static final String STATE_FILE = "rollback.state";
    /** Suffix of the kept previous launch jar. */
    public static final String PREV_SUFFIX = ".prev";

    /** The guard over the server's {@code auto_update} directory. */
    public static final UpdateRollbackGuard SERVER = new UpdateRollbackGuard(Path.of("auto_update"));

    /** Where an apply stands. Persisted by name; order is not significant. */
    public enum Phase {
        /** Jar swapped, restart pending or in progress; no boot on the new jar seen yet. */
        APPLIED,
        /** First boot on the new jar started and has not reached ready. */
        BOOTING,
        /** First boot on the new jar reached ready; the update is confirmed. */
        OK,
        /** The previous jar was copied back; held until an operator clears it. */
        ROLLED_BACK,
        /** The previous jar could not be restored; held until an operator clears it. */
        RESTORE_FAILED
    }

    /** What the boot stage should do after {@link #onBoot()}. */
    public enum BootDecision {
        /** No apply in flight. */
        NONE,
        /** First boot after an apply: register the ready listener that calls {@link #onReady()}. */
        AWAIT_READY,
        /** The previous boot failed before ready and the old jar is back on disk; held. */
        RESTORED,
        /** The previous boot failed before ready and the old jar could not be restored; held. */
        RESTORE_FAILED,
        /** Still held from an earlier rollback. */
        HELD
    }

    /** Snapshot of the state file. */
    public record State(Phase phase, String tag, @Nullable Path launchJar, @Nullable Path prevJar,
                        String appliedAt, String note, @Nullable String prevSha256) {}

    private final Path dir;
    private final Path stateFile;

    public UpdateRollbackGuard(Path autoUpdateDir) {
        this.dir = autoUpdateDir;
        this.stateFile = autoUpdateDir.resolve(STATE_FILE);
    }

    // ------------------------------------------------------------------ apply side

    /**
     * Copy {@code launchJar} to {@code <launchJar>.prev} (via a {@code .part} file so a crash mid-copy
     * never leaves a truncated {@code .prev}). Returns the {@code .prev} path. Must succeed before the
     * swap; the caller aborts the apply when it throws.
     */
    public Path keepPrevious(Path launchJar) throws IOException {
        Path prev = prevPathFor(launchJar);
        Path part = launchJar.resolveSibling(prev.getFileName() + ".part");
        Files.copy(launchJar, part, StandardCopyOption.REPLACE_EXISTING);
        moveReplacing(part, prev);
        return prev;
    }

    /**
     * Record that {@code tag} is about to replace {@code launchJar}, with the previous build at {@code prevJar}.
     * Called before the swap, from the async updater apply callback (streams the kept jar off
     * server/region threads); a failure here must abort the swap.
     */
    public void recordApplied(String tag, Path launchJar, Path prevJar, Instant when) throws IOException {
        // Hash outside the state monitor so status/ready callers cannot wait on this file read.
        String previousHash = sha256(prevJar);
        synchronized (this) {
            write(new State(Phase.APPLIED, tag, launchJar.toAbsolutePath().normalize(),
                prevJar.toAbsolutePath().normalize(), when.toString(), "swapped in-place; restart pending", previousHash));
        }
    }

    /** Strict read of the current state, for {@link #restoreState}; throws when it is unreadable. */
    public synchronized Optional<State> currentState() throws IOException {
        return read();
    }

    /** Put back a state read with {@link #currentState()} (none = no state file). */
    public synchronized void restoreState(Optional<State> previous) throws IOException {
        if (previous.isPresent()) {
            write(previous.get());
        } else {
            Files.deleteIfExists(stateFile);
        }
    }

    /** The {@code .prev} sibling of a launch jar. */
    public static Path prevPathFor(Path launchJar) {
        return launchJar.resolveSibling(launchJar.getFileName() + PREV_SUFFIX);
    }

    // ------------------------------------------------------------------ boot side

    /**
     * Advance the phase for this boot. Call once, early in the boot, before anything that could fail
     * because of the new jar's contents. Never throws: a state-file problem is reported as
     * {@link BootDecision#HELD} with the reason in {@link #holdReason()}.
     */
    public synchronized BootDecision onBoot() {
        State s;
        try {
            s = read().orElse(null);
        } catch (IOException e) {
            noteProblem("state file unreadable: " + e.getMessage());
            return BootDecision.HELD;
        }
        if (s == null) return BootDecision.NONE;
        return switch (s.phase()) {
            case APPLIED -> {
                try {
                    write(with(s, Phase.BOOTING, "first boot on the new jar started; waiting for ready"));
                    yield BootDecision.AWAIT_READY;
                } catch (IOException e) {
                    noteProblem("could not record BOOTING: " + e.getMessage());
                    yield BootDecision.HELD;
                }
            }
            case BOOTING -> restore(s);
            case OK -> BootDecision.NONE;
            case ROLLED_BACK, RESTORE_FAILED -> BootDecision.HELD;
        };
    }

    /** The first boot on the new jar reached ready: confirm the update. No-op in any other phase. */
    public synchronized void onReady() {
        try {
            Optional<State> s = read();
            if (s.isPresent() && s.get().phase() == Phase.BOOTING) {
                write(with(s.get(), Phase.OK, "reached ready; update confirmed"));
            }
        } catch (IOException e) {
            noteProblem("could not record OK: " + e.getMessage());
        }
    }

    /** True while a rollback (or failed rollback) blocks further checks and applies. */
    public synchronized boolean isHeld() {
        try {
            Optional<State> s = read();
            return s.isPresent() && (s.get().phase() == Phase.ROLLED_BACK || s.get().phase() == Phase.RESTORE_FAILED);
        } catch (IOException e) {
            return true; // unreadable state is a reason to stop, not to proceed
        }
    }

    /** Human-readable reason for the hold (empty when not held). */
    public synchronized String holdReason() {
        try {
            Optional<State> s = read();
            if (s.isEmpty()) return "";
            State st = s.get();
            return switch (st.phase()) {
                case ROLLED_BACK -> "update " + st.tag() + " was rolled back: " + st.note();
                case RESTORE_FAILED -> "update " + st.tag() + " failed to boot and the previous jar could NOT be restored: " + st.note();
                default -> "";
            };
        } catch (IOException e) {
            return "state file unreadable: " + e.getMessage();
        }
    }

    /** Current state, if an apply has been recorded. */
    public synchronized Optional<State> state() {
        try {
            return read();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Operator intervention: forget the rollback and allow checks again. Returns true when a state
     * file was removed. Leaves {@code applied.tag} alone so the rolled-back tag is not re-offered;
     * delete that file too to retry the same release.
     */
    public synchronized boolean clearHold() throws IOException {
        return Files.deleteIfExists(stateFile);
    }

    /** The state file path (for status output). */
    public Path stateFile() {
        return stateFile;
    }

    // ------------------------------------------------------------------ internals

    private BootDecision restore(State s) {
        Path launch = s.launchJar();
        Path prev = s.prevJar();
        if (prev != null && Files.isSymbolicLink(prev)) {
            String reason = "previous jar " + prev + " is a symbolic link; refusing restore; jar files left untouched";
            SourbyLogger.error("auto-updater: " + reason);
            writeQuietly(with(s, Phase.RESTORE_FAILED, reason));
            return BootDecision.RESTORE_FAILED;
        }
        if (launch == null || prev == null || !Files.isRegularFile(prev, LinkOption.NOFOLLOW_LINKS)) {
            writeQuietly(with(s, Phase.RESTORE_FAILED, "previous jar missing: " + prev));
            return BootDecision.RESTORE_FAILED;
        }
        Path part = null;
        try {
            // Copy, not move: .prev stays for the operator; the running JVM keeps its open inode.
            // The bytes are copied once into a fresh temporary file, and THAT file is hashed and
            // renamed, so what lands on the launch path is exactly what was verified even if
            // .prev changes in between.
            Path parent = launch.toAbsolutePath().getParent();
            part = Files.createTempFile(parent, launch.getFileName() + ".restore-", ".part");
            try (InputStream in = Files.newInputStream(prev, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            if (s.prevSha256() != null) {
                String actual = sha256(part);
                if (!actual.equalsIgnoreCase(s.prevSha256())) {
                    String reason = "SHA-256 mismatch for " + prev + ": expected=" + s.prevSha256()
                        + ", actual=" + actual + "; refusing restore; jar files left untouched";
                    SourbyLogger.error("auto-updater: " + reason);
                    writeQuietly(with(s, Phase.RESTORE_FAILED, reason));
                    return BootDecision.RESTORE_FAILED;
                }
            }
            copyPermissions(launch, part);
            moveReplacing(part, launch);
            part = null;
            writeQuietly(with(s, Phase.ROLLED_BACK, "the boot after applying it did not reach ready; "
                + prev.getFileName() + " was copied back over " + launch.getFileName()));
            return BootDecision.RESTORED;
        } catch (IOException e) {
            writeQuietly(with(s, Phase.RESTORE_FAILED, "copy of " + prev + " over " + launch + " failed: " + e.getMessage()));
            return BootDecision.RESTORE_FAILED;
        } finally {
            if (part != null) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                    // a stray temporary file is harmless; the state already records the outcome
                }
            }
        }
    }

    /** A temp file is created owner-only; give the restored jar the launch jar's POSIX mode. */
    private static void copyPermissions(Path from, Path to) {
        try {
            Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX file system, or the launch jar is gone: keep the default mode
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static State with(State s, Phase phase, String note) {
        return new State(phase, s.tag(), s.launchJar(), s.prevJar(), s.appliedAt(), note, s.prevSha256());
    }

    private void noteProblem(String note) {
        try {
            Optional<State> s = read();
            if (s.isPresent()) writeQuietly(with(s.get(), Phase.RESTORE_FAILED, note));
        } catch (IOException ignored) {
            // nothing more can be recorded; isHeld() already answers true for an unreadable file
        }
    }

    private void writeQuietly(State s) {
        try {
            write(s);
        } catch (IOException ignored) {
            // the decision has been made; a failed record is reported via holdReason() on next read
        }
    }

    private Optional<State> read() throws IOException {
        if (!Files.isRegularFile(stateFile)) return Optional.empty();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            p.load(in);
        }
        String phaseRaw = p.getProperty("phase", "");
        Phase phase;
        try {
            phase = Phase.valueOf(phaseRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IOException("unknown phase '" + phaseRaw + "' in " + stateFile);
        }
        return Optional.of(new State(phase,
            p.getProperty("tag", "?"),
            pathOrNull(p.getProperty("launch")),
            pathOrNull(p.getProperty("prev")),
            p.getProperty("applied_at", ""),
            p.getProperty("note", ""),
            p.getProperty("prev_sha256")));
    }

    private void write(State s) throws IOException {
        Files.createDirectories(dir);
        Properties p = new Properties();
        p.setProperty("phase", s.phase().name());
        p.setProperty("tag", s.tag());
        if (s.launchJar() != null) p.setProperty("launch", s.launchJar().toString());
        if (s.prevJar() != null) p.setProperty("prev", s.prevJar().toString());
        p.setProperty("applied_at", s.appliedAt());
        p.setProperty("note", s.note());
        if (s.prevSha256() != null) p.setProperty("prev_sha256", s.prevSha256().toLowerCase(Locale.ROOT));
        Path part = stateFile.resolveSibling(STATE_FILE + ".part");
        try (OutputStream out = Files.newOutputStream(part)) {
            p.store(out, "SourbyCraft auto-updater rollback guard — delete to lift a hold (/update resume does this)");
        }
        moveReplacing(part, stateFile);
    }

    private static @Nullable Path pathOrNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : Path.of(raw);
    }
}
