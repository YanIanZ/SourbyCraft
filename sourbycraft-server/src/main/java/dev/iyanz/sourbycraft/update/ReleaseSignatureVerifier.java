package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 release-signature check for the auto-updater (audit SEC-2), on the JDK's built-in
 * {@code java.security} provider only — no new dependency.
 *
 * <p><b>Activation is by presence of a public key in the jar.</b> {@link #fromClasspath()} looks
 * for {@value #KEY_RESOURCE}. When the resource is absent the verifier is {@link #isActive()
 * inactive} and {@link SourbyUpdater} stages a release on SHA-256 + zip validity exactly as before.
 * When it is present, every staged release additionally needs a detached signature asset named
 * {@code <jar asset name>}{@value #SIGNATURE_SUFFIX} that verifies over the downloaded bytes; a
 * missing or failing signature refuses the stage. A resource that is present but unparseable keeps
 * the verifier active and failing closed: a build that claims to enforce signatures never silently
 * stops enforcing them.
 *
 * <p><b>This repository ships no key.</b> The resource does not exist in the source tree or the
 * build, so on every current build the verifier is inactive. Turning it on is an owner action:
 * generate an Ed25519 key pair, add the public key as the resource, publish the private key as a CI
 * secret and sign each release asset in the workflow (see {@code docs/development/AUTO-UPDATER.md}).
 *
 * <p>Formats: the key resource is the X.509 {@code SubjectPublicKeyInfo} DER encoding of the public
 * key, base64 text with or without the PEM {@code -----BEGIN PUBLIC KEY-----} armour (what
 * {@code openssl pkey -pubout} writes). The signature asset is the 64 raw bytes
 * {@code openssl pkeyutl -sign -rawin} emits, or that value as base64 text.
 */
public final class ReleaseSignatureVerifier {

    /** Classpath location of the release public key; absent on every build this repository makes. */
    public static final String KEY_RESOURCE = "/META-INF/sourbycraft-release.pub";
    /** Suffix of the detached-signature release asset, appended to the jar asset's name. */
    public static final String SIGNATURE_SUFFIX = ".sig";
    private static final String ALGORITHM = "Ed25519";
    private static final int RAW_SIGNATURE_BYTES = 64;
    private static final long MAX_KEY_FILE_BYTES = 16 * 1024;

    private final @Nullable PublicKey key;
    private final boolean active;
    private final @Nullable String keyProblem;

    private ReleaseSignatureVerifier(@Nullable PublicKey key, boolean active, @Nullable String keyProblem) {
        this.key = key;
        this.active = active;
        this.keyProblem = keyProblem;
    }

    /** A verifier that enforces nothing: the state of every build without a key resource. */
    public static ReleaseSignatureVerifier inactive() {
        return new ReleaseSignatureVerifier(null, false, null);
    }

    /**
     * The verifier for THIS jar: active iff {@value #KEY_RESOURCE} is on the classpath. Logs the
     * resulting state once so an operator can see from the boot log whether releases are
     * signature-checked. A JVM property can add enforcement to a build that ships no key (local
     * fixtures only, see {@link UpdateOverrides}); it never replaces a shipped key.
     */
    public static ReleaseSignatureVerifier fromClasspath() {
        String resourceText = null;
        try (InputStream in = ReleaseSignatureVerifier.class.getResourceAsStream(KEY_RESOURCE)) {
            if (in != null) resourceText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ReleaseSignatureVerifier(null, true, "could not read key resource: " + e.getMessage());
        }
        return resolve(resourceText, System.getProperty(UpdateOverrides.KEY_FILE_PROPERTY));
    }

    /**
     * The decision behind {@link #fromClasspath()}, on plain values. {@code resourceText} is the
     * shipped key ({@code null} = none); {@code keyFile} is the value of
     * {@value UpdateOverrides#KEY_FILE_PROPERTY} ({@code null}/blank = unset).
     * <ol>
     *   <li>Shipped key: it alone decides; a key file is ignored with an ERROR line, because
     *       letting a JVM flag swap the release key would defeat the point of shipping one.</li>
     *   <li>No shipped key, key file set: enforcement ON with that key; an unreadable, oversize or
     *       unparseable file keeps it ON and failing closed.</li>
     *   <li>Neither: inactive.</li>
     * </ol>
     */
    static ReleaseSignatureVerifier resolve(@Nullable String resourceText, @Nullable String keyFile) {
        final boolean fileSet = keyFile != null && !keyFile.isBlank();
        if (resourceText != null) {
            if (fileSet) {
                SourbyLogger.error("auto-updater: -D" + UpdateOverrides.KEY_FILE_PROPERTY + " is IGNORED: this build ships its own "
                    + KEY_RESOURCE + ", which cannot be replaced from the command line.");
            }
            ReleaseSignatureVerifier v = fromKeyText(resourceText);
            if (v.keyProblem != null) {
                SourbyLogger.error("auto-updater: " + KEY_RESOURCE + " is present but unusable (" + v.keyProblem
                    + "); release staging is refused until the build ships a valid key.");
            } else {
                SourbyLogger.info("auto-updater: release signatures ENFORCED (Ed25519 key from " + KEY_RESOURCE + ").");
            }
            return v;
        }
        if (!fileSet) {
            SourbyLogger.info("auto-updater: release signatures NOT enforced (no " + KEY_RESOURCE
                + " in this build); staging relies on the GitHub asset SHA-256 digest.");
            return inactive();
        }
        final Path file = Path.of(keyFile.trim());
        final String text;
        try {
            if (Files.size(file) > MAX_KEY_FILE_BYTES) {
                throw new IOException("larger than " + MAX_KEY_FILE_BYTES + " bytes");
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            SourbyLogger.error("auto-updater: -D" + UpdateOverrides.KEY_FILE_PROPERTY + " names an unusable file (" + e.getMessage()
                + "); release staging is refused.");
            return new ReleaseSignatureVerifier(null, true, "could not read test key file: " + e.getMessage());
        }
        ReleaseSignatureVerifier v = fromKeyText(text);
        if (v.keyProblem != null) {
            SourbyLogger.error("auto-updater: test release key " + file + " is unusable (" + v.keyProblem
                + "); release staging is refused.");
        } else {
            SourbyLogger.info("auto-updater: release signatures ENFORCED (TEST key from -D"
                + UpdateOverrides.KEY_FILE_PROPERTY + "=" + file + "; this build ships no " + KEY_RESOURCE + ").");
        }
        return v;
    }

    /** Parse a base64 / PEM public-key text. Never throws: a bad key yields an active, failing verifier. */
    public static ReleaseSignatureVerifier fromKeyText(String text) {
        try {
            return fromEncodedKey(decodeArmoured(text));
        } catch (IllegalArgumentException e) {
            return new ReleaseSignatureVerifier(null, true, "key is not base64/PEM: " + e.getMessage());
        }
    }

    /** Build an active verifier from the X.509 DER encoding of an Ed25519 public key. */
    public static ReleaseSignatureVerifier fromEncodedKey(byte[] x509Der) {
        try {
            PublicKey pk = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(x509Der));
            return new ReleaseSignatureVerifier(pk, true, null);
        } catch (GeneralSecurityException e) {
            return new ReleaseSignatureVerifier(null, true, "not an Ed25519 X.509 public key: " + e.getMessage());
        }
    }

    /** True when staging requires a verified signature (a key resource exists, valid or not). */
    public boolean isActive() {
        return active;
    }

    /**
     * Verify {@code signatureAsset} (raw 64 bytes or base64 text) over the full contents of
     * {@code file}. Throws with a reason on any failure; returns normally only on a valid signature.
     */
    public void verifyOrThrow(Path file, byte[] signatureAsset) throws IOException, GeneralSecurityException {
        if (!active) return;
        if (key == null) {
            throw new SignatureException("release public key unusable: " + keyProblem);
        }
        byte[] sig = decodeSignature(signatureAsset);
        Signature verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(key);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) verifier.update(buf, 0, n);
        }
        if (!verifier.verify(sig)) {
            throw new SignatureException("Ed25519 signature does not match " + file.getFileName());
        }
    }

    /**
     * Verify {@code signatureAsset} over {@code data} held in memory (the signed release manifest,
     * {@link ReleaseManifest}). Same key, formats and failure rules as {@link #verifyOrThrow(Path, byte[])};
     * an inactive verifier returns without checking.
     */
    public void verifyBytesOrThrow(byte[] data, byte[] signatureAsset) throws GeneralSecurityException {
        if (!active) return;
        if (key == null) {
            throw new SignatureException("release public key unusable: " + keyProblem);
        }
        Signature verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(key);
        verifier.update(data);
        if (!verifier.verify(decodeSignature(signatureAsset))) {
            throw new SignatureException("Ed25519 signature does not match the signed data");
        }
    }

    /** Raw 64-byte signatures pass through; anything else must be base64 text of those 64 bytes. */
    static byte[] decodeSignature(byte[] asset) throws SignatureException {
        if (asset.length == RAW_SIGNATURE_BYTES) return asset;
        byte[] decoded;
        try {
            decoded = decodeArmoured(new String(asset, StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            throw new SignatureException("signature asset is neither 64 raw bytes nor base64 text");
        }
        if (decoded.length != RAW_SIGNATURE_BYTES) {
            throw new SignatureException("signature asset decodes to " + decoded.length + " bytes, expected " + RAW_SIGNATURE_BYTES);
        }
        return decoded;
    }

    /** Strip PEM armour lines and whitespace, then base64-decode. */
    private static byte[] decodeArmoured(String text) {
        StringBuilder b64 = new StringBuilder();
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("-----")) continue;
            b64.append(t);
        }
        return Base64.getMimeDecoder().decode(b64.toString());
    }
}
