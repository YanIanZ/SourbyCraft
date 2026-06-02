package dev.iyanz.sourbycraft.install;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PluginManifestTest {

    @Test
    void parsesGithubEntry() {
        var yaml = """
            plugins:
              - name: SlimeWorldManager
                source: github
                repo: InfernalSuite/SlimeWorldManager
                asset-glob: "swm-*.jar"
            """;
        List<PluginEntry> entries = PluginManifest.parse(new ByteArrayInputStream(yaml.getBytes()));
        assertEquals(1, entries.size());
        PluginEntry e = entries.get(0);
        assertEquals("SlimeWorldManager", e.name());
        assertEquals("github", e.source());
        assertEquals("InfernalSuite/SlimeWorldManager", e.repo());
        assertEquals("swm-*.jar", e.assetGlob());
        assertNull(e.url());
    }

    @Test
    void parsesCiEntry() {
        var yaml = """
            plugins:
              - name: spark
                source: ci
                url: "https://example.com/spark.jar"
            """;
        List<PluginEntry> entries = PluginManifest.parse(new ByteArrayInputStream(yaml.getBytes()));
        assertEquals(1, entries.size());
        assertEquals("https://example.com/spark.jar", entries.get(0).url());
    }

    @Test
    void emptyManifestReturnsEmptyList() {
        var yaml = "plugins: []\n";
        assertTrue(PluginManifest.parse(new ByteArrayInputStream(yaml.getBytes())).isEmpty());
    }

    @Test
    void nullStreamReturnsEmptyList() {
        assertTrue(PluginManifest.parse(null).isEmpty());
    }
}
