package dev.iyanz.sourbycraft.update;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ViaAutoUpdateTest {
    @TempDir Path plugins;
    private static final byte[] JAR = "verified fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private ViaAutoUpdate.Pin pin(long size, String hash) {
        return new ViaAutoUpdate.Pin("fixture", "ViaVersion-", "ViaVersion-fixture.jar",
            "https://cdn.modrinth.com/fixture.jar", hash, size);
    }

    private String hash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JAR));
    }

    private com.google.gson.JsonArray metadata(String mc) {
        return JsonParser.parseString("""
            [{"version_type":"release","loaders":["paper"],"game_versions":["%s"],
              "files":[{"filename":"ViaVersion-fixture.jar","url":"https://cdn.modrinth.com/fixture.jar",
                        "hashes":{"sha512":"not-trusted"}}]}]
            """.formatted(mc)).getAsJsonArray();
    }

    @Test void refusesVersionsNotListingRunningMinecraft() throws Exception {
        assertNull(ViaAutoUpdate.select(metadata("1.21.8"), "26.2", pin(JAR.length, hash())));
        assertNotNull(ViaAutoUpdate.select(metadata("26.2"), "26.2", pin(JAR.length, hash())));
    }

    @Test void ignoresUnpinnedArtifactsEvenWhenMetadataClaimsCompatibility() throws Exception {
        var versions = metadata("26.2");
        versions.get(0).getAsJsonObject().getAsJsonArray("files").get(0).getAsJsonObject()
            .addProperty("url", "https://attacker.invalid/fixture.jar");
        assertNull(ViaAutoUpdate.select(versions, "26.2", pin(JAR.length, hash())));
    }

    @Test void preservesForeignJarsIncludingConflictingTarget() throws Exception {
        Path foreign = plugins.resolve("ViaVersion-operator.jar");
        Files.writeString(foreign, "operator owned");
        HttpTransport fail = (url, headers) -> { throw new AssertionError("must not download"); };
        assertFalse(ViaAutoUpdate.install(pin(JAR.length, hash()), plugins, fail));
        assertEquals("operator owned", Files.readString(foreign));
        Files.delete(foreign);
        Path target = plugins.resolve("ViaVersion-fixture.jar");
        Files.writeString(target, "foreign target");
        assertFalse(ViaAutoUpdate.install(pin(JAR.length, hash()), plugins, fail));
        assertEquals("foreign target", Files.readString(target));
    }

    @Test void preservesRenamedOperatorJarByPluginDescriptor() throws Exception {
        Path foreign = plugins.resolve("operator-custom-build.jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(foreign))) {
            jar.putNextEntry(new java.util.jar.JarEntry("plugin.yml"));
            jar.write("name: ViaVersion\nversion: custom\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        byte[] original = Files.readAllBytes(foreign);
        assertFalse(ViaAutoUpdate.install(pin(JAR.length, hash()), plugins,
            (url, headers) -> { throw new AssertionError("operator plugin must be preserved"); }));
        assertArrayEquals(original, Files.readAllBytes(foreign));
    }

    @Test void installedTargetSkipsMetadataRequest() throws Exception {
        Path target = plugins.resolve("ViaVersion-fixture.jar");
        Files.writeString(target, "operator target");
        assertFalse(ViaAutoUpdate.check(pin(JAR.length, hash()), "26.2", plugins,
            (url, headers) -> { throw new AssertionError("installed target must skip all HTTP requests: " + url); }));
        assertEquals("operator target", Files.readString(target));
    }

    @Test void renamedInstalledPluginSkipsMetadataRequest() throws Exception {
        Path target = plugins.resolve("renamed.jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(target))) {
            jar.putNextEntry(new java.util.jar.JarEntry("paper-plugin.yml"));
            jar.write("name: ViaVersion\nversion: custom\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        byte[] original = Files.readAllBytes(target);
        assertFalse(ViaAutoUpdate.check(pin(JAR.length, hash()), "26.2", plugins,
            (url, headers) -> { throw new AssertionError("renamed plugin must skip all HTTP requests: " + url); }));
        assertArrayEquals(original, Files.readAllBytes(target));
    }

    @Test void missingPluginRecoversOnlyCompatibleReviewedPin() throws Exception {
        var requests = new java.util.ArrayList<String>();
        HttpTransport transport = (url, headers) -> {
            requests.add(url);
            byte[] body = url.endsWith("/version")
                ? metadata("26.2").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8) : JAR;
            return new HttpTransport.Response(200, Map.of(), new ByteArrayInputStream(body));
        };
        assertTrue(ViaAutoUpdate.check(pin(JAR.length, hash()), "26.2", plugins, transport));
        assertEquals(List.of("https://api.modrinth.com/v2/project/fixture/version",
            "https://cdn.modrinth.com/fixture.jar"), requests);
        assertArrayEquals(JAR, Files.readAllBytes(plugins.resolve("ViaVersion-fixture.jar")));
    }

    @Test void incompatibleMetadataDoesNotDownloadArtifact() throws Exception {
        HttpTransport transport = (url, headers) -> {
            assertEquals("https://api.modrinth.com/v2/project/fixture/version", url);
            return new HttpTransport.Response(200, Map.of(), new ByteArrayInputStream(
                metadata("1.21.8").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        };
        assertFalse(ViaAutoUpdate.check(pin(JAR.length, hash()), "26.2", plugins, transport));
        try (var files = Files.list(plugins)) { assertEquals(0, files.count()); }
    }

    @Test void oversizedMetadataIsRejectedBeforeArtifactDownload() throws Exception {
        HttpTransport transport = (url, headers) -> new HttpTransport.Response(200, Map.of(),
            new ByteArrayInputStream(new byte[4 * 1024 * 1024 + 1]));
        assertThrows(java.io.IOException.class,
            () -> ViaAutoUpdate.check(pin(JAR.length, hash()), "26.2", plugins, transport));
        try (var files = Files.list(plugins)) { assertEquals(0, files.count()); }
    }

    @Test void verifiesPinHashRatherThanApiHashAndIsIdempotent() throws Exception {
        HttpTransport transport = (url, headers) -> new HttpTransport.Response(200, Map.of(), new ByteArrayInputStream(JAR));
        assertTrue(ViaAutoUpdate.install(pin(JAR.length, hash()), plugins, transport));
        assertArrayEquals(JAR, Files.readAllBytes(plugins.resolve("ViaVersion-fixture.jar")));
        assertFalse(ViaAutoUpdate.install(pin(JAR.length, hash()), plugins,
            (url, headers) -> { throw new AssertionError("already installed"); }));
    }

    @Test void rejectsOversizedAndWrongHashAndCleansTemporaryFile() throws Exception {
        HttpTransport transport = (url, headers) -> new HttpTransport.Response(200, Map.of(), new ByteArrayInputStream(JAR));
        assertThrows(java.io.IOException.class, () -> ViaAutoUpdate.install(pin(3, hash()), plugins, transport));
        assertThrows(java.io.IOException.class, () -> ViaAutoUpdate.install(pin(JAR.length, "0".repeat(64)), plugins, transport));
        HttpTransport declared = (url, headers) -> new HttpTransport.Response(200,
            Map.of("content-length", List.of("1000000")), new ByteArrayInputStream(JAR));
        assertThrows(java.io.IOException.class, () -> ViaAutoUpdate.install(pin(JAR.length, hash()), plugins, declared));
        try (var files = Files.list(plugins)) { assertEquals(0, files.count()); }
    }
}
