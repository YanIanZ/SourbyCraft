package dev.iyanz.sourbycraft.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The boot stage's warning sink: one line for the cache read, one for the cache write. */
class StartupIndexStageTest {

    private static final CacheEnvironment ENV = new CacheEnvironment("26.2", 1, 1, 1, 25);

    @TempDir Path dir;

    @Test
    void cacheThatCanBeNeitherReadNorWrittenWarnsForBoth() throws IOException {
        final Path plugins = Files.createDirectories(this.dir.resolve("plugins"));
        try (OutputStream out = Files.newOutputStream(plugins.resolve("a.jar")); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write("name: A\nversion: 1\nmain: a.A\nfolia-supported: true\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        // A directory where the cache file belongs: reading fails, and so does rewriting it.
        final Path cache = Files.createDirectories(this.dir.resolve("startup-index.cache"));
        final List<String> logged = new ArrayList<>();

        final PluginStartupIndex.Result result = PluginStartupIndex.build(plugins, cache, ENV, 1,
            StartupIndexStage.warnings(logged::add));

        assertFalse(result.cacheWritten(), "nothing can be written over a directory");
        assertEquals(2, logged.size(), logged.toString());
        assertTrue(logged.get(0).contains("could not be read"), logged.get(0));
        assertTrue(logged.get(1).startsWith(PluginStartupIndex.CACHE_WRITE_FAILED), logged.get(1));
    }

    @Test
    void eachKindIsLoggedOnce() {
        final List<String> logged = new ArrayList<>();
        final Consumer<String> warnings = StartupIndexStage.warnings(logged::add);
        warnings.accept("Aurora startup cache was not a valid startup cache and was discarded");
        warnings.accept("plugins folder could not be listed: x");
        warnings.accept(PluginStartupIndex.CACHE_WRITE_FAILED + "a");
        warnings.accept(PluginStartupIndex.CACHE_WRITE_FAILED + "b");
        assertEquals(List.of("Aurora startup cache was not a valid startup cache and was discarded",
            PluginStartupIndex.CACHE_WRITE_FAILED + "a"), logged);
    }
}
