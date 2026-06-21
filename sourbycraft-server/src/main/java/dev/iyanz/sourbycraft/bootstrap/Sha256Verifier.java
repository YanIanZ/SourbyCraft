package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Sha256Verifier {

    private Sha256Verifier() {}

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

    static boolean matches(Path file, String expected) throws IOException {
        return expected.equalsIgnoreCase(ofFile(file));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
