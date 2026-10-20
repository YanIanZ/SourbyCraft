package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The two durability primitives the FILE backend is built on: fsync of one file and fsync of one
 * directory. {@link #DISK} is the real one; tests substitute a recording or crashing one to prove
 * the order in which a commit makes things durable ({@code AwfDurabilityTest}).
 *
 * <p>Why both: on POSIX, forcing a file makes its bytes and inode durable but says nothing about
 * the directory entry that names it; a create or rename is only durable once the directory holding
 * it is forced. (ext4 in {@code data=ordered} mode happens to flush new files' data on a directory
 * fsync; nothing else promises that, so a file force is never skipped in favour of a directory
 * force.)</p>
 *
 * <p>A failed force is a failed commit: {@link #DISK} propagates every I/O error from a directory
 * force. The one tolerated case is a platform that cannot open a directory at all (Windows): there
 * the rename's own durability is all there is, the first occurrence is logged, and
 * {@link #degradedDirectoryForces()} stays non-zero so the gap is visible, never silent ({@code /perf awf}
 * shows the count and reason while it is non-zero).</p>
 *
 * <p>Public only for the two static degraded-state accessors; the primitives are the FILE
 * backend's own.</p>
 */
public interface Durability {

    /** Makes a file's content and metadata durable (fsync). */
    void forceFile(Path file) throws IOException;

    /**
     * Makes a directory's entries durable: the creates and renames inside it.
     *
     * @throws IOException when the force fails; the caller's commit must fail with it
     */
    void forceDirectory(Path dir) throws IOException;

    /**
     * Publishes {@code from} under {@code to} by one atomic rename; the temporary file always sits
     * next to its target. A filesystem that cannot rename atomically throws
     * {@link java.nio.file.AtomicMoveNotSupportedException}, and the caller refuses the commit
     * rather than copying: an object or a generation must appear whole under its name or not at
     * all. On the seam so a test can model that refusal.
     */
    default void moveAtomically(final Path from, final Path to) throws IOException {
        Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Creates a directory and its missing ancestors, and makes the new entries durable: the parent
     * of every directory created is forced, from the first existing ancestor downwards. Call it
     * before anything that depends on the directory is forced.
     *
     * @return the directories created, outermost first; empty when it already existed
     */
    default List<Path> createDirectoriesDurably(final Path dir) throws IOException {
        final Deque<Path> missing = new ArrayDeque<>();
        for (Path p = dir.toAbsolutePath(); p != null && !Files.isDirectory(p); p = p.getParent()) {
            missing.push(p);
        }
        if (missing.isEmpty()) return List.of();
        Files.createDirectories(dir);
        final List<Path> created = new ArrayList<>(missing);
        for (final Path child : created) {
            final Path parent = child.getParent();
            if (parent != null) this.forceDirectory(parent);
        }
        return created;
    }

    /** Directory forces this process could not perform because the platform cannot open a directory. */
    static long degradedDirectoryForces() {
        return Disk.DEGRADED.get();
    }

    /** Why directory forces are degraded, or {@code null} while none has been. */
    static String degradedReason() {
        return Disk.DEGRADED_REASON.get();
    }

    Durability DISK = new Disk();

    /** The on-disk implementation; a class rather than a lambda so the degraded state has a home. */
    final class Disk implements Durability {
        private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SourbyCraft/AWF");
        private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        private static final AtomicLong DEGRADED = new AtomicLong();
        private static final AtomicReference<String> DEGRADED_REASON = new AtomicReference<>();
        private static final AtomicBoolean LOGGED = new AtomicBoolean();

        private Disk() {}

        @Override
        public void forceFile(final Path file) throws IOException {
            // WRITE rather than READ: Windows' FlushFileBuffers needs a writable handle.
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }

        @Override
        public void forceDirectory(final Path dir) throws IOException {
            final FileChannel channel;
            try {
                channel = FileChannel.open(dir, StandardOpenOption.READ);
            } catch (final IOException | UnsupportedOperationException refused) {
                // Linux and macOS open directories; only a platform that cannot (Windows) or a
                // provider that refuses outright is tolerated. A missing or unreadable directory
                // on a platform that can open them is a real failure and fails the commit.
                if (refused instanceof UnsupportedOperationException || WINDOWS) {
                    degraded(dir, refused);
                    return;
                }
                throw refused;
            }
            try (channel) {
                channel.force(true);
            }
        }

        private static void degraded(final Path dir, final Exception why) {
            DEGRADED.incrementAndGet();
            final String reason = why.getClass().getSimpleName() + ": " + why.getMessage();
            DEGRADED_REASON.compareAndSet(null, reason);
            if (LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("AWF cannot fsync directories on this platform ({}); directory entries such as {} rely on the"
                    + " rename alone. Commits are not fully durable against power loss here.", reason, dir);
            }
        }
    }
}
