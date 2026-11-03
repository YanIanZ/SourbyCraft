package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The strict file primitives the format-2 {@link FileProfileRepository} paths use.
 *
 * <p>Unlike the format-1 writer, which falls back to a plain rename and forces the directory on a best
 * effort basis, {@link #writeDurable} requires an atomic rename and a directory force, and it tells its
 * caller which side of the rename a failure happened on: before (nothing is visible, a plain
 * {@link IOException}) or after it started ({@link OutcomeUnknown}: the new file may or may not be
 * visible and durable). A filesystem without atomic rename raises {@link DurabilityUnsupported}; the
 * repository then refuses co-op transactional writes instead of degrading (COOP-DESIGN §5). Whether the
 * actual filesystem keeps these guarantees across power loss is owner qualification work, not a claim of
 * this class.</p>
 *
 * <p>{@link #readBounded} reads the actual stream up to a limit plus one sentinel byte, never a size
 * check followed by an unbounded read, so a file that grows between two calls cannot force a larger
 * allocation.</p>
 */
final class FileProfileIo {

    private FileProfileIo() {}

    /** A failure after the rename of a durable write started: the outcome is unknown, not "not written". */
    static final class OutcomeUnknown extends IOException {

        private static final long serialVersionUID = 1L;

        OutcomeUnknown(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /** The filesystem cannot rename atomically or force a directory: co-op transactions must stay disabled. */
    static final class DurabilityUnsupported extends IOException {

        private static final long serialVersionUID = 1L;

        DurabilityUnsupported(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /** At most {@code limit} bytes of {@code file}; a longer file is refused without reading past the sentinel. */
    static byte[] readBounded(final Path file, final int limit) throws IOException {
        if (limit < 0 || limit == Integer.MAX_VALUE) throw new IllegalArgumentException("limit " + limit);
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("record " + file + " is larger than its " + limit + "-byte limit; it was not read further");
            }
            return bytes;
        }
    }

    /**
     * Writes {@code bytes} to a unique temporary sibling, forces it, renames it atomically over
     * {@code file} and forces the directory.
     *
     * @throws DurabilityUnsupported the filesystem has no atomic rename (nothing was replaced)
     * @throws OutcomeUnknown the rename or the directory force failed after the rename started
     * @throws IOException the temporary file could not be written (nothing was replaced)
     */
    static void writeDurable(final Path file, final byte[] bytes) throws IOException {
        final Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        } catch (final IOException | RuntimeException failed) {
            deleteQuietly(temporary);
            throw failed instanceof IOException io ? io : new IOException("writing " + temporary + " failed", failed);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            deleteQuietly(temporary);
            throw new DurabilityUnsupported("the filesystem holding " + file.getParent() + " does not rename atomically",
                unsupported);
        } catch (final IOException failed) {
            if (Files.exists(temporary)) {
                // The temporary file is still there, so the rename did not happen: nothing was replaced.
                deleteQuietly(temporary);
                throw failed;
            }
            throw new OutcomeUnknown("renaming " + temporary.getFileName() + " over " + file + " failed after it started ("
                + failed.getMessage() + ")", failed);
        }
        try {
            forceDirectory(file.getParent());
        } catch (final IOException failed) {
            throw new OutcomeUnknown(file + " was renamed but its directory could not be forced (" + failed.getMessage()
                + ")", failed);
        }
    }

    /** Forces a directory's entries to disk; unlike the format-1 helper, a failure is reported. */
    static void forceDirectory(final Path directory) throws IOException {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (final RuntimeException unsupported) {
            throw new IOException("cannot force directory " + directory + ": " + unsupported, unsupported);
        }
    }

    /**
     * Creates {@code directory} and any missing parents below {@code root}, forcing the parent of every
     * directory it created so the new entries survive a crash.
     */
    static void ensureDirectory(final Path directory, final Path root) throws IOException {
        if (Files.isDirectory(directory)) return;
        final List<Path> missing = new ArrayList<>();
        for (Path at = directory; at != null && !at.equals(root) && !Files.isDirectory(at); at = at.getParent()) {
            missing.add(at);
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            final Path created = missing.get(i);
            try {
                Files.createDirectory(created);
            } catch (final java.nio.file.FileAlreadyExistsException race) {
                if (!Files.isDirectory(created)) throw race;
            }
            forceDirectory(created.getParent());
        }
    }

    /**
     * Checks once that {@code root}'s filesystem renames atomically and forces directories, with a
     * probe file that is removed again.
     *
     * @throws DurabilityUnsupported when either is missing
     */
    static void probe(final Path root) throws DurabilityUnsupported {
        final Path probe = root.resolve(".durability-probe-" + UUID.randomUUID());
        try {
            writeDurable(probe, new byte[] {1});
        } catch (final DurabilityUnsupported unsupported) {
            throw unsupported;
        } catch (final IOException failed) {
            throw new DurabilityUnsupported("the profile directory " + root + " failed the durability probe ("
                + failed.getMessage() + ")", failed);
        } finally {
            deleteQuietly(probe);
        }
    }

    static void deleteQuietly(final Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (final IOException ignored) {
            // A leftover temporary or probe file is ignored by every reader.
        }
    }

    static String hex(final byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
