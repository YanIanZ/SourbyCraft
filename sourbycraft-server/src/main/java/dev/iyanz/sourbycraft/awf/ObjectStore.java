package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Set;

/**
 * Content-addressed chunk storage: each object is a chunk's deflated bytes, named by the SHA-256
 * of its uncompressed bytes.
 *
 * <p>Content addressing is what makes {@link PersistenceMode#INCREMENTAL} both cheap and safe: a
 * chunk that has not changed already has an object with its name, so a commit writes only new
 * objects and references the rest, while every generation still describes the complete world.</p>
 *
 * <p>Objects are written to a temporary file, forced, and moved into place, so an object either
 * exists whole or not at all. Objects no retained generation references are removed by
 * {@link #retainOnly}; that runs after a commit, so a crash before it only leaves garbage.</p>
 */
final class ObjectStore {

    private static final int RAW_LENGTH_BYTES = 4;
    private final Path root;

    ObjectStore(final Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
        // Unfinished writes from a crash.
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(root)) {
            for (final Path shard : shards) {
                if (!Files.isDirectory(shard)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(shard, "*.tmp")) {
                    for (final Path temp : files) Files.deleteIfExists(temp);
                }
            }
        }
    }

    static String name(final byte[] raw) {
        return HexFormat.of().formatHex(AwfFile.sha256(raw));
    }

    private Path path(final String name) {
        return this.root.resolve(name.substring(0, 2)).resolve(name);
    }

    boolean contains(final String name) {
        return Files.isRegularFile(path(name));
    }

    /**
     * Stores a chunk.
     *
     * @param rewrite write even if an object of this name exists (FULL mode)
     * @return the object's name and the bytes written, 0 when it already existed
     */
    Written put(final byte[] raw, final boolean rewrite) throws IOException {
        final String name = name(raw);
        final Path target = path(name);
        if (!rewrite && Files.isRegularFile(target)) {
            return new Written(name, 0L);
        }
        Files.createDirectories(target.getParent());
        final byte[] deflated = AwfFile.deflate(raw);
        final Path temp = Files.createTempFile(target.getParent(), name, ".tmp");
        try {
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                final ByteBuffer buffer = ByteBuffer.allocate(RAW_LENGTH_BYTES + deflated.length);
                buffer.putInt(raw.length).put(deflated).flip();
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return new Written(name, RAW_LENGTH_BYTES + deflated.length);
    }

    record Written(String name, long bytes) {}

    /** Reads and verifies an object. */
    byte[] get(final String name) throws IOException {
        final byte[] stored;
        try {
            stored = Files.readAllBytes(path(name));
        } catch (final NoSuchFileException missing) {
            throw new AwfFile.CorruptAwfException("object " + name + " is missing");
        }
        if (stored.length < RAW_LENGTH_BYTES) throw new AwfFile.CorruptAwfException("object " + name + " is truncated");
        final int rawLength = ByteBuffer.wrap(stored, 0, RAW_LENGTH_BYTES).getInt();
        if (rawLength < 0 || rawLength > AwfFile.MAX_CHUNK_BYTES) {
            throw new AwfFile.CorruptAwfException("object " + name + " declares size " + rawLength);
        }
        final byte[] body = java.util.Arrays.copyOfRange(stored, RAW_LENGTH_BYTES, stored.length);
        final byte[] raw = AwfFile.inflate(body, rawLength, null);
        if (!name(raw).equals(name)) throw new AwfFile.CorruptAwfException("object " + name + " fails its SHA-256");
        return raw;
    }

    /** Deletes one object if it exists; returns whether it did. */
    boolean delete(final String name) throws IOException {
        return Files.deleteIfExists(path(name));
    }

    /** Deletes every object not in {@code live}; returns how many were removed. */
    int retainOnly(final Set<String> live) throws IOException {
        int removed = 0;
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(this.root)) {
            for (final Path shard : shards) {
                if (!Files.isDirectory(shard)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(shard)) {
                    for (final Path object : files) {
                        if (!live.contains(object.getFileName().toString()) && Files.deleteIfExists(object)) removed++;
                    }
                }
            }
        }
        return removed;
    }
}
