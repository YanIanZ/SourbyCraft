package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.update.SourbyUpdater.ApplyMode;
import dev.iyanz.sourbycraft.update.SourbyUpdater.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two JVM properties the local updater rollback fixture uses (disabled by default):
 * the loopback-only release endpoint and the additive test key. The properties are restored
 * after every test.
 */
class UpdateOverridesTest {

    @TempDir Path root;

    @AfterEach
    void clearProperties() {
        System.clearProperty(UpdateOverrides.API_BASE_PROPERTY);
        System.clearProperty(UpdateOverrides.KEY_FILE_PROPERTY);
    }

    // ------------------------------------------------------------------ release endpoint

    @Test
    void unsetOrBlankKeepsTheGitHubEndpoint() {
        assertEquals("https://api.github.com/repos/", UpdateOverrides.reposBase((String) null));
        assertEquals("https://api.github.com/repos/", UpdateOverrides.reposBase(""));
        assertEquals("https://api.github.com/repos/", UpdateOverrides.reposBase("   "));
        assertNull(System.getProperty(UpdateOverrides.API_BASE_PROPERTY), "disabled unless somebody sets it");
        assertEquals("https://api.github.com/repos/", UpdateOverrides.reposBase());
    }

    @Test
    void loopbackBasesAreAcceptedAndNormalised() {
        assertEquals("http://127.0.0.1:8123/repos/", UpdateOverrides.reposBase("http://127.0.0.1:8123"));
        assertEquals("http://127.0.0.1:8123/repos/", UpdateOverrides.reposBase(" http://127.0.0.1:8123/ "));
        assertEquals("http://localhost/repos/", UpdateOverrides.reposBase("HTTP://LocalHost"));
        assertEquals("https://localhost:9/repos/", UpdateOverrides.reposBase("https://localhost:9"));
        assertEquals("http://[::1]:7/repos/", UpdateOverrides.reposBase("http://[::1]:7"));
    }

    @Test
    void everythingThatCouldReachAnotherHostIsRefused() {
        for (String bad : List.of(
                "https://api.github.com", "http://example.com:80", "http://10.0.0.5:80", "http://0.0.0.0:80",
                "http://localhost.evil.example", "http://localhost@evil.example", "http://user:pw@127.0.0.1:80",
                "http://127.0.0.1.evil.example", "http://127.0.0.1:80/x", "http://127.0.0.1:80/?a=b",
                "http://127.0.0.1:80#frag", "ftp://127.0.0.1", "file:///tmp/x", "127.0.0.1:80", "not a url", "http://")) {
            assertThrows(IllegalArgumentException.class, () -> UpdateOverrides.reposBase(bad), bad);
        }
    }

    private SourbyUpdater updater(SourbyUpdaterCheckTest.ScriptedTransport transport) {
        return new SourbyUpdater(root.resolve("auto_update"), transport,
            () -> new BuildInfo("26.2-REL", "47", "47", "26.2", "aurora-nexus", "", ""),
            Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC),
            ReleaseSignatureVerifier.inactive(), new UpdateRollbackGuard(root.resolve("auto_update")));
    }

    @Test
    void anOverrideThatIsSetButRefusedMakesNoRequestAtAll() {
        System.setProperty(UpdateOverrides.API_BASE_PROPERTY, "https://api.github.com");
        SourbyUpdaterCheckTest.ScriptedTransport transport = new SourbyUpdaterCheckTest.ScriptedTransport();

        assertEquals(Outcome.DISABLED, updater(transport).check(ApplyMode.NOTIFY));
        assertTrue(transport.requests.isEmpty(), "a refused override must not fall back to the real endpoint");
    }

    @Test
    void anAcceptedOverrideSendsTheListingToTheLoopbackServerOnly() throws Exception {
        System.setProperty(UpdateOverrides.API_BASE_PROPERTY, "http://127.0.0.1:8123");
        SourbyUpdaterCheckTest.ScriptedTransport transport = new SourbyUpdaterCheckTest.ScriptedTransport();
        byte[] jar = SourbyUpdaterCheckTest.tinyJar();
        String listing = "http://127.0.0.1:8123/repos/YanIanZ/SourbyCraft/releases?per_page=100";
        transport.enqueue(listing, SourbyUpdaterCheckTest.ok(SourbyUpdaterCheckTest.listing(
            SourbyUpdaterCheckTest.release("v26.2-r48", jar.length, SourbyUpdaterCheckTest.sha256(jar), false)), Map.of()));

        assertEquals(Outcome.NOTIFIED, updater(transport).check(ApplyMode.NOTIFY));
        assertEquals(List.of(listing), transport.urls());
    }

    // ------------------------------------------------------------------ test key

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static byte[] sign(KeyPair kp, byte[] data) throws GeneralSecurityException {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(data);
        return s.sign();
    }

    private static String pem(KeyPair kp) {
        return "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(kp.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----\n";
    }

    @Test
    void withoutAKeyOrAFileTheVerifierStaysInactive() {
        assertFalse(ReleaseSignatureVerifier.resolve(null, null).isActive());
        assertFalse(ReleaseSignatureVerifier.resolve(null, "  ").isActive());
        // Since Build 48 the build ships META-INF/sourbycraft-release.pub: then it alone decides.
        boolean shipsKey = ReleaseSignatureVerifier.class.getResource(ReleaseSignatureVerifier.KEY_RESOURCE) != null;
        assertEquals(shipsKey, ReleaseSignatureVerifier.fromClasspath().isActive(),
            "fromClasspath() is active exactly when the build ships a key (no property is set)");
    }

    @Test
    void aKeyFileTurnsEnforcementOnForABuildWithoutAKey() throws Exception {
        KeyPair kp = newKeyPair();
        Path keyFile = Files.writeString(root.resolve("release.pub"), pem(kp));
        byte[] data = "bytes".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(root.resolve("a.jar"), data);

        ReleaseSignatureVerifier v = ReleaseSignatureVerifier.resolve(null, keyFile.toString());
        assertTrue(v.isActive());
        assertDoesNotThrow(() -> v.verifyOrThrow(file, sign(kp, data)));
        byte[] other = sign(newKeyPair(), data);
        assertThrows(GeneralSecurityException.class, () -> v.verifyOrThrow(file, other));

        // fromClasspath reads the property itself.
        System.setProperty(UpdateOverrides.KEY_FILE_PROPERTY, keyFile.toString());
        assertTrue(ReleaseSignatureVerifier.fromClasspath().isActive());
    }

    @Test
    void aShippedKeyCannotBeReplacedFromTheCommandLine() throws Exception {
        KeyPair shipped = newKeyPair();
        KeyPair attacker = newKeyPair();
        Path keyFile = Files.writeString(root.resolve("attacker.pub"), pem(attacker));
        byte[] data = "bytes".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(root.resolve("a.jar"), data);

        ReleaseSignatureVerifier v = ReleaseSignatureVerifier.resolve(pem(shipped), keyFile.toString());
        assertDoesNotThrow(() -> v.verifyOrThrow(file, sign(shipped, data)));
        byte[] forged = sign(attacker, data);
        assertThrows(GeneralSecurityException.class, () -> v.verifyOrThrow(file, forged),
            "the property must not make the attacker's key acceptable");
    }

    @Test
    void anUnusableKeyFileKeepsEnforcementOnAndFailsClosed() throws Exception {
        Path file = Files.write(root.resolve("a.jar"), new byte[]{1});
        byte[] sig = new byte[64];
        Path garbage = Files.writeString(root.resolve("garbage.pub"), "this is not a key");
        Path oversize = Files.write(root.resolve("big.pub"), new byte[17 * 1024]);
        for (String path : List.of(root.resolve("missing.pub").toString(), garbage.toString(), oversize.toString())) {
            ReleaseSignatureVerifier v = ReleaseSignatureVerifier.resolve(null, path);
            assertTrue(v.isActive(), path);
            assertThrows(GeneralSecurityException.class, () -> v.verifyOrThrow(file, sig), path);
        }
    }
}
