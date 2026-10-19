package dev.iyanz.sourbycraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.bootstrap.PluginProvisioner.Pin;
import dev.iyanz.sourbycraft.bootstrap.PluginProvisionerTest.StubTransport;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * ProtocolLib pin handling and the two independent {@code auto-provision} toggles. Every transport
 * here is a stub; the real pin bytes (10 MB) are never fetched, so a stub that cannot satisfy the
 * pin's SHA-256 is used to observe WHICH downloads are attempted.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ProtocolLibProvisionerTest {

    @TempDir Path root;
    Path plugins;
    Path config;
    private String savedOffline;

    @BeforeEach void setUp() {
        this.plugins = this.root.resolve("plugins");
        this.config = this.root.resolve("global.toml");
        this.savedOffline = System.getProperty("sourbyclip.offline");
        System.clearProperty("sourbyclip.offline");
    }

    @AfterEach void restore() {
        if (this.savedOffline == null) System.clearProperty("sourbyclip.offline");
        else System.setProperty("sourbyclip.offline", this.savedOffline);
    }

    private static List<String> names(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Fails every fetch, so only the attempted URIs are observable. */
    private static StubTransport refusing() {
        StubTransport t = new StubTransport();
        t.failure = new IOException("stub: no network");
        return t;
    }

    private void toml(boolean protocolLib, boolean via) throws IOException {
        Files.writeString(this.config, "[protocollib]\nauto-provision = " + protocolLib
            + "\n\n[viaversion]\nauto-provision = " + via + "\n");
    }

    // ---- the pin against build-data/protocollib-pin.json --------------------------------

    private static Path pinJson() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve("build-data").resolve("protocollib-pin.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new AssertionError("build-data/protocollib-pin.json not found above " + Path.of("").toAbsolutePath());
    }

    private static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertTrue(m.find(), "missing string field " + key);
        return m.group(1);
    }

    private static long jsonNumber(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        assertTrue(m.find(), "missing number field " + key);
        return Long.parseLong(m.group(1));
    }

    @Test
    void theRuntimePinMatchesTheReviewedJsonMetadata() throws IOException {
        String json = Files.readString(pinJson());
        Pin pin = ProtocolLibProvisioner.PIN;
        assertEquals(jsonString(json, "file_name"), pin.fileName());
        assertEquals(jsonString(json, "download_url"), pin.downloadUrl());
        assertEquals(jsonString(json, "sha256"), pin.sha256());
        assertEquals(jsonNumber(json, "size_bytes"), pin.sizeBytes());
        assertEquals(jsonString(json, "asset_name"), pin.downloadUrl().substring(pin.downloadUrl().lastIndexOf('/') + 1));
        assertTrue(pin.fileName().equals("ProtocolLib-" + jsonString(json, "version") + ".jar"));
        assertTrue(pin.downloadUrl().contains("/download/" + jsonString(json, "version") + "/"),
            "the pin is a tagged release, not a mutable dev-build");
    }

    @Test
    void theRuntimePinIsHttpsAndWellFormed() {
        Pin pin = ProtocolLibProvisioner.PIN;
        assertEquals("ProtocolLib", pin.plugin());
        assertTrue(pin.fileName().startsWith(pin.jarPrefix()) && pin.fileName().endsWith(".jar"));
        assertEquals("https", URI.create(pin.downloadUrl()).getScheme());
        assertFalse(pin.downloadUrl().contains("dev-build"));
        assertTrue(pin.sha256().matches("[0-9a-f]{64}"), pin.sha256());
        assertTrue(pin.sizeBytes() > 0);
        assertEquals(null, pin.configResource(), "ProtocolLib generates its own config; none is seeded");
    }

    // ---- protocollib toggle -------------------------------------------------------------

    @Test
    void enabledAttemptsExactlyTheTrustedUrlBoundedByThePinnedSize() throws IOException {
        toml(true, true);
        StubTransport t = refusing();
        ProtocolLibProvisioner.provision(this.plugins, this.config, t); // failure is swallowed, never thrown
        assertEquals(List.of(URI.create(ProtocolLibProvisioner.PIN.downloadUrl())), t.uris);
        assertEquals(List.of(ProtocolLibProvisioner.PIN.sizeBytes()), t.maxBytes);
        assertEquals(List.of(), names(this.plugins), "a failed install leaves no jar or temp file");
    }

    @Test
    void aBadDownloadIsRejectedAndDoesNotThrowOutOfProvision() throws IOException {
        toml(true, true);
        StubTransport t = new StubTransport();
        t.body = new byte[16]; // wrong size and bytes
        ProtocolLibProvisioner.provision(this.plugins, this.config, t);
        assertEquals(1, t.uris.size());
        assertEquals(List.of(), names(this.plugins));
    }

    @Test
    void defaultsToEnabledWhenTheConfigFileIsAbsent() throws IOException {
        StubTransport t = refusing();
        ProtocolLibProvisioner.provision(this.plugins, this.root.resolve("absent.toml"), t);
        assertEquals(1, t.uris.size());
    }

    @Test
    void disabledMakesNoRequestAndLeavesOperatorJarsAlone() throws IOException {
        toml(false, true);
        Files.createDirectories(this.plugins);
        byte[] operator = "operator-protocollib".getBytes(StandardCharsets.UTF_8);
        Files.write(this.plugins.resolve("ProtocolLib-5.4.0.jar"), operator); // same name, other bytes
        Files.write(this.plugins.resolve("ProtocolLib.jar"), operator);
        StubTransport t = refusing();
        ProtocolLibProvisioner.provision(this.plugins, this.config, t);
        assertTrue(t.uris.isEmpty());
        assertEquals(List.of("ProtocolLib-5.4.0.jar", "ProtocolLib.jar"), names(this.plugins));
        assertArrayEquals(operator, Files.readAllBytes(this.plugins.resolve("ProtocolLib-5.4.0.jar")));
    }

    @Test
    void anOperatorProtocolLibJarPreventsADuplicateInstall() throws IOException {
        toml(true, true);
        Files.createDirectories(this.plugins);
        byte[] operator = "operator-protocollib".getBytes(StandardCharsets.UTF_8);
        Files.write(this.plugins.resolve("ProtocolLib.jar"), operator);
        StubTransport t = refusing();
        ProtocolLibProvisioner.provision(this.plugins, this.config, t);
        assertTrue(t.uris.isEmpty());
        assertEquals(List.of("ProtocolLib.jar"), names(this.plugins));
    }

    @Test
    void aRenamedProtocolLibJarIsRecognisedByItsDescriptor() throws IOException {
        toml(true, true);
        Files.createDirectories(this.plugins);
        Files.write(this.plugins.resolve("packets.jar"),
            PluginProvisionerTest.jarWith("plugin.yml", "name: ProtocolLib\nversion: 5.5.0-custom\n"));
        StubTransport t = refusing();
        ProtocolLibProvisioner.provision(this.plugins, this.config, t);
        assertTrue(t.uris.isEmpty());
        assertEquals(List.of("packets.jar"), names(this.plugins));
    }

    @Test
    void offlineModeMakesNoProtocolLibRequest() throws IOException {
        toml(true, true);
        System.setProperty("sourbyclip.offline", "true");
        StubTransport t = refusing();
        ProtocolLibProvisioner.provision(this.plugins, this.config, t);
        assertTrue(t.uris.isEmpty());
        assertEquals(List.of(), names(this.plugins));
    }

    @Test
    void invalidToggleValueDisablesInstallation() throws IOException {
        Files.writeString(this.config, "[protocollib]\nauto-provision = yes\n");
        StubTransport t = refusing();
        ProtocolLibProvisioner.provision(this.plugins, this.config, t);
        assertTrue(t.uris.isEmpty(), "an unparseable switch must not trigger a download");
    }

    // ---- independence of the two toggles (provisionJars) --------------------------------

    private List<URI> requested(boolean protocolLib, boolean via) throws IOException {
        toml(protocolLib, via);
        StubTransport t = refusing();
        PluginProvisioner.provisionJars(this.plugins, this.config, t);
        return new ArrayList<>(t.uris);
    }

    private static List<URI> viaUris() {
        return PluginProvisioner.viaPins().stream().map(v -> URI.create(v.url())).toList();
    }

    @Test
    void protocolLibOffViaOnFetchesOnlyVia() throws IOException {
        assertEquals(viaUris(), requested(false, true));
    }

    @Test
    void protocolLibOnViaOffFetchesOnlyProtocolLib() throws IOException {
        assertEquals(List.of(URI.create(ProtocolLibProvisioner.PIN.downloadUrl())), requested(true, false));
    }

    @Test
    void bothOnFetchesProtocolLibFirstThenVia() throws IOException {
        List<URI> expected = new ArrayList<>();
        expected.add(URI.create(ProtocolLibProvisioner.PIN.downloadUrl()));
        expected.addAll(viaUris());
        assertEquals(expected, requested(true, true));
    }

    @Test
    void bothOffFetchesNothing() throws IOException {
        assertEquals(List.of(), requested(false, false));
    }

    @Test
    void viaGroupAbsentFromTheFileStillDefaultsToEnabled() throws IOException {
        Files.writeString(this.config, "[protocollib]\nauto-provision = false\n");
        StubTransport t = refusing();
        PluginProvisioner.provisionJars(this.plugins, this.config, t);
        assertEquals(viaUris(), t.uris);
    }

    @Test
    void disablingViaQuarantinesOnlyOurVerifiedViaJars() throws IOException {
        // Use real Via pin names but operator bytes: nothing may be moved (hash differs).
        toml(true, false);
        Files.createDirectories(this.plugins);
        byte[] operator = "operator-via".getBytes(StandardCharsets.UTF_8);
        for (PluginProvisioner.ViaPin via : PluginProvisioner.viaPins()) {
            Files.write(this.plugins.resolve(via.filename()), operator);
        }
        PluginProvisioner.provisionJars(this.plugins, this.config, refusing());
        for (PluginProvisioner.ViaPin via : PluginProvisioner.viaPins()) {
            assertArrayEquals(operator, Files.readAllBytes(this.plugins.resolve(via.filename())), via.filename());
        }
        assertFalse(names(this.plugins).stream().anyMatch(n -> n.endsWith(".disabled")));
    }

    @Test
    void hasViaJarRejectsAnUnknownPrefixAndSeesOperatorJars() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> PluginProvisioner.hasViaJar(this.plugins, "Nope-"));
        assertFalse(PluginProvisioner.hasViaJar(this.plugins, "ViaVersion-"), "missing dir -> false");
        Files.createDirectories(this.plugins);
        assertFalse(PluginProvisioner.hasViaJar(this.plugins, "ViaVersion-"));
        Files.write(this.plugins.resolve("ViaVersion-custom.jar"), new byte[] {1});
        assertTrue(PluginProvisioner.hasViaJar(this.plugins, "ViaVersion-"));
        assertFalse(PluginProvisioner.hasViaJar(this.plugins, "ViaBackwards-"));
    }
}
