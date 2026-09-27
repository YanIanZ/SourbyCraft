package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AwfSettingsTest {

    @TempDir Path dir;

    @Test
    void noWorldIsStoredByDefault() {
        assertTrue(AwfSettings.DEFAULT.worlds().isEmpty());
        assertEquals(AwfSettings.DEFAULT, AwfSettings.parse(Map.of()).settings());
    }

    @Test
    void parsesEveryKey() {
        final AwfSettings.Parsed parsed = AwfSettings.parse(Map.of(
            AwfSettings.WORLDS_KEY, List.of("lobby", "arena"),
            AwfSettings.PERSISTENCE_KEY, "checkpoint",
            AwfSettings.COMMIT_INTERVAL_KEY, 10,
            AwfSettings.RESIDENT_CHUNKS_KEY, 256,
            AwfSettings.RETAINED_GENERATIONS_KEY, 5,
            AwfSettings.COMMIT_ATTEMPTS_KEY, 4));
        assertEquals(List.of(), parsed.invalidKeys());
        assertEquals(new AwfSettings(Set.of("lobby", "arena"), PersistenceMode.CHECKPOINT, 10, 256, 5, 4),
            parsed.settings());
    }

    @Test
    void aBadWorldListMeansNoWorldNotAGuess() {
        for (final Object bad : List.of("world", List.of("ok", "../escape"), List.of("a/b"), List.of(""), List.of(3))) {
            final AwfSettings.Parsed parsed = AwfSettings.parse(Map.of(AwfSettings.WORLDS_KEY, bad));
            assertEquals(List.of(AwfSettings.WORLDS_KEY), parsed.invalidKeys(), String.valueOf(bad));
            assertTrue(parsed.settings().worlds().isEmpty());
        }
    }

    @Test
    void eachBadKeyFallsBackOnItsOwn() {
        final AwfSettings.Parsed parsed = AwfSettings.parse(Map.of(
            AwfSettings.WORLDS_KEY, List.of("lobby"),
            AwfSettings.PERSISTENCE_KEY, "read_only",
            AwfSettings.COMMIT_INTERVAL_KEY, 0,
            AwfSettings.RESIDENT_CHUNKS_KEY, 1.5));
        assertEquals(List.of(AwfSettings.PERSISTENCE_KEY, AwfSettings.COMMIT_INTERVAL_KEY,
            AwfSettings.RESIDENT_CHUNKS_KEY), parsed.invalidKeys());
        assertEquals(Set.of("lobby"), parsed.settings().worlds());
        assertEquals(PersistenceMode.INCREMENTAL, parsed.settings().persistence(), "a live world is never read-only");
        assertEquals(AwfSettings.DEFAULT.commitIntervalSeconds(), parsed.settings().commitIntervalSeconds());
    }

    @Test
    void readOnlyAndZeroBoundsAreRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> new AwfSettings(Set.of(), PersistenceMode.READ_ONLY, 30, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new AwfSettings(Set.of(), PersistenceMode.FULL, 30, 0, 1, 1), "memory must be bounded");
    }

    @Test
    void auroraFileWinsOverTheUnifiedFile() throws Exception {
        final Path unified = this.dir.resolve("unified.toml");
        final Path aurora = this.dir.resolve("aurora.toml");
        Files.writeString(unified, "[aurora.awf]\nworlds = [\"old\"]\ncommit-interval-seconds = 5\n");
        Files.writeString(aurora, "[aurora.awf]\nworlds = [\"lobby\"]\n");
        final AwfSettings settings = AwfSettings.readEarly(aurora, unified);
        assertEquals(Set.of("lobby"), settings.worlds());
        assertEquals(5, settings.commitIntervalSeconds());
    }

    @Test
    void anUnreadableFileMeansNoWorld() throws Exception {
        final Path aurora = this.dir.resolve("aurora.toml");
        Files.writeString(aurora, "[aurora.awf\nworlds = [\"lobby\"]\n");
        assertEquals(AwfSettings.DEFAULT, AwfSettings.readEarly(aurora, this.dir.resolve("missing.toml")));
    }
}
