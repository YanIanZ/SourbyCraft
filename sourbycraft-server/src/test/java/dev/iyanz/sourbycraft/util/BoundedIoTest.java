package dev.iyanz.sourbycraft.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedIoTest {

    @TempDir
    Path dir;

    /** An endless stream that counts what was read from it. */
    private static InputStream endless(final AtomicLong read) {
        return new InputStream() {
            @Override
            public int read() {
                read.incrementAndGet();
                return 'x';
            }

            @Override
            public int read(final byte[] b, final int off, final int len) {
                java.util.Arrays.fill(b, off, off + len, (byte) 'x');
                read.addAndGet(len);
                return len;
            }
        };
    }

    @Test
    void readsABodyUpToTheLimit() throws IOException {
        final byte[] body = new byte[100];
        assertArrayEquals(body, BoundedIo.readAll(new ByteArrayInputStream(body), 100));
        assertEquals(0, BoundedIo.readAll(InputStream.nullInputStream(), 0).length);
    }

    @Test
    void refusesABodyOneByteOverTheLimit() {
        final IOException e = assertThrows(IOException.class,
            () -> BoundedIo.readAll(new ByteArrayInputStream(new byte[101]), 100));
        assertTrue(e.getMessage().contains("100"), e.getMessage());
    }

    @Test
    void stopsReadingAnEndlessStreamShortlyAfterTheLimit() {
        final AtomicLong read = new AtomicLong();
        assertThrows(IOException.class, () -> BoundedIo.readAll(endless(read), 1000));
        assertTrue(read.get() <= 1000 + 64 * 1024, "read " + read.get());
    }

    @Test
    void copiesAFileUpToTheLimit() throws IOException {
        final Path out = this.dir.resolve("ok");
        assertEquals(10L, BoundedIo.copy(new ByteArrayInputStream(new byte[10]), out, 10L));
        assertEquals(10L, Files.size(out));
    }

    @Test
    void anOversizedCopyFailsAndStopsReading() {
        final AtomicLong read = new AtomicLong();
        final Path out = this.dir.resolve("big");
        assertThrows(IOException.class, () -> BoundedIo.copy(endless(read), out, 4096L));
        assertTrue(read.get() <= 4096L + 64 * 1024, "read " + read.get());
    }

    @Test
    void aLimitedStreamPassesUpToTheLimitAndFailsPastIt() throws IOException {
        assertEquals(50, BoundedIo.limited(new ByteArrayInputStream(new byte[50]), 50).readAllBytes().length);
        final InputStream over = BoundedIo.limited(new ByteArrayInputStream(new byte[51]), 50);
        assertThrows(IOException.class, over::readAllBytes);
        final InputStream single = BoundedIo.limited(new ByteArrayInputStream(new byte[2]), 1);
        assertEquals(0, single.read());
        assertThrows(IOException.class, single::read);
    }

    @Test
    void aGzipBombBehindALimitedStreamStopsAtTheDecompressedLimit() throws IOException {
        final java.io.ByteArrayOutputStream gz = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(gz)) {
            out.write(new byte[8 * 1024 * 1024]);
        }
        final Path target = this.dir.resolve("bomb");
        try (InputStream in = new java.util.zip.GZIPInputStream(
                BoundedIo.limited(new ByteArrayInputStream(gz.toByteArray()), 1024L * 1024))) {
            assertThrows(IOException.class, () -> BoundedIo.copy(in, target, 1024L * 1024));
        }
    }

    @Test
    void rejectsANegativeLimit() {
        assertThrows(IllegalArgumentException.class, () -> BoundedIo.readAll(InputStream.nullInputStream(), -1));
    }
}
