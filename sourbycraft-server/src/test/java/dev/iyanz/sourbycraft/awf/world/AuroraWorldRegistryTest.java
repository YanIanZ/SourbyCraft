package dev.iyanz.sourbycraft.awf.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuroraWorldRegistryTest {

    @TempDir Path dir;

    @Test
    void namesThatWouldClaimOtherWorldsFoldersAreRefused() {
        assertTrue(AuroraWorldRegistry.invalidName("island_42").isEmpty());
        assertTrue(AuroraWorldRegistry.invalidName("region").isPresent(), "every storage folder is named region");
        assertTrue(AuroraWorldRegistry.invalidName("dimensions").isPresent());
        assertTrue(AuroraWorldRegistry.invalidName("Island").isPresent(), "names are lower-case path elements");
        assertTrue(AuroraWorldRegistry.invalidName("a/b").isPresent());
        assertTrue(AuroraWorldRegistry.invalidName("").isPresent());
        assertTrue(AuroraWorldRegistry.invalidName("x".repeat(49)).isPresent());
    }

    @Test
    void entriesSurviveAReopenAndRemovalIsPersisted() throws IOException {
        final Path file = this.dir.resolve("aurora-worlds.json");
        final AuroraWorldRegistry first = new AuroraWorldRegistry(file);
        first.put(new AuroraWorldRegistry.Entry("hub", "normal", 42L, null, null, null, true));
        first.put(new AuroraWorldRegistry.Entry("arena", "nether", null, "void", "flat", "lobby", false));

        final AuroraWorldRegistry reopened = new AuroraWorldRegistry(file);
        assertEquals(2, reopened.all().size());
        assertTrue(reopened.get("hub").orElseThrow().autoload());
        assertEquals(42L, reopened.get("hub").orElseThrow().seed());
        assertEquals("nether", reopened.get("arena").orElseThrow().environment());
        assertEquals("lobby", reopened.get("arena").orElseThrow().template());
        assertEquals("void", reopened.get("arena").orElseThrow().generator());

        reopened.remove("arena");
        assertFalse(new AuroraWorldRegistry(file).contains("arena"));
        try (var leftovers = Files.list(this.dir)) {
            assertEquals(1, leftovers.count(), "the temporary file of an atomic write is cleaned up");
        }
    }

    @Test
    void aMalformedFileIsAnErrorNotAnEmptyList() throws IOException {
        final Path file = this.dir.resolve("aurora-worlds.json");
        Files.writeString(file, "{ not json");
        assertThrows(IOException.class, () -> new AuroraWorldRegistry(file),
            "silently starting empty would drop every world's AWF attachment");
    }
}
