package dev.iyanz.sourbycraft.awf.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.awf.AwfWorldStore;
import dev.iyanz.sourbycraft.awf.ChunkKey;
import dev.iyanz.sourbycraft.awf.PersistenceMode;
import dev.iyanz.sourbycraft.awf.WorldRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuroraTemplatesTest {

    @TempDir Path dir;

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(final byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static AuroraWorldRegistry.Entry world(final String name, final String template) {
        return new AuroraWorldRegistry.Entry(name, "normal", 7L, "void", null, template, true);
    }

    /** Commits chunks (null bytes = deletion) into the store of one storage folder. */
    private static void commit(final Path folder, final String storage, final Map<ChunkKey, String> chunks)
        throws IOException {
        final Map<ChunkKey, byte[]> changed = new HashMap<>();
        final java.util.Set<ChunkKey> deleted = new java.util.HashSet<>();
        chunks.forEach((key, value) -> {
            if (value == null) deleted.add(key);
            else changed.put(key, b(value));
        });
        AwfWorldStore.open(AuroraTemplates.store(folder, storage), WorldRole.VANILLA, 2)
            .commit(changed, Set.of(), deleted, PersistenceMode.INCREMENTAL);
    }

    private static AwfWorldStore read(final Path folder, final String storage) throws IOException {
        return AwfWorldStore.open(AuroraTemplates.store(folder, storage), WorldRole.READ_ONLY, 1);
    }

    @Test
    void aTemplateHoldsTheWorldsChunksAndItsDescription() throws IOException {
        final Path island = this.dir.resolve("island");
        commit(island, "region", Map.of(new ChunkKey(0, 0), "a", new ChunkKey(1, 0), "b"));
        commit(island, "entities", Map.of(new ChunkKey(0, 0), "cow"));
        final AuroraTemplates templates = new AuroraTemplates(this.dir.resolve("awf-templates"));

        assertEquals(3, templates.save(island, world("island", null), "sky"));

        assertEquals(Set.of("sky"), templates.list());
        final AwfWorldStore region = read(this.dir.resolve("awf-templates/sky"), "region");
        assertEquals("b", s(region.read(new ChunkKey(1, 0)).orElseThrow()));
        assertEquals("cow", s(read(this.dir.resolve("awf-templates/sky"), "entities").read(new ChunkKey(0, 0)).orElseThrow()));
        assertFalse(Files.exists(this.dir.resolve("awf-templates/sky/poi.awf")), "nothing to copy, nothing created");
        final AuroraWorldRegistry.Entry description = templates.read("sky").orElseThrow();
        assertEquals("sky", description.name());
        assertEquals("void", description.generator());
        assertEquals(7L, description.seed());
        assertEquals(null, description.template());
        assertTrue(Files.exists(AuroraTemplates.store(island, "region")), "the world is left as it is");
    }

    @Test
    void anInstanceIsFlattenedOverItsTemplate() throws IOException {
        final Path templatesRoot = this.dir.resolve("awf-templates");
        final AuroraTemplates templates = new AuroraTemplates(templatesRoot);
        final Path base = this.dir.resolve("base");
        commit(base, "region", Map.of(new ChunkKey(0, 0), "t0", new ChunkKey(1, 0), "t1", new ChunkKey(2, 0), "t2"));
        templates.save(base, world("base", null), "sky");

        // The instance changed one chunk, deleted another and added a third.
        final Path instance = this.dir.resolve("inst");
        final Map<ChunkKey, String> own = new HashMap<>();
        own.put(new ChunkKey(0, 0), "mine");
        own.put(new ChunkKey(1, 0), null);
        own.put(new ChunkKey(5, 5), "new");
        commit(instance, "region", own);
        templates.save(instance, world("inst", "sky"), "sky2");

        final AwfWorldStore flat = read(templatesRoot.resolve("sky2"), "region");
        assertEquals(Set.of(new ChunkKey(0, 0), new ChunkKey(2, 0), new ChunkKey(5, 5)), flat.keys());
        assertEquals("mine", s(flat.read(new ChunkKey(0, 0)).orElseThrow()));
        assertEquals("t2", s(flat.read(new ChunkKey(2, 0)).orElseThrow()));
        assertTrue(flat.deleted().isEmpty(), "a deleted chunk is simply absent: generated again, as in the instance");
        assertEquals(null, templates.read("sky2").orElseThrow().template(), "a template stands alone");
        assertEquals("t1", s(read(templatesRoot.resolve("sky"), "region").read(new ChunkKey(1, 0)).orElseThrow()),
            "the original template is untouched");
    }

    @Test
    void largeWorldsAreWrittenInBatches() throws IOException {
        final Path big = this.dir.resolve("big");
        final Map<ChunkKey, String> chunks = new HashMap<>();
        for (int i = 0; i < AuroraTemplates.BATCH + 3; i++) chunks.put(new ChunkKey(i, -i), "c" + i);
        commit(big, "region", chunks);
        final AuroraTemplates templates = new AuroraTemplates(this.dir.resolve("awf-templates"));
        assertEquals(chunks.size(), templates.save(big, world("big", null), "big"));
        final AwfWorldStore region = read(this.dir.resolve("awf-templates/big"), "region");
        assertEquals(chunks.size(), region.keys().size());
        assertArrayEquals(b("c100"), region.read(new ChunkKey(100, -100)).orElseThrow());
    }

    @Test
    void anExistingTemplateIsNeverOverwrittenAndNoPartialIsLeft() throws IOException {
        final Path island = this.dir.resolve("island");
        commit(island, "region", Map.of(new ChunkKey(0, 0), "a"));
        final Path root = this.dir.resolve("awf-templates");
        final AuroraTemplates templates = new AuroraTemplates(root);
        templates.save(island, world("island", null), "sky");
        assertThrows(IllegalStateException.class, () -> templates.save(island, world("island", null), "sky"));
        templates.delete("sky");
        assertTrue(templates.list().isEmpty());
        try (Stream<Path> left = Files.list(root)) {
            assertEquals(0, left.count(), "no hidden partial or deleting folder remains");
        }
    }
}
