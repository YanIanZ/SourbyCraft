package dev.iyanz.sourbycraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * {@link PluginProvisioner} with a stub {@link LibDownloader.Transport}: no sockets, no real
 * network. {@code sourbyclip.offline} is a JVM-wide system property, so it is saved and restored.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class PluginProvisionerTest {

    /** Records every fetch and serves canned bytes (or a canned failure). */
    static final class StubTransport implements LibDownloader.Transport {
        final List<URI> uris = new ArrayList<>();
        final List<Long> maxBytes = new ArrayList<>();
        byte[] body = new byte[0];
        IOException failure;
        /** Optional hook run after the body is written, before fetch returns. */
        Runnable afterWrite;
        /** When true, a partial body is written and then {@link #failure} is thrown. */
        boolean writeBeforeFailing;

        @Override public void fetch(URI uri, Path into, long max) throws IOException {
            this.uris.add(uri);
            this.maxBytes.add(max);
            if (this.failure != null) {
                if (this.writeBeforeFailing) Files.write(into, this.body);
                throw this.failure;
            }
            Files.write(into, this.body);
            if (this.afterWrite != null) this.afterWrite.run();
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static PluginProvisioner.Pin pinFor(byte[] jar, String url) {
        return new PluginProvisioner.Pin("Demo", "Demo-", "Demo-1.0.jar", url, sha256(jar), jar.length, null);
    }

    /** A real zip with the given descriptor entries, so the descriptor scan can read it. */
    static byte[] jarWith(String entryName, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    static final String HTTPS = "https://example.invalid/Demo-1.0.jar";
    static final byte[] PAYLOAD = "pretend-this-is-a-jar".getBytes(StandardCharsets.UTF_8);

    @TempDir Path root;
    Path plugins;
    private String savedOffline;

    @BeforeEach void setUp() {
        this.plugins = this.root.resolve("plugins");
        this.savedOffline = System.getProperty("sourbyclip.offline");
        System.clearProperty("sourbyclip.offline");
    }

    @AfterEach void restore() {
        if (this.savedOffline == null) System.clearProperty("sourbyclip.offline");
        else System.setProperty("sourbyclip.offline", this.savedOffline);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private StubTransport serving(byte[] body) {
        StubTransport t = new StubTransport();
        t.body = body;
        return t;
    }

    // ---- install ------------------------------------------------------------------------

    @Test
    void coldInstallDownloadsOnceVerifiesAndLeavesOnlyTheJar() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pin, this.plugins, t);
        assertEquals(List.of(URI.create(HTTPS)), t.uris);
        assertEquals(List.of((long) PAYLOAD.length), t.maxBytes, "the transport is bounded by the pinned size");
        assertArrayEquals(PAYLOAD, Files.readAllBytes(this.plugins.resolve("Demo-1.0.jar")));
        assertEquals(List.of("Demo-1.0.jar"), names(this.plugins), "no temp or corrupt leftovers");
    }

    @Test
    void anAlreadyVerifiedJarIsNotDownloadedAgain() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        Files.createDirectories(this.plugins);
        Files.write(this.plugins.resolve("Demo-1.0.jar"), PAYLOAD);
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pin, this.plugins, t);
        assertTrue(t.uris.isEmpty());
        assertEquals(List.of("Demo-1.0.jar"), names(this.plugins));
    }

    @Test
    void aVerifiedJarIsUsedEvenOfflineAndEvenWithANonHttpsUrl() throws IOException {
        System.setProperty("sourbyclip.offline", "true");
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, "http://example.invalid/Demo-1.0.jar");
        Files.createDirectories(this.plugins);
        Files.write(this.plugins.resolve("Demo-1.0.jar"), PAYLOAD);
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pin, this.plugins, t);
        assertTrue(t.uris.isEmpty());
    }

    @Test
    void secondRunIsIdempotent() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pin, this.plugins, t);
        byte[] first = Files.readAllBytes(this.plugins.resolve("Demo-1.0.jar"));
        PluginProvisioner.ensureJar(pin, this.plugins, t);
        PluginProvisioner.ensureJar(pin, this.plugins, t);
        assertEquals(1, t.uris.size(), "later runs must not re-download");
        assertArrayEquals(first, Files.readAllBytes(this.plugins.resolve("Demo-1.0.jar")));
        assertEquals(List.of("Demo-1.0.jar"), names(this.plugins));
    }

    // ---- integrity ----------------------------------------------------------------------

    @Test
    void hashMismatchLeavesNoJarAndNoTempFile() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        byte[] tampered = PAYLOAD.clone();
        tampered[0] ^= 1; // same length, different bytes
        StubTransport t = serving(tampered);
        IOException e = assertThrows(IOException.class, () -> PluginProvisioner.ensureJar(pin, this.plugins, t));
        assertTrue(e.getMessage().contains("SHA-256 mismatch"), e.getMessage());
        assertEquals(List.of(), names(this.plugins));
    }

    @Test
    void sizeMismatchLeavesNoJarAndNoTempFile() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        StubTransport longer = serving((new String(PAYLOAD, StandardCharsets.UTF_8) + "x").getBytes(StandardCharsets.UTF_8));
        IOException e = assertThrows(IOException.class, () -> PluginProvisioner.ensureJar(pin, this.plugins, longer));
        assertTrue(e.getMessage().contains("size mismatch"), e.getMessage());
        assertEquals(List.of(), names(this.plugins));
        StubTransport empty = serving(new byte[0]);
        assertThrows(IOException.class, () -> PluginProvisioner.ensureJar(pin, this.plugins, empty));
        assertEquals(List.of(), names(this.plugins));
    }

    @Test
    void transportFailureLeavesNoPartialFile() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        StubTransport t = serving(PAYLOAD);
        t.failure = new IOException("connection reset");
        t.writeBeforeFailing = true;
        IOException e = assertThrows(IOException.class, () -> PluginProvisioner.ensureJar(pin, this.plugins, t));
        assertEquals("connection reset", e.getMessage());
        assertEquals(List.of(), names(this.plugins));
    }

    @Test
    void aFailedDownloadDoesNotPoisonALaterSuccessfulRun() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        StubTransport bad = serving(new byte[PAYLOAD.length]);
        assertThrows(IOException.class, () -> PluginProvisioner.ensureJar(pin, this.plugins, bad));
        StubTransport good = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pin, this.plugins, good);
        assertEquals(List.of("Demo-1.0.jar"), names(this.plugins));
    }

    @Test
    void aCorruptFileWithOurExactNameIsMovedAsideAndReplaced() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        Files.createDirectories(this.plugins);
        Files.write(this.plugins.resolve("Demo-1.0.jar"), "corrupt".getBytes(StandardCharsets.UTF_8));
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pin, this.plugins, t);
        assertEquals(1, t.uris.size());
        assertArrayEquals(PAYLOAD, Files.readAllBytes(this.plugins.resolve("Demo-1.0.jar")));
        List<String> left = names(this.plugins);
        assertEquals(2, left.size(), left.toString());
        String aside = left.stream().filter(n -> !n.equals("Demo-1.0.jar")).findFirst().orElseThrow();
        assertTrue(aside.endsWith(".corrupt"), "the bad file must not stay a loadable *.jar: " + aside);
    }

    // ---- https-only ---------------------------------------------------------------------

    @Test
    void nonHttpsSchemesAreRefusedBeforeAnyFetchOrFileCreation() throws IOException {
        for (String url : new String[] {
            "http://example.invalid/Demo-1.0.jar", "ftp://example.invalid/Demo-1.0.jar",
            "file:///etc/passwd", "jar:file:///x.jar!/y", "//example.invalid/Demo-1.0.jar", "Demo-1.0.jar"}) {
            PluginProvisioner.Pin pin = pinFor(PAYLOAD, url);
            StubTransport t = serving(PAYLOAD);
            IOException e = assertThrows(IOException.class,
                () -> PluginProvisioner.ensureJar(pin, this.plugins, t), url);
            assertTrue(e.getMessage().contains("non-https"), url + " -> " + e.getMessage());
            assertTrue(t.uris.isEmpty(), url);
            assertEquals(List.of(), names(this.plugins), url);
        }
    }

    @Test
    void httpsSchemeIsCaseInsensitive() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, "HTTPS://example.invalid/Demo-1.0.jar");
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pin, this.plugins, t);
        assertEquals(1, t.uris.size());
    }

    // ---- offline ------------------------------------------------------------------------

    @Test
    void offlineModeRefusesTheDownloadAndCreatesNoJar() throws IOException {
        System.setProperty("sourbyclip.offline", "true");
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        StubTransport t = serving(PAYLOAD);
        IOException e = assertThrows(IOException.class, () -> PluginProvisioner.ensureJar(pin, this.plugins, t));
        assertTrue(e.getMessage().contains("Offline mode"), e.getMessage());
        assertTrue(t.uris.isEmpty());
        assertEquals(List.of(), names(this.plugins));
    }

    @Test
    void offlineModeStillRestoresAQuarantinedVerifiedJarAndKeepsOperatorJars() throws IOException {
        System.setProperty("sourbyclip.offline", "true");
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        Files.createDirectories(this.plugins);
        Files.write(this.plugins.resolve("Demo-1.0.jar.disabled"), PAYLOAD);
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pin, this.plugins, t);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(this.plugins.resolve("Demo-1.0.jar")));
        assertFalse(Files.exists(this.plugins.resolve("Demo-1.0.jar.disabled")));

        Path other = this.root.resolve("p2");
        Files.createDirectories(other);
        Files.write(other.resolve("Demo-custom.jar"), "operator".getBytes(StandardCharsets.UTF_8));
        PluginProvisioner.ensureJar(pin, other, t);
        assertEquals(List.of("Demo-custom.jar"), names(other));
        assertTrue(t.uris.isEmpty());
    }

    @Test
    void offlinePropertySetToFalseDoesNotBlockDownloads() throws IOException {
        System.setProperty("sourbyclip.offline", "false");
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pinFor(PAYLOAD, HTTPS), this.plugins, t);
        assertEquals(1, t.uris.size());
    }

    // ---- operator jars and quarantine ---------------------------------------------------

    @Test
    void anOperatorJarWithTheSamePrefixIsPreservedWithoutDownload() throws IOException {
        Files.createDirectories(this.plugins);
        byte[] operator = "operator-build".getBytes(StandardCharsets.UTF_8);
        Files.write(this.plugins.resolve("Demo-9.9-custom.jar"), operator);
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pinFor(PAYLOAD, HTTPS), this.plugins, t);
        assertTrue(t.uris.isEmpty());
        assertEquals(List.of("Demo-9.9-custom.jar"), names(this.plugins));
        assertArrayEquals(operator, Files.readAllBytes(this.plugins.resolve("Demo-9.9-custom.jar")));
    }

    @Test
    void aRenamedJarIsRecognisedByItsPluginDescriptor() throws IOException {
        for (String descriptor : new String[] {"plugin.yml", "paper-plugin.yml"}) {
            Path dir = this.root.resolve("d-" + descriptor);
            Files.createDirectories(dir);
            Files.write(dir.resolve("something-else.jar"), jarWith(descriptor, "main: x.Y\nname: Demo\nversion: 1\n"));
            StubTransport t = serving(PAYLOAD);
            PluginProvisioner.ensureJar(pinFor(PAYLOAD, HTTPS), dir, t);
            assertTrue(t.uris.isEmpty(), descriptor);
            assertEquals(List.of("something-else.jar"), names(dir), descriptor);
        }
    }

    @Test
    void aQuotedDescriptorNameAndCaseInsensitiveKeyAreRecognised() throws IOException {
        Files.createDirectories(this.plugins);
        Files.write(this.plugins.resolve("x.jar"), jarWith("plugin.yml", "Name: \"Demo\" # c\n"));
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pinFor(PAYLOAD, HTTPS), this.plugins, t);
        assertTrue(t.uris.isEmpty());
    }

    @Test
    void aJarForADifferentPluginDoesNotBlockInstallation() throws IOException {
        Files.createDirectories(this.plugins);
        Files.write(this.plugins.resolve("unrelated.jar"), jarWith("plugin.yml", "name: DemoExtra\n"));
        Files.write(this.plugins.resolve("broken.jar"), "not a zip".getBytes(StandardCharsets.UTF_8));
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pinFor(PAYLOAD, HTTPS), this.plugins, t);
        assertEquals(1, t.uris.size());
        assertTrue(Files.exists(this.plugins.resolve("Demo-1.0.jar")));
    }

    @Test
    void aPluginThatAppearsDuringTheDownloadIsNotReplaced() throws IOException {
        Files.createDirectories(this.plugins);
        byte[] operator = "operator-arrived".getBytes(StandardCharsets.UTF_8);
        StubTransport t = serving(PAYLOAD);
        t.afterWrite = () -> {
            try {
                Files.write(this.plugins.resolve("Demo-operator.jar"), operator);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        PluginProvisioner.ensureJar(pinFor(PAYLOAD, HTTPS), this.plugins, t);
        assertEquals(List.of("Demo-operator.jar"), names(this.plugins), "temp file removed, pinned jar not added");
        assertArrayEquals(operator, Files.readAllBytes(this.plugins.resolve("Demo-operator.jar")));
    }

    @Test
    void quarantineMovesOnlyOurVerifiedJarAndRestoreBringsItBackWithoutNetwork() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        Files.createDirectories(this.plugins);
        Files.write(this.plugins.resolve("Demo-1.0.jar"), PAYLOAD);
        PluginProvisioner.quarantineProvisionedJars(this.plugins, List.of(pin));
        assertEquals(List.of("Demo-1.0.jar.disabled"), names(this.plugins));
        StubTransport t = serving(PAYLOAD);
        PluginProvisioner.ensureJar(pin, this.plugins, t);
        assertTrue(t.uris.isEmpty());
        assertEquals(List.of("Demo-1.0.jar"), names(this.plugins));
    }

    @Test
    void quarantineLeavesAJarWithADifferentHashAlone() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        Files.createDirectories(this.plugins);
        byte[] operator = "operator".getBytes(StandardCharsets.UTF_8);
        Files.write(this.plugins.resolve("Demo-1.0.jar"), operator);
        PluginProvisioner.quarantineProvisionedJars(this.plugins, List.of(pin));
        assertEquals(List.of("Demo-1.0.jar"), names(this.plugins));
        assertArrayEquals(operator, Files.readAllBytes(this.plugins.resolve("Demo-1.0.jar")));
    }

    @Test
    void quarantineNeverOverwritesADifferingExistingQuarantineFile() throws IOException {
        PluginProvisioner.Pin pin = pinFor(PAYLOAD, HTTPS);
        Files.createDirectories(this.plugins);
        byte[] other = "someone-elses-backup".getBytes(StandardCharsets.UTF_8);
        Files.write(this.plugins.resolve("Demo-1.0.jar"), PAYLOAD);
        Files.write(this.plugins.resolve("Demo-1.0.jar.disabled"), other);
        PluginProvisioner.quarantineProvisionedJars(this.plugins, List.of(pin));
        assertArrayEquals(other, Files.readAllBytes(this.plugins.resolve("Demo-1.0.jar.disabled")));
        assertArrayEquals(PAYLOAD, Files.readAllBytes(this.plugins.resolve("Demo-1.0.jar")));
    }

    @Test
    void quarantineOnAMissingPluginsDirectoryIsANoOp() throws IOException {
        PluginProvisioner.quarantineProvisionedJars(this.plugins, List.of(pinFor(PAYLOAD, HTTPS)));
        assertFalse(Files.exists(this.plugins), "quarantine must not create plugins/");
    }

    // ---- user config is never overwritten -----------------------------------------------

    @Test
    void ensureJarNeverTouchesThePluginsConfigDirectory() throws IOException {
        Path cfg = this.plugins.resolve("Demo").resolve("config.yml");
        Files.createDirectories(cfg.getParent());
        byte[] mine = "user: edited\n".getBytes(StandardCharsets.UTF_8);
        Files.write(cfg, mine);
        PluginProvisioner.ensureJar(pinFor(PAYLOAD, HTTPS), this.plugins, serving(PAYLOAD));
        assertArrayEquals(mine, Files.readAllBytes(cfg));
        assertEquals(List.of("Demo", "Demo-1.0.jar"), names(this.plugins));
    }

    @Test
    void ensureConfigSeedsAnAbsentConfigThenNeverOverwritesIt() throws IOException {
        PluginProvisioner.Pin pin = new PluginProvisioner.Pin("ViaVersion", "ViaVersion-", "ViaVersion-x.jar",
            HTTPS, "0".repeat(64), 1L, "/sourbycraft/via/viaversion-config.yml");
        Path cfg = this.plugins.resolve("ViaVersion").resolve("config.yml");
        PluginProvisioner.ensureConfig(pin, this.plugins);
        assertTrue(Files.size(cfg) > 0, "the shipped default is written when absent");

        byte[] mine = "operator: edit\n".getBytes(StandardCharsets.UTF_8);
        Files.write(cfg, mine);
        PluginProvisioner.ensureConfig(pin, this.plugins);
        PluginProvisioner.ensureConfig(pin, this.plugins);
        assertArrayEquals(mine, Files.readAllBytes(cfg), "operator edits survive every later run");
    }

    @Test
    void ensureConfigWithoutAResourceOrWithAMissingOneWritesNothing() throws IOException {
        PluginProvisioner.Pin none = pinFor(PAYLOAD, HTTPS); // configResource == null
        PluginProvisioner.ensureConfig(none, this.plugins);
        PluginProvisioner.Pin missing = new PluginProvisioner.Pin("Demo", "Demo-", "Demo-1.0.jar", HTTPS,
            "0".repeat(64), 1L, "/sourbycraft/via/does-not-exist.yml");
        PluginProvisioner.ensureConfig(missing, this.plugins);
        assertFalse(Files.exists(this.plugins.resolve("Demo")));
    }

    @Test
    void theShippedViaDefaultsExistForEveryViaPin() throws IOException {
        for (PluginProvisioner.ViaPin via : PluginProvisioner.viaPins()) {
            String plugin = via.prefix().substring(0, via.prefix().length() - 1);
            String resource = "/sourbycraft/via/" + plugin.toLowerCase(java.util.Locale.ROOT) + "-config.yml";
            assertTrue(PluginProvisioner.class.getResource(resource) != null, resource);
        }
    }

    @Test
    void viaPinsAreHttpsSha256PinnedAndSized() {
        for (PluginProvisioner.ViaPin via : PluginProvisioner.viaPins()) {
            assertTrue(via.url().startsWith("https://"), via.url());
            assertTrue(via.sha256().matches("[0-9a-f]{64}"), via.sha256());
            assertTrue(via.size() > 0);
            assertTrue(via.filename().startsWith(via.prefix()) && via.filename().endsWith(".jar"), via.filename());
        }
    }
}
