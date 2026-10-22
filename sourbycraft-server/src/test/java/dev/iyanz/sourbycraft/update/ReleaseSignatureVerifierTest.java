package dev.iyanz.sourbycraft.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ed25519 release signatures (audit SEC-2). The key pair is generated inside the test; the
 * repository ships none, and the first test pins that.
 */
class ReleaseSignatureVerifierTest {

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static byte[] sign(KeyPair kp, byte[] data) throws GeneralSecurityException {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(data);
        return s.sign();
    }

    @Test
    void thisBuildShipsTheReleaseKeySoTheVerifierIsActive() throws Exception {
        // SEC-2 key generated 2026-10-22 (fingerprint sha256:a9643e8b3dcdaae50d0acd5a93f4ef1fff593ee7c8bf26199ca32a0b7232f9c4);
        // the public key ships as META-INF/sourbycraft-release.pub from Build 48 on.
        var url = ReleaseSignatureVerifier.class.getResource(ReleaseSignatureVerifier.KEY_RESOURCE);
        assertNotNull(url, "the release public key is missing from the build");
        assertTrue(ReleaseSignatureVerifier.fromClasspath().isActive());
        String pem;
        try (var in = url.openStream()) {
            pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = java.util.Base64.getDecoder().decode(b64);
        String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(der));
        assertEquals("a9643e8b3dcdaae50d0acd5a93f4ef1fff593ee7c8bf26199ca32a0b7232f9c4", fingerprint);
    }

    @Test
    void inactiveVerifierAcceptsAnythingBecauseItChecksNothing(@TempDir Path dir) throws Exception {
        Path jar = Files.writeString(dir.resolve("a.jar"), "bytes");
        assertDoesNotThrow(() -> ReleaseSignatureVerifier.inactive().verifyOrThrow(jar, new byte[0]));
    }

    @Test
    void validSignatureVerifiesRawAndBase64(@TempDir Path dir) throws Exception {
        KeyPair kp = newKeyPair();
        byte[] data = "SourbyCraft release bytes".getBytes(StandardCharsets.UTF_8);
        Path jar = Files.write(dir.resolve("SourbyCraft.jar"), data);
        byte[] sig = sign(kp, data);
        ReleaseSignatureVerifier v = ReleaseSignatureVerifier.fromEncodedKey(kp.getPublic().getEncoded());

        assertTrue(v.isActive());
        assertDoesNotThrow(() -> v.verifyOrThrow(jar, sig));
        assertDoesNotThrow(() -> v.verifyOrThrow(jar,
            Base64.getEncoder().encodeToString(sig).getBytes(StandardCharsets.US_ASCII)));
        assertDoesNotThrow(() -> v.verifyOrThrow(jar,
            (Base64.getEncoder().encodeToString(sig) + "\n").getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void tamperedFileWrongKeyAndGarbageAreRejected(@TempDir Path dir) throws Exception {
        KeyPair kp = newKeyPair();
        byte[] data = "SourbyCraft release bytes".getBytes(StandardCharsets.UTF_8);
        byte[] sig = sign(kp, data);
        ReleaseSignatureVerifier v = ReleaseSignatureVerifier.fromEncodedKey(kp.getPublic().getEncoded());

        Path tampered = Files.write(dir.resolve("t.jar"), "SourbyCraft release bytes!".getBytes(StandardCharsets.UTF_8));
        assertThrows(GeneralSecurityException.class, () -> v.verifyOrThrow(tampered, sig));

        Path jar = Files.write(dir.resolve("ok.jar"), data);
        ReleaseSignatureVerifier other = ReleaseSignatureVerifier.fromEncodedKey(newKeyPair().getPublic().getEncoded());
        assertThrows(GeneralSecurityException.class, () -> other.verifyOrThrow(jar, sig));

        assertThrows(GeneralSecurityException.class, () -> v.verifyOrThrow(jar, new byte[10]));
        assertThrows(GeneralSecurityException.class, () -> v.verifyOrThrow(jar, "not base64 ***".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void pemAndBareBase64KeyTextBothParse(@TempDir Path dir) throws Exception {
        KeyPair kp = newKeyPair();
        byte[] data = new byte[]{1, 2, 3};
        Path jar = Files.write(dir.resolve("k.jar"), data);
        byte[] sig = sign(kp, data);
        String b64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        ReleaseSignatureVerifier bare = ReleaseSignatureVerifier.fromKeyText(b64 + "\n");
        ReleaseSignatureVerifier pem = ReleaseSignatureVerifier.fromKeyText(
            "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n");
        assertDoesNotThrow(() -> bare.verifyOrThrow(jar, sig));
        assertDoesNotThrow(() -> pem.verifyOrThrow(jar, sig));
    }

    @Test
    void unusableKeyStaysActiveAndFailsClosed(@TempDir Path dir) throws Exception {
        Path jar = Files.write(dir.resolve("x.jar"), new byte[]{9});
        ReleaseSignatureVerifier broken = ReleaseSignatureVerifier.fromKeyText("definitely not a key");
        assertTrue(broken.isActive(), "a present-but-broken key must not silently disable enforcement");
        assertThrows(GeneralSecurityException.class, () -> broken.verifyOrThrow(jar, new byte[64]));
    }
}
