package dev.iyanz.sourbycraft.awf.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.iyanz.sourbycraft.api.world.WorldProperties;
import dev.iyanz.sourbycraft.awf.AwfWorldFile;
import dev.iyanz.sourbycraft.awf.ChunkKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.Difficulty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The portable {@code .awf} file's metadata: what an export writes is what an import restores. */
class AuroraWorldFilesTest {

    @TempDir Path dir;

    private Path write(final String name, final Map<String, String> metadata) throws IOException {
        final Path file = this.dir.resolve(name + ".awf");
        try (AwfWorldFile.Writer writer = AwfWorldFile.writer(file)) {
            writer.add("region", new ChunkKey(0, 0), "chunk".getBytes(StandardCharsets.UTF_8));
            writer.finish(metadata);
        }
        return file;
    }

    private static AuroraWorldRegistry.Entry describe(final Path file, final String name) throws IOException {
        try (AwfWorldFile awf = AwfWorldFile.open(file)) {
            return AuroraWorldFiles.describe(awf, name, true);
        }
    }

    @Test
    void exportThenImportKeepsEveryProperty() throws IOException {
        // Every field non-default, false booleans included: a false that read back as null would
        // silently become the server default.
        final WorldProperties properties = new WorldProperties(new WorldProperties.Spawn(0.5, 72.0, -3.5, 90.0f),
            Difficulty.HARD, false, false, true, "minecraft:the_void", new WorldProperties.Bounds(-4, -4, 3, 3), false);
        final AuroraWorldRegistry.Entry world = new AuroraWorldRegistry.Entry("isle", "normal", 42L, "void", null,
            null, false, properties);
        final Path file = write("isle", AuroraWorldFiles.metadata(world, "world isle"));

        final AuroraWorldRegistry.Entry imported = describe(file, "isle-copy");
        assertEquals(properties, imported.properties());
        assertEquals("isle-copy", imported.name());
        assertEquals(42L, imported.seed());
        assertEquals("void", imported.generator());
        assertFalse(imported.prunesEmptyChunks(), "the explicit pruning override survives the file");
    }

    @Test
    void aFileWithoutPropertiesStillImports() throws IOException {
        // The metadata every file written before properties were carried has.
        final Path file = write("old", Map.of("format", AuroraWorldFiles.FORMAT, "environment", "nether",
            "seed", "7", "generator", "void", "source", "world old", "created-by", "SourbyCraft Aurora World Fabric"));
        final AuroraWorldRegistry.Entry imported = describe(file, "old");
        assertNull(imported.properties());
        assertEquals(WorldProperties.NONE, imported.propertiesOrNone());
        assertEquals("nether", imported.environment());
        assertEquals(7L, imported.seed());
    }

    @Test
    void aWorldWithoutPropertiesWritesNoKey() {
        final AuroraWorldRegistry.Entry world = new AuroraWorldRegistry.Entry("plain", "normal", null, null, null,
            null, false);
        assertFalse(AuroraWorldFiles.metadata(world, "world plain").containsKey(AuroraWorldFiles.PROPERTIES));
    }

    @Test
    void malformedPropertiesAreRefusedBeforeAnythingIsCreated() throws IOException {
        final Path garbage = write("garbage", Map.of("format", AuroraWorldFiles.FORMAT, "environment", "normal",
            AuroraWorldFiles.PROPERTIES, "{not json"));
        assertThrows(IOException.class, () -> describe(garbage, "garbage"));
        final Path inverted = write("inverted", Map.of("format", AuroraWorldFiles.FORMAT, "environment", "normal",
            AuroraWorldFiles.PROPERTIES, "{\"saveBounds\":{\"minChunkX\":5,\"minChunkZ\":0,\"maxChunkX\":1,\"maxChunkZ\":0}}"));
        assertThrows(IOException.class, () -> describe(inverted, "inverted"));
    }
}
