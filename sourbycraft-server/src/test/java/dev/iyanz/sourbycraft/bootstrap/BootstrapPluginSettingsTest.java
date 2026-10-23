package dev.iyanz.sourbycraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_ERR)
class BootstrapPluginSettingsTest {
    @TempDir Path dir;

    private Path toml(String body) throws IOException {
        return Files.writeString(this.dir.resolve("g.toml"), body);
    }

    private boolean on(String body, String group, boolean fallback) throws IOException {
        return BootstrapPluginSettings.enabled(toml(body), group, fallback);
    }

    /** Runs {@code r} while capturing System.err; restores it afterwards. */
    private String captureErr(Runnable r) {
        PrintStream old = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true));
        try {
            r.run();
        } finally {
            System.setErr(old);
        }
        return buf.toString();
    }

    @Test
    void missingFileYieldsTheFallbackEitherWay() {
        Path none = this.dir.resolve("absent.toml");
        assertTrue(BootstrapPluginSettings.enabled(none, "protocollib", true));
        assertFalse(BootstrapPluginSettings.enabled(none, "protocollib", false));
    }

    @Test
    void emptyFileOrAbsentKeyYieldsTheFallback() throws IOException {
        assertTrue(on("", "protocollib", true));
        assertFalse(on("", "protocollib", false));
        assertTrue(on("[protocollib]\nother = false\n", "protocollib", true));
        assertTrue(on("[viaversion]\nauto-provision = false\n", "protocollib", true));
    }

    @Test
    void explicitTrueAndFalseInTheGroupTable() throws IOException {
        assertTrue(on("[protocollib]\nauto-provision = true\n", "protocollib", false));
        assertFalse(on("[protocollib]\nauto-provision = false\n", "protocollib", true));
    }

    @Test
    void groupsAreIndependent() throws IOException {
        String body = "[protocollib]\nauto-provision = false\n\n[viaversion]\nauto-provision = true\n";
        assertFalse(on(body, "protocollib", true));
        assertTrue(on(body, "viaversion", false));
        String reversed = "[viaversion]\nauto-provision = false\n[protocollib]\nauto-provision = true\n";
        assertTrue(on(reversed, "protocollib", false));
        assertFalse(on(reversed, "viaversion", true));
    }

    @Test
    void sameKeyInAnotherTableIsIgnored() throws IOException {
        String body = "[other]\nauto-provision = false\n[protocollib]\nx = 1\n";
        assertTrue(on(body, "protocollib", true));
        // A sub-table is not the group table.
        assertTrue(on("[protocollib.sub]\nauto-provision = false\n", "protocollib", true));
    }

    @Test
    void dottedRootKeyIsAccepted() throws IOException {
        assertFalse(on("protocollib.auto-provision = false\n", "protocollib", true));
        assertTrue(on("protocollib.auto-provision = true\n", "protocollib", false));
    }

    @Test
    void dottedKeyInsideAnotherTableIsNotTheRootKey() throws IOException {
        // TOML: inside [other] this means other.protocollib.auto-provision.
        assertTrue(on("[other]\nprotocollib.auto-provision = false\n", "protocollib", true));
    }

    @Test
    void quotedTableAndKeyNamesAreUnquoted() throws IOException {
        assertFalse(on("[\"protocollib\"]\nauto-provision = false\n", "protocollib", true));
        assertFalse(on("['protocollib']\n'auto-provision' = false\n", "protocollib", true));
        assertFalse(on("[protocollib]\n\"auto-provision\" = false\n", "protocollib", true));
    }

    @Test
    void whitespaceAndCommentsAreTolerated() throws IOException {
        String body = "# header comment\n  [ protocollib ]   # table comment\n\t auto-provision\t=\t false   # off\n";
        assertFalse(on(body, "protocollib", true));
        assertTrue(on("[protocollib]\n# auto-provision = false\nauto-provision=true\n", "protocollib", false));
    }

    @Test
    void crlfLineEndingsWork() throws IOException {
        assertFalse(on("[protocollib]\r\nauto-provision = false\r\n", "protocollib", true));
    }

    @Test
    void aUtf8ByteOrderMarkBeforeTheFirstLineIsIgnored() throws IOException {
        // Editors on Windows save "UTF-8 with BOM"; readAllLines keeps U+FEFF on the first line.
        assertFalse(on("\uFEFF[protocollib]\nauto-provision = false\n", "protocollib", true));
        assertFalse(on("\uFEFFprotocollib.auto-provision = false\n", "protocollib", true));
        assertFalse(on("\uFEFF# settings\n[protocollib]\nauto-provision = false\n", "protocollib", true));
        assertTrue(on("\uFEFF", "protocollib", true));
        assertFalse(on("\uFEFF", "protocollib", false));
    }

    @Test
    void aByteOrderMarkLaterInTheFileMakesItUnparseable() throws IOException {
        // U+FEFF later in the file is not a BOM; a real TOML parser refuses the key, and provisioning
        // fails closed rather than guessing what the line meant.
        assertFalse(on("[protocollib]\n\uFEFFauto-provision = false\n", "protocollib", true));
    }

    @Test
    void invalidValueDisablesInstallationAndWarns() throws IOException {
        Path cfg = toml("[protocollib]\nauto-provision = maybe\n");
        boolean[] result = new boolean[1];
        String err = captureErr(() -> result[0] = BootstrapPluginSettings.enabled(cfg, "protocollib", true));
        assertFalse(result[0], "an unparseable value must not silently install");
        assertTrue(err.contains("Cannot read plugin provisioning settings"), err);
    }

    @Test
    void quotedBooleanIsInvalidNotTrue() throws IOException {
        Path cfg = toml("[protocollib]\nauto-provision = \"true\"\n");
        boolean[] result = new boolean[] {true};
        captureErr(() -> result[0] = BootstrapPluginSettings.enabled(cfg, "protocollib", true));
        assertFalse(result[0]);
    }

    @Test
    void emptyValueIsInvalid() throws IOException {
        Path cfg = toml("[protocollib]\nauto-provision =\n");
        boolean[] result = new boolean[] {true};
        captureErr(() -> result[0] = BootstrapPluginSettings.enabled(cfg, "protocollib", true));
        assertFalse(result[0]);
    }

    @Test
    void capitalisedBooleanIsInvalidTomlAndDisables() throws IOException {
        Path cfg = toml("[protocollib]\nauto-provision = True\n");
        boolean[] result = new boolean[] {true};
        captureErr(() -> result[0] = BootstrapPluginSettings.enabled(cfg, "protocollib", true));
        assertFalse(result[0]);
    }

    @Test
    void unreadableConfigDisablesInstallationAndWarns() throws IOException {
        // A directory exists() but cannot be read as lines -> IOException path.
        Path asDir = Files.createDirectory(this.dir.resolve("cfg-dir"));
        boolean[] result = new boolean[] {true};
        String err = captureErr(() -> result[0] = BootstrapPluginSettings.enabled(asDir, "protocollib", true));
        assertFalse(result[0]);
        assertTrue(err.contains("Cannot read plugin provisioning settings"), err);
    }

    @Test
    void firstMatchingKeyWins() throws IOException {
        String body = "[protocollib]\nauto-provision = false\nauto-provision = true\n";
        assertFalse(on(body, "protocollib", true));
    }

    @Test
    void theShippedDefaultNamesTheSameGroupsTheCodeReads() throws IOException {
        // Both documented groups resolve independently from one realistic file.
        String body = "[misc]\nx = 1\n\n[protocollib]\nauto-provision = true\n\n[viaversion]\nauto-provision = true\n";
        assertEquals(true, on(body, "protocollib", false));
        assertEquals(true, on(body, "viaversion", false));
    }

    // ------------------------------------------------------------ Build 49 layout ([plugins.<group>])

    @Test
    void theBuild49TableSpellingIsRead() throws IOException {
        assertFalse(on("[plugins.protocollib]\nauto-provision = false\n", "protocollib", true));
        assertTrue(on("[plugins.protocollib]\nauto-provision = true\n", "protocollib", false));
        assertFalse(on("[plugins]\nprotocollib.auto-provision = false\n", "protocollib", true));
        assertFalse(on("plugins.protocollib.auto-provision = false\n", "protocollib", true));
        assertTrue(on("[plugins.viaversion]\nauto-provision = false\n", "protocollib", true), "another group's table is not mine");
        assertTrue(on("[plugins.protocollib]\nother = false\n", "protocollib", true));
    }

    @Test
    void theBuild49SpellingWinsOverThePreBuild49OneWhateverTheOrder() throws IOException {
        assertTrue(on("[protocollib]\nauto-provision = false\n\n[plugins.protocollib]\nauto-provision = true\n", "protocollib", false));
        assertTrue(on("[plugins.protocollib]\nauto-provision = true\n\n[protocollib]\nauto-provision = false\n", "protocollib", false));
        assertFalse(on("[plugins.protocollib]\nauto-provision = false\n\n[protocollib]\nauto-provision = true\n", "protocollib", true));
    }

    @Test
    void anUnparseableBuild49ValueDisablesInstallationAndNamesTheFile() throws IOException {
        Path cfg = toml("[plugins.protocollib]\nauto-provision = maybe\n");
        java.io.PrintStream previous = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true));
        try {
            assertFalse(BootstrapPluginSettings.enabled(cfg, "protocollib", true));
        } finally {
            System.setErr(previous);
        }
        assertTrue(captured.toString().contains("Cannot read plugin provisioning settings"), captured.toString());
        assertTrue(captured.toString().contains("g.toml"), captured.toString());
    }
}
