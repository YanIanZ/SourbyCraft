package dev.iyanz.sourbycraft.update;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The staging gate for the launcher embedded in a downloaded release jar (SEC-4).
 *
 * <p>A staged jar becomes the {@code -jar} argument of the next boot, so the SourbyClip launcher
 * inside it - not the one that is running now - decides how that boot starts. The operator's start
 * command stays as it is, so the new launcher must still be a launcher this server can hand over to.
 * "Can hand over to" is exactly what the code defines, nothing more:
 * <ul>
 *   <li><b>Protocol</b> - {@link #SUPPORTED_PROTOCOL}. SourbyClip stamps {@code SourbyClip-Protocol}
 *       into its manifest and {@code protocol=} into {@code META-INF/sourbyclip.properties};
 *       SourbyPatcher's {@code VerifySourbyClip} hard-codes {@code "1"} and refuses any other (see
 *       SourbyClip's {@code AGENTS.md}: a protocol bump needs a Patcher release). The protocol is a
 *       change of the launch contract (flags, cache layout), so a jar that declares another one is
 *       a manual upgrade, never an unattended swap.</li>
 *   <li><b>Version</b> - at least {@link #MIN_VERSION}: SourbyClip 3.0.23 is the release in which
 *       the manifest protocol and {@code -Dsourbyclip.offline} first appeared (SourbyClip
 *       {@code readme.md}); an older launcher has no protocol to compare at all.</li>
 *   <li><b>Consistency</b> - the manifest ({@code Clip-Version}, {@code SourbyClip-Protocol}) and
 *       {@code META-INF/sourbyclip.properties} ({@code version}, {@code protocol}) are written by the
 *       same SourbyClip build and must agree. Disagreement means the jar was re-assembled by hand.</li>
 * </ul>
 *
 * <p><b>Missing metadata fails closed.</b> A jar that carries no launcher identity (a pre-3.0.23
 * launcher, a jar that is not a SourbyCraft launcher jar, or a hand-rolled repack) is refused. A
 * staged jar is executed unattended, the updater already refuses an unverifiable digest the same way,
 * and the price of a wrong refusal is one logged line plus a manual jar swap, whereas the price of a
 * wrong acceptance is a server that restarts into a launcher nobody has checked (the rollback guard
 * can restore {@code .prev}, but only after a failed boot). Every release built from this tree has
 * both entries (checked against {@code build/libs/SourbyCraft-slim.jar}, clip 3.1.2, protocol 1).
 *
 * <p>The jar is only read: two small entries, each bounded to {@link #MAX_ENTRY_BYTES}.
 */
final class LauncherCompatibility {

    /** The one launcher protocol this server can hand over to. */
    static final int SUPPORTED_PROTOCOL = 1;
    /** First SourbyClip release that carries the manifest protocol (SourbyClip readme, 3.0.23). */
    static final String MIN_VERSION = "3.0.23";

    static final String PROPERTIES_ENTRY = "META-INF/sourbyclip.properties";
    static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";
    static final String MANIFEST_VERSION_KEY = "Clip-Version";
    static final String MANIFEST_PROTOCOL_KEY = "SourbyClip-Protocol";

    /** Both entries are a few dozen bytes; anything past this is not launcher metadata. */
    static final int MAX_ENTRY_BYTES = 16 * 1024;

    private static final Pattern NUMERIC_VERSION = Pattern.compile("\\d+(\\.\\d+){1,3}");

    private LauncherCompatibility() {}

    enum Reason {
        COMPATIBLE,
        /** An identity entry is absent, or one of its keys is: fail closed. */
        METADATA_MISSING,
        /** An entry exists but cannot be parsed (not a number, not a dotted version, too large). */
        METADATA_UNREADABLE,
        /** The manifest and the properties file disagree. */
        METADATA_INCONSISTENT,
        PROTOCOL_UNSUPPORTED,
        VERSION_TOO_OLD
    }

    /**
     * @param reason  why, {@link Reason#COMPATIBLE} when the jar may be staged
     * @param version the declared launcher version, or {@code null} when it could not be read
     * @param protocol the declared protocol, or {@code null} when it could not be read
     * @param detail  an operator-facing sentence (empty when compatible)
     */
    record Verdict(Reason reason, @Nullable String version, @Nullable Integer protocol, String detail) {
        boolean compatible() {
            return reason == Reason.COMPATIBLE;
        }
    }

    /** Inspect {@code jar}; never throws for a malformed jar - an unreadable archive is a verdict too. */
    static Verdict inspect(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return judge(readProperties(zip), readManifest(zip));
        } catch (IOException | RuntimeException e) {
            return new Verdict(Reason.METADATA_UNREADABLE, null, null,
                "the jar could not be read for launcher metadata (" + e.getMessage() + ")");
        }
    }

    private static Properties readProperties(ZipFile zip) throws IOException {
        byte[] data = readEntry(zip, PROPERTIES_ENTRY);
        if (data == null) return null;
        Properties p = new Properties();
        p.load(new java.io.StringReader(new String(data, StandardCharsets.ISO_8859_1)));
        return p;
    }

    private static Attributes readManifest(ZipFile zip) throws IOException {
        byte[] data = readEntry(zip, MANIFEST_ENTRY);
        if (data == null) return null;
        return new Manifest(new java.io.ByteArrayInputStream(data)).getMainAttributes();
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] data = in.readNBytes(MAX_ENTRY_BYTES + 1);
            if (data.length > MAX_ENTRY_BYTES) {
                throw new IOException(name + " is larger than " + MAX_ENTRY_BYTES + " bytes");
            }
            return data;
        }
    }

    private static Verdict judge(@Nullable Properties props, @Nullable Attributes manifest) {
        String propVersion = props == null ? null : blankToNull(props.getProperty("version"));
        String propProtocol = props == null ? null : blankToNull(props.getProperty("protocol"));
        String manVersion = manifest == null ? null : blankToNull(manifest.getValue(MANIFEST_VERSION_KEY));
        String manProtocol = manifest == null ? null : blankToNull(manifest.getValue(MANIFEST_PROTOCOL_KEY));

        if (propVersion == null || propProtocol == null || manVersion == null || manProtocol == null) {
            StringBuilder missing = new StringBuilder();
            if (propVersion == null) missing.append(' ').append(PROPERTIES_ENTRY).append(":version");
            if (propProtocol == null) missing.append(' ').append(PROPERTIES_ENTRY).append(":protocol");
            if (manVersion == null) missing.append(' ').append(MANIFEST_ENTRY).append(':').append(MANIFEST_VERSION_KEY);
            if (manProtocol == null) missing.append(' ').append(MANIFEST_ENTRY).append(':').append(MANIFEST_PROTOCOL_KEY);
            return new Verdict(Reason.METADATA_MISSING, firstNonNull(propVersion, manVersion), null,
                "the jar does not declare its SourbyClip launcher (missing:" + missing + "); "
                    + "a launcher this server cannot identify is never staged");
        }
        if (!propVersion.equals(manVersion) || !propProtocol.equals(manProtocol)) {
            return new Verdict(Reason.METADATA_INCONSISTENT, propVersion, null,
                "the manifest says SourbyClip " + manVersion + " protocol " + manProtocol + " but "
                    + PROPERTIES_ENTRY + " says " + propVersion + " protocol " + propProtocol);
        }
        Integer protocol;
        try {
            protocol = Integer.valueOf(propProtocol);
        } catch (NumberFormatException e) {
            return new Verdict(Reason.METADATA_UNREADABLE, propVersion, null,
                "the declared SourbyClip protocol '" + propProtocol + "' is not a number");
        }
        if (!NUMERIC_VERSION.matcher(propVersion).matches()) {
            return new Verdict(Reason.METADATA_UNREADABLE, propVersion, protocol,
                "the declared SourbyClip version '" + propVersion + "' is not a dotted number");
        }
        if (protocol != SUPPORTED_PROTOCOL) {
            return new Verdict(Reason.PROTOCOL_UNSUPPORTED, propVersion, protocol,
                "SourbyClip " + propVersion + " speaks launcher protocol " + protocol
                    + "; this server hands over only to protocol " + SUPPORTED_PROTOCOL);
        }
        if (SemVer.compare(propVersion, MIN_VERSION) < 0) {
            return new Verdict(Reason.VERSION_TOO_OLD, propVersion, protocol,
                "SourbyClip " + propVersion + " is older than the minimum " + MIN_VERSION);
        }
        return new Verdict(Reason.COMPATIBLE, propVersion, protocol, "");
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static @Nullable String firstNonNull(@Nullable String a, @Nullable String b) {
        return a != null ? a : b;
    }
}
