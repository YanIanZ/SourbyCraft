package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.update.LauncherCompatibility.Reason;
import dev.iyanz.sourbycraft.update.LauncherCompatibility.Verdict;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.jar;
import static dev.iyanz.sourbycraft.update.SourbyUpdaterCheckTest.launcherJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher staging gate (SEC-4) on jars built in a temp directory: accept, every reject reason,
 * and the fail-closed handling of missing or malformed metadata.
 */
class LauncherCompatibilityTest {

    @TempDir Path dir;

    private Verdict inspect(byte[] jar) throws IOException {
        return LauncherCompatibility.inspect(Files.write(dir.resolve("candidate.jar"), jar));
    }

    private static String manifest(String version, String protocol) {
        return "Manifest-Version: 1.0\n"
            + (version == null ? "" : "Clip-Version: " + version + "\n")
            + (protocol == null ? "" : "SourbyClip-Protocol: " + protocol + "\n");
    }

    private static String props(String version, String protocol) {
        return (version == null ? "" : "version=" + version + "\n")
            + (protocol == null ? "" : "protocol=" + protocol + "\n");
    }

    // ---------------------------------------------------------------- accept

    @Test
    void theLauncherThisTreePinsIsAccepted() throws IOException {
        Verdict v = inspect(launcherJar("3.1.2", "1"));
        assertTrue(v.compatible(), v.detail());
        assertEquals("3.1.2", v.version());
        assertEquals(1, v.protocol());
    }

    @Test
    void theMinimumVersionItselfIsAccepted() throws IOException {
        assertTrue(inspect(launcherJar(LauncherCompatibility.MIN_VERSION, "1")).compatible());
    }

    @Test
    void versionsCompareNumericallyNotLexically() throws IOException {
        // "3.10.0" < "3.9.0" as text; as numbers it is newer than the 3.0.23 floor.
        assertTrue(inspect(launcherJar("3.10.0", "1")).compatible());
        assertEquals(Reason.VERSION_TOO_OLD, inspect(launcherJar("3.0.9", "1")).reason());
    }

    @Test
    void crlfAndPaddedValuesInTheMetadataAreTolerated() throws IOException {
        Verdict v = inspect(jar("Manifest-Version: 1.0\r\nClip-Version: 3.1.2\r\nSourbyClip-Protocol: 1\r\n",
            "version = 3.1.2\r\nprotocol = 1\r\n"));
        assertTrue(v.compatible(), v.detail());
    }

    @Test
    void theBuiltLauncherJarIsAcceptedWhenOneIsPresent() {
        // Evidence against the real artifact; skipped when the jar has not been built here.
        Path built = null;
        for (Path d = Path.of("").toAbsolutePath(); d != null && built == null; d = d.getParent()) {
            Path candidate = d.resolve("build").resolve("libs").resolve("SourbyCraft-slim.jar");
            if (Files.isRegularFile(candidate)) built = candidate;
        }
        Assumptions.assumeTrue(built != null, "no build/libs/SourbyCraft-slim.jar above " + Path.of("").toAbsolutePath());
        Verdict v = LauncherCompatibility.inspect(built);
        assertTrue(v.compatible(), v.reason() + ": " + v.detail());
    }

    // ---------------------------------------------------------------- reject

    @Test
    void anotherProtocolIsRefused() throws IOException {
        Verdict v = inspect(launcherJar("3.1.2", "2"));
        assertEquals(Reason.PROTOCOL_UNSUPPORTED, v.reason());
        assertEquals(2, v.protocol());
        assertTrue(v.detail().contains("protocol 2"), v.detail());
    }

    @Test
    void protocolZeroIsRefusedToo() throws IOException {
        assertEquals(Reason.PROTOCOL_UNSUPPORTED, inspect(launcherJar("3.1.2", "0")).reason());
    }

    @Test
    void aLauncherOlderThanTheMinimumIsRefused() throws IOException {
        Verdict v = inspect(launcherJar("3.0.22", "1"));
        assertEquals(Reason.VERSION_TOO_OLD, v.reason());
        assertTrue(v.detail().contains(LauncherCompatibility.MIN_VERSION), v.detail());
    }

    @Test
    void theProtocolIsCheckedBeforeTheVersion() throws IOException {
        assertEquals(Reason.PROTOCOL_UNSUPPORTED, inspect(launcherJar("3.0.1", "7")).reason());
    }

    @Test
    void aManifestAndPropertiesThatDisagreeAreRefused() throws IOException {
        assertEquals(Reason.METADATA_INCONSISTENT,
            inspect(jar(manifest("3.1.2", "1"), props("3.1.1", "1"))).reason());
        assertEquals(Reason.METADATA_INCONSISTENT,
            inspect(jar(manifest("3.1.2", "1"), props("3.1.2", "2"))).reason());
    }

    @Test
    void nonNumericMetadataIsRefused() throws IOException {
        assertEquals(Reason.METADATA_UNREADABLE, inspect(launcherJar("3.1.2", "one")).reason());
        assertEquals(Reason.METADATA_UNREADABLE, inspect(launcherJar("3.1.2-SNAPSHOT", "1")).reason());
        assertEquals(Reason.METADATA_UNREADABLE, inspect(launcherJar("latest", "1")).reason());
        assertEquals(Reason.METADATA_UNREADABLE, inspect(launcherJar("3", "1")).reason(),
            "a bare major is not a launcher release number");
    }

    // ---------------------------------------------------------------- missing metadata fails closed

    @Test
    void aJarWithNoLauncherEntriesAtAllFailsClosed() throws IOException {
        Verdict v = inspect(jar("Manifest-Version: 1.0\n", null));
        assertEquals(Reason.METADATA_MISSING, v.reason());
        assertFalse(v.compatible());
        assertNull(v.protocol());
    }

    @Test
    void theShapeOfTheOldReleaseJarFailsClosed() throws IOException {
        // release/SourbyCraft-26.2-REL.jar: Clip-Version only, no protocol, no properties file.
        Verdict v = inspect(jar("Manifest-Version: 1.0\nClip-Version: 3.0.21\n", null));
        assertEquals(Reason.METADATA_MISSING, v.reason());
        assertEquals("3.0.21", v.version(), "the version that was readable is still reported");
    }

    @Test
    void everySingleMissingKeyFailsClosed() throws IOException {
        for (String[] m : new String[][] {
            {null, "1", "3.1.2", "1"}, {"3.1.2", null, "3.1.2", "1"},
            {"3.1.2", "1", null, "1"}, {"3.1.2", "1", "3.1.2", null}}) {
            Verdict v = inspect(jar(manifest(m[0], m[1]), props(m[2], m[3])));
            assertEquals(Reason.METADATA_MISSING, v.reason(), String.join(",",
                String.valueOf(m[0]), String.valueOf(m[1]), String.valueOf(m[2]), String.valueOf(m[3])));
        }
    }

    @Test
    void aManifestWithoutAPropertiesFileFailsClosed() throws IOException {
        assertEquals(Reason.METADATA_MISSING, inspect(jar(manifest("3.1.2", "1"), null)).reason());
    }

    @Test
    void aPropertiesFileWithoutAManifestFailsClosed() throws IOException {
        assertEquals(Reason.METADATA_MISSING, inspect(jar(null, props("3.1.2", "1"))).reason());
    }

    @Test
    void blankValuesCountAsMissing() throws IOException {
        assertEquals(Reason.METADATA_MISSING,
            inspect(jar(manifest("3.1.2", "1"), "version=\nprotocol=1\n")).reason());
    }

    // ---------------------------------------------------------------- hostile or broken archives

    @Test
    void notAZipIsAVerdictNotAnException() throws IOException {
        Verdict v = inspect("definitely not a jar".getBytes(StandardCharsets.UTF_8));
        assertEquals(Reason.METADATA_UNREADABLE, v.reason());
        assertFalse(v.compatible());
    }

    @Test
    void anOversizedMetadataEntryIsRefusedWithoutBeingReadWhole() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write(manifest("3.1.2", "1").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/sourbyclip.properties"));
            zip.write(new byte[LauncherCompatibility.MAX_ENTRY_BYTES + 1]);
            zip.closeEntry();
        }
        assertEquals(Reason.METADATA_UNREADABLE, inspect(bytes.toByteArray()).reason());
    }

    @Test
    void aDirectoryEntryNamedLikeTheMetadataIsNotMetadata() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write(manifest("3.1.2", "1").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/sourbyclip.properties/"));
            zip.closeEntry();
        }
        assertEquals(Reason.METADATA_MISSING, inspect(bytes.toByteArray()).reason());
    }

    @Test
    void theSupportedProtocolIsTheOneThePatcherHardCodes() {
        // SourbyPatcher's VerifySourbyClip hard-codes "1" (SourbyClip AGENTS.md); raising this
        // constant without a Patcher release would accept launchers the build itself refuses.
        assertEquals(1, LauncherCompatibility.SUPPORTED_PROTOCOL);
        assertEquals("3.0.23", LauncherCompatibility.MIN_VERSION);
    }
}
