package dev.iyanz.sourbycraft.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cold, warm and damaged-cache boots all produce the same index; only the cost differs. */
class PluginStartupIndexTest {

    private static final CacheEnvironment ENV = new CacheEnvironment("26.2", 1, 1, 1, 25);

    @TempDir Path dir;

    private Path plugins() throws IOException {
        return Files.createDirectories(this.dir.resolve("plugins"));
    }

    private static void jar(final Path file, final String entry, final String yaml) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            if (entry != null) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(yaml.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("x.class"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
    }

    private PluginStartupIndex.Result build(final Path cache, final List<String> warnings) throws IOException {
        return PluginStartupIndex.build(plugins(), cache, ENV, 2, warnings::add);
    }

    @Test
    void aColdBootMissesAndAWarmBootHits() throws IOException {
        final Path plugins = plugins();
        jar(plugins.resolve("native.jar"), "plugin.yml", "name: Native\nversion: 1\nmain: n.N\nfolia-supported: true\n");
        jar(plugins.resolve("legacy.jar"), "plugin.yml", "name: Legacy\nversion: 2\nmain: l.L\n");
        jar(plugins.resolve("library.jar"), null, null);
        final Path cache = this.dir.resolve("cache");
        final List<String> warnings = new ArrayList<>();

        final PluginStartupIndex.Result cold = build(cache, warnings);
        assertEquals("cold", cold.telemetry().startClass());
        assertEquals(3, cold.telemetry().misses());
        assertTrue(cold.cacheWritten());
        assertEquals(List.of("Legacy"), cold.undeclared().stream().map(p -> p.descriptor().name()).toList());

        final PluginStartupIndex.Result warm = build(cache, warnings);
        assertEquals("warm", warm.telemetry().startClass());
        assertEquals(3, warm.telemetry().hits());
        assertFalse(warm.cacheWritten(), "nothing changed, nothing to rewrite");
        assertEquals(cold.plugins().stream().map(PluginStartupIndex.Indexed::descriptor).toList(),
            warm.plugins().stream().map(PluginStartupIndex.Indexed::descriptor).toList());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void changedBytesAreAMissEvenWhenSizeAndMtimeMatch() throws IOException {
        final Path plugins = plugins();
        final Path jar = plugins.resolve("a.jar");
        jar(jar, "plugin.yml", "name: Alpha\nfolia-supported: true\n");
        final Path cache = this.dir.resolve("cache");
        build(cache, new ArrayList<>());

        final FileTime mtime = Files.getLastModifiedTime(jar);
        // Same length, different descriptor, same mtime: only the SHA-256 can tell.
        jar(jar, "plugin.yml", "name: Alpha\nfolia-supported: fals\n");
        Files.setLastModifiedTime(jar, mtime);
        final PluginStartupIndex.Result second = build(cache, new ArrayList<>());
        assertEquals(1, second.telemetry().misses());
        assertFalse(second.plugins().get(0).descriptor().regionSupported());
    }

    @Test
    void aCorruptCacheWarnsOnceRebuildsAndContinues() throws IOException {
        final Path plugins = plugins();
        jar(plugins.resolve("a.jar"), "plugin.yml", "name: Alpha\nfolia-supported: true\n");
        jar(plugins.resolve("b.jar"), "paper-plugin.yml", "name: Beta\ncanvas-supported: true\n");
        final Path cache = this.dir.resolve("cache");
        build(cache, new ArrayList<>());
        Files.writeString(cache, "garbage\n");

        final List<String> warnings = new ArrayList<>();
        final PluginStartupIndex.Result rebuilt = build(cache, warnings);
        assertEquals(1, warnings.size());
        assertEquals(StartupCache.LoadStatus.CORRUPT, rebuilt.cacheStatus());
        assertEquals(2, rebuilt.telemetry().misses());
        assertTrue(rebuilt.cacheWritten());
        assertTrue(rebuilt.undeclared().isEmpty());
        assertEquals("warm", build(cache, new ArrayList<>()).telemetry().startClass());
    }

    @Test
    void anUnreadableJarIsReportedAndNotCached() throws IOException {
        final Path plugins = plugins();
        Files.writeString(plugins.resolve("broken.jar"), "not a zip");
        final Path cache = this.dir.resolve("cache");
        final PluginStartupIndex.Result result = build(cache, new ArrayList<>());
        assertNull(result.plugins().get(0).descriptor());
        assertTrue(result.plugins().get(0).failure() != null);
        assertEquals(0, StartupCache.load(cache, ENV).entries().size());
    }

    @Test
    void withoutACacheFileNothingIsReadOrWritten() throws IOException {
        jar(plugins().resolve("a.jar"), "plugin.yml", "name: Alpha\n");
        final PluginStartupIndex.Result result = build(null, new ArrayList<>());
        assertFalse(result.cacheWritten());
        assertEquals(1, result.plugins().size());
    }

    @Test
    void aMissingPluginsFolderIsAnEmptyIndex() {
        final PluginStartupIndex.Result result =
            PluginStartupIndex.build(this.dir.resolve("absent"), this.dir.resolve("cache"), ENV, 2, w -> {});
        assertTrue(result.plugins().isEmpty());
        assertEquals("empty", result.telemetry().startClass());
    }
}
