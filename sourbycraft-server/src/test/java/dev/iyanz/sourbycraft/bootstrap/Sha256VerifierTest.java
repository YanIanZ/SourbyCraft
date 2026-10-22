package dev.iyanz.sourbycraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Sha256VerifierTest {
    /** SHA-256 of "abc" (FIPS 180-2 test vector). */
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    /** SHA-256 of the empty input. */
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @TempDir Path dir;

    private Path write(String name, byte[] data) throws IOException {
        return Files.write(this.dir.resolve(name), data);
    }

    @Test
    void knownVectorIsLowercaseHex() throws IOException {
        Path f = write("abc.bin", "abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals(ABC, Sha256Verifier.ofFile(f));
    }

    @Test
    void emptyFileHasTheEmptyInputDigest() throws IOException {
        Path f = write("empty.bin", new byte[0]);
        assertEquals(EMPTY, Sha256Verifier.ofFile(f));
        assertTrue(Sha256Verifier.matches(f, EMPTY));
        assertFalse(Sha256Verifier.matches(f, ABC));
    }

    @Test
    void matchesAcceptsTheCorrectDigest() throws IOException {
        Path f = write("abc.bin", "abc".getBytes(StandardCharsets.US_ASCII));
        assertTrue(Sha256Verifier.matches(f, ABC));
    }

    @Test
    void matchesRejectsAWrongDigestAndAOneBitFlip() throws IOException {
        Path f = write("abc.bin", "abc".getBytes(StandardCharsets.US_ASCII));
        assertFalse(Sha256Verifier.matches(f, EMPTY));
        // Same length, last hex digit changed.
        assertFalse(Sha256Verifier.matches(f, ABC.substring(0, 63) + "e"));
        // Truncated / padded expectations never match.
        assertFalse(Sha256Verifier.matches(f, ABC.substring(0, 63)));
        assertFalse(Sha256Verifier.matches(f, ABC + "0"));
        assertFalse(Sha256Verifier.matches(f, ""));
    }

    @Test
    void hexCaseOfTheExpectedDigestDoesNotMatter() throws IOException {
        Path f = write("abc.bin", "abc".getBytes(StandardCharsets.US_ASCII));
        assertTrue(Sha256Verifier.matches(f, ABC.toUpperCase(java.util.Locale.ROOT)));
        StringBuilder mixed = new StringBuilder();
        for (int i = 0; i < ABC.length(); i++) {
            char c = ABC.charAt(i);
            mixed.append(i % 2 == 0 ? Character.toUpperCase(c) : c);
        }
        assertTrue(Sha256Verifier.matches(f, mixed.toString()));
        // ofFile itself always reports lowercase.
        assertEquals(ABC, Sha256Verifier.ofFile(f));
    }

    @Test
    void missingFileIsAnIOExceptionNotAMatch() {
        Path missing = this.dir.resolve("nope.bin");
        assertThrows(NoSuchFileException.class, () -> Sha256Verifier.ofFile(missing));
        assertThrows(NoSuchFileException.class, () -> Sha256Verifier.matches(missing, EMPTY));
    }

    @Test
    void directoryIsAnIOExceptionNotAMatch() {
        assertThrows(IOException.class, () -> Sha256Verifier.matches(this.dir, EMPTY));
    }

    @Test
    void filesLargerThanTheReadBufferAreHashedInFull() throws Exception {
        // 64 KiB buffer: use sizes straddling one and several buffer fills.
        for (int size : new int[] {64 * 1024 - 1, 64 * 1024, 64 * 1024 + 1, 3 * 64 * 1024 + 17}) {
            byte[] data = new byte[size];
            new Random(size).nextBytes(data);
            Path f = write("big-" + size + ".bin", data);
            String expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(data));
            assertEquals(expected, Sha256Verifier.ofFile(f), "size " + size);
        }
    }

    @Test
    void aSingleByteChangeChangesTheDigest() throws IOException {
        byte[] data = new byte[1000];
        Path a = write("a.bin", data);
        data[999] = 1;
        Path b = write("b.bin", data);
        assertFalse(Sha256Verifier.ofFile(a).equals(Sha256Verifier.ofFile(b)));
    }
}
