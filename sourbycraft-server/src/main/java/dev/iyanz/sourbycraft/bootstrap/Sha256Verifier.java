package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared SHA-256 helper for the bootstrap phase's download-integrity checks (library fetch,
 * ViaVersion/ViaBackwards provisioning). JDK-only, matching the rest of {@code bootstrap} — this
 * runs before any external library is on the classpath.
 */
final class Sha256Verifier {

    private Sha256Verifier() {}

    /** Hex-encoded (lowercase) SHA-256 digest of a file's full contents. */
    static String ofFile(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable in this JDK", e);
        }
    }

    /** True when {@code file}'s SHA-256 equals {@code expected} (case-insensitive hex compare). */
    static boolean matches(Path file, String expected) throws IOException {
        return expected.equalsIgnoreCase(ofFile(file));
    }

    private static String toHex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes); // JDK type — bootstrap stays JDK-only
    }
}
