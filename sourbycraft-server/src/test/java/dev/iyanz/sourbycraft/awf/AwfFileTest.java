package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Registering an image reads its index only; chunks materialise on request and are verified. */
class AwfFileTest {

    @TempDir Path dir;

    static SortedMap<ChunkKey, byte[]> chunks(final int n) {
        final SortedMap<ChunkKey, byte[]> out = new TreeMap<>();
        for (int i = 0; i < n; i++) out.put(new ChunkKey(i, -i), ("chunk " + i + " ").repeat(50).getBytes(StandardCharsets.UTF_8));
        return out;
    }

    @Test
    void openingReadsNoChunkAndReadsAreLazy() throws Exception {
        final Path file = this.dir.resolve("t.awf");
        AwfFile.write(file, Map.of("role", "TEMPLATE"), chunks(10));
        try (AwfFile awf = AwfFile.open(file)) {
            assertEquals(10, awf.keys().size());
            assertEquals("TEMPLATE", awf.metadata().get("role"));
            assertEquals(0, awf.materialized());
            assertArrayEquals(chunks(10).get(new ChunkKey(3, -3)), awf.read(new ChunkKey(3, -3)).orElseThrow());
            assertEquals(1, awf.materialized());
            assertTrue(awf.read(new ChunkKey(99, 99)).isEmpty());
        }
    }

    @Test
    void anEmptyChunkRoundTrips() throws Exception {
        final Path file = this.dir.resolve("e.awf");
        final SortedMap<ChunkKey, byte[]> one = new TreeMap<>(Map.of(new ChunkKey(0, 0), new byte[0]));
        AwfFile.write(file, Map.of(), one);
        try (AwfFile awf = AwfFile.open(file)) {
            assertEquals(0, awf.read(new ChunkKey(0, 0)).orElseThrow().length);
        }
    }

    @Test
    void aCorruptChunkIsReportedNotReturned() throws Exception {
        final Path file = this.dir.resolve("c.awf");
        AwfFile.write(file, Map.of(), chunks(2));
        final long size = Files.size(file);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(new byte[] {0x55, 0x55, 0x55, 0x55}), size - 6);
        }
        try (AwfFile awf = AwfFile.open(file)) {
            // Data is laid out in key order, so the tail of the file is the last key's chunk.
            final java.util.List<ChunkKey> keys = new java.util.ArrayList<>(awf.keys());
            final ChunkKey damaged = keys.get(keys.size() - 1);
            final ChunkKey intact = keys.get(0);
            assertThrows(AwfFile.CorruptAwfException.class, () -> awf.read(damaged));
            assertArrayEquals(chunks(2).get(intact), awf.read(intact).orElseThrow(),
                "damage to one chunk leaves the others readable");
        }
    }

    @Test
    void notAnImageIsRefused() throws Exception {
        final Path file = Files.writeString(this.dir.resolve("x.awf"), "hello world, not an image");
        assertThrows(AwfFile.CorruptAwfException.class, () -> AwfFile.open(file));
    }

    @Test
    void aTruncatedIndexIsRefused() throws Exception {
        final Path file = this.dir.resolve("t.awf");
        AwfFile.write(file, Map.of(), chunks(3));
        final byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, 40));
        assertThrows(java.io.IOException.class, () -> AwfFile.open(file));
    }
}
