package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AwfWorldFileTest {

    @TempDir Path dir;

    private static byte[] chunk(final int seed) {
        // Compressible like real chunk NBT: repeated palette text with some noise.
        final StringBuilder text = new StringBuilder();
        final Random random = new Random(seed);
        for (int i = 0; i < 200; i++) text.append("minecraft:stone ").append(random.nextInt(4));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Path write(final byte codec) throws IOException {
        final Path file = this.dir.resolve("world-" + codec + ".awf");
        try (AwfWorldFile.Writer writer = AwfWorldFile.writer(file, codec)) {
            for (int x = -3; x <= 3; x++) writer.add("region", new ChunkKey(x, x * 2), chunk(x));
            writer.add("entities", new ChunkKey(0, 0), "cow".getBytes(StandardCharsets.UTF_8));
            writer.finish(Map.of("format", "awf-world", "environment", "normal"));
        }
        return file;
    }

    @Test
    void everyCodecRoundTripsAndReadsChunksOnDemand() throws IOException {
        for (final byte codec : new byte[] {Compression.NONE, Compression.DEFLATE, Compression.ZSTD}) {
            if (codec == Compression.ZSTD && !Compression.zstdAvailable()) continue;
            try (AwfWorldFile file = AwfWorldFile.open(write(codec))) {
                assertEquals("normal", file.metadata().get("environment"));
                assertEquals(Map.of("region", 7, "entities", 1), file.counts());
                assertArrayEquals(chunk(2), file.stream("region").read(new ChunkKey(2, 4)).orElseThrow(),
                    Compression.name(codec));
                assertTrue(file.stream("region").read(new ChunkKey(2, 5)).isEmpty());
                assertTrue(file.stream("poi").keys().isEmpty(), "an absent stream is empty");
                assertEquals("cow", new String(file.stream("entities").read(new ChunkKey(0, 0)).orElseThrow(),
                    StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void compressionShrinksChunkData() throws IOException {
        final long none = Files.size(write(Compression.NONE));
        final long deflate = Files.size(write(Compression.DEFLATE));
        assertTrue(deflate < none / 2, deflate + " vs " + none);
        if (Compression.zstdAvailable()) assertTrue(Files.size(write(Compression.ZSTD)) < none / 2);
    }

    @Test
    void aDamagedChunkIsReportedAndTheOthersStillRead() throws IOException {
        final Path path = write(Compression.NONE);
        final byte[] bytes = Files.readAllBytes(path);
        bytes[8 + 5] ^= 0x55;                    // inside the first chunk written: (-3, -6)
        Files.write(path, bytes);
        try (AwfWorldFile file = AwfWorldFile.open(path)) {
            assertThrows(AwfFile.CorruptAwfException.class, () -> file.stream("region").read(new ChunkKey(-3, -6)));
            assertArrayEquals(chunk(3), file.stream("region").read(new ChunkKey(3, 6)).orElseThrow());
        }
    }

    @Test
    void aDamagedIndexOrTruncatedFileIsRefused() throws IOException {
        final Path path = write(Compression.DEFLATE);
        final byte[] bytes = Files.readAllBytes(path);
        final byte[] badIndex = bytes.clone();
        badIndex[bytes.length - AwfWorldFile.TRAILER_BYTES - 3] ^= 1;
        Files.write(path, badIndex);
        assertThrows(AwfFile.CorruptAwfException.class, () -> AwfWorldFile.open(path));
        Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length - 7));
        assertThrows(AwfFile.CorruptAwfException.class, () -> AwfWorldFile.open(path));
        Files.write(path, "AWF1 something else".getBytes(StandardCharsets.UTF_8));
        assertThrows(AwfFile.CorruptAwfException.class, () -> AwfWorldFile.open(path));
    }

    @Test
    void anUnfinishedWriteLeavesNothing() throws IOException {
        final Path target = this.dir.resolve("never.awf");
        try (AwfWorldFile.Writer writer = AwfWorldFile.writer(target)) {
            writer.add("region", new ChunkKey(0, 0), chunk(0));
        }
        assertFalse(Files.exists(target));
        try (Stream<Path> left = Files.list(this.dir)) {
            assertEquals(0, left.count(), "the temporary file is removed");
        }
    }

    @Test
    void aChunkIsWrittenOncePerStream() throws IOException {
        final Path target = this.dir.resolve("dup.awf");
        try (AwfWorldFile.Writer writer = AwfWorldFile.writer(target)) {
            writer.add("region", new ChunkKey(0, 0), chunk(0));
            writer.add("region", new ChunkKey(0, 0), chunk(1));
            assertThrows(IOException.class, () -> writer.finish(Map.of()));
        }
        assertFalse(Files.exists(target));
        assertTrue(AwfWorldFile.looksLike(new byte[] {'A', 'W', 'F', 'W'}));
        assertFalse(AwfWorldFile.looksLike(new byte[] {(byte) 0xB1, 0x0B, 0x0D, 0}));
    }
}
