package dev.iyanz.sourbycraft.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A cache can make a boot faster; it must never make one fail or return what it did not store. */
class StartupCacheTest {

    private static final CacheEnvironment ENV = new CacheEnvironment("26.2", 1, 1, 1, 25);
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @TempDir Path dir;

    private static StartupCache.Entry entry(final String key, final String sha, final String payload) {
        return new StartupCache.Entry(key, new SourceFingerprint(sha, 10, 20), payload);
    }

    @Test
    void entriesRoundTrip() throws Exception {
        final Path file = this.dir.resolve("c");
        StartupCache.write(file, ENV, List.of(entry("a.jar", SHA_A, "one\ntwo"), entry("ü b.jar", SHA_B, "")));
        final StartupCache.Loaded loaded = StartupCache.load(file, ENV);
        assertEquals(StartupCache.LoadStatus.LOADED, loaded.status());
        assertEquals("one\ntwo", loaded.entries().get("a.jar").payload());
        assertEquals("", loaded.entries().get("ü b.jar").payload());
        assertFalse(loaded.discardedAnything());
    }

    @Test
    void aMissingFileIsAColdStartNotAWarning() {
        final StartupCache.Loaded loaded = StartupCache.load(this.dir.resolve("none"), ENV);
        assertEquals(StartupCache.LoadStatus.MISSING, loaded.status());
        assertFalse(loaded.discardedAnything());
    }

    @Test
    void anyEnvironmentChangeDiscardsTheWholeFile() throws Exception {
        final Path file = this.dir.resolve("c");
        StartupCache.write(file, ENV, List.of(entry("a.jar", SHA_A, "x")));
        for (final CacheEnvironment other : List.of(
            new CacheEnvironment("26.3", 1, 1, 1, 25), new CacheEnvironment("26.2", 2, 1, 1, 25),
            new CacheEnvironment("26.2", 1, 2, 1, 25), new CacheEnvironment("26.2", 1, 1, 2, 25),
            new CacheEnvironment("26.2", 1, 1, 1, 26))) {
            final StartupCache.Loaded loaded = StartupCache.load(file, other);
            assertEquals(StartupCache.LoadStatus.STALE_ENVIRONMENT, loaded.status(), other.toString());
            assertTrue(loaded.entries().isEmpty());
            assertTrue(loaded.discardedAnything());
        }
    }

    @Test
    void aDamagedHeaderDiscardsTheFile() throws Exception {
        final Path file = this.dir.resolve("c");
        Files.writeString(file, "not a cache\n");
        assertEquals(StartupCache.LoadStatus.CORRUPT, StartupCache.load(file, ENV).status());
    }

    @Test
    void aCorruptEntryIsDiscardedAloneAndTheRestAreKept() throws Exception {
        final Path file = this.dir.resolve("c");
        StartupCache.write(file, ENV, List.of(entry("a.jar", SHA_A, "keep"), entry("b.jar", SHA_B, "flip")));
        final List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        // Flip one character inside the second entry's payload field.
        final String victim = lines.get(3);
        final int at = victim.length() - 70;
        lines.set(3, victim.substring(0, at) + (victim.charAt(at) == 'A' ? 'B' : 'A') + victim.substring(at + 1));
        Files.write(file, lines, StandardCharsets.UTF_8);

        final StartupCache.Loaded loaded = StartupCache.load(file, ENV);
        assertEquals(StartupCache.LoadStatus.LOADED, loaded.status());
        assertEquals(1, loaded.discardedEntries());
        assertEquals("keep", loaded.entries().get("a.jar").payload());
        assertFalse(loaded.entries().containsKey("b.jar"));
        assertTrue(loaded.discardedAnything());
    }

    @Test
    void aTruncatedTailLosesOnlyTheTornEntry() throws Exception {
        final Path file = this.dir.resolve("c");
        StartupCache.write(file, ENV, List.of(entry("a.jar", SHA_A, "keep"), entry("b.jar", SHA_B, "torn")));
        final byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 20));
        final StartupCache.Loaded loaded = StartupCache.load(file, ENV);
        assertEquals(1, loaded.entries().size());
        assertEquals(1, loaded.discardedEntries());
    }

    @Test
    void aRewriteLeavesNoTemporaryFilesBehind() throws Exception {
        final Path file = this.dir.resolve("c");
        StartupCache.write(file, ENV, List.of(entry("a.jar", SHA_A, "1")));
        StartupCache.write(file, ENV, List.of(entry("a.jar", SHA_A, "2")));
        try (var files = Files.list(this.dir)) {
            assertEquals(List.of(file), files.toList());
        }
        assertEquals("2", StartupCache.load(file, ENV).entries().get("a.jar").payload());
    }
}
