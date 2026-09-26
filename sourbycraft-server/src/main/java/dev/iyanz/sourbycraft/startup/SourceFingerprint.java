package dev.iyanz.sourbycraft.startup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What a cached output was derived from: a strong SHA-256 of the source bytes, plus size and
 * modification time.
 *
 * <p>Size and mtime are only an early fast-path check. A mismatch there proves the source changed
 * and skips nothing but a lookup; a match proves nothing, so reuse always requires the SHA-256 to
 * match as well.</p>
 */
public record SourceFingerprint(String sha256, long size, long modifiedMillis) {

    public SourceFingerprint {
        if (sha256 == null || sha256.length() != 64) {
            throw new IllegalArgumentException("sha256 must be 64 hex characters");
        }
    }

    /** Reads and hashes the file. */
    public static SourceFingerprint of(final Path file) throws IOException {
        final long size = Files.size(file);
        final long modified = Files.getLastModifiedTime(file).toMillis();
        return new SourceFingerprint(sha256(file), size, modified);
    }

    /** Whether size and mtime alone already rule reuse out. */
    boolean cheaplyDiffersFrom(final long size, final long modifiedMillis) {
        return this.size != size || this.modifiedMillis != modifiedMillis;
    }

    static String sha256(final Path file) throws IOException {
        final MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(final byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(impossible);
        }
    }
}
