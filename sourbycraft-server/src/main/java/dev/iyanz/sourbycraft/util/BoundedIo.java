package dev.iyanz.sourbycraft.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads of untrusted response bodies and process output with a hard size limit. Both methods fail
 * with an {@link IOException} as soon as more than {@code limit} bytes arrive, reading at most one
 * buffer past the limit, so an endless or oversized body cannot fill memory or disk. They do not
 * close the stream.
 */
public final class BoundedIo {

    private static final int BUFFER = 64 * 1024;

    private BoundedIo() {}

    /** The whole stream, which must hold at most {@code limit} bytes. */
    public static byte[] readAll(final InputStream in, final int limit) throws IOException {
        if (limit < 0) throw new IllegalArgumentException("limit " + limit);
        final byte[] bytes = in.readNBytes(limit);
        if (in.read() != -1) {
            throw new IOException("body exceeds " + limit + " bytes");
        }
        return bytes;
    }

    /**
     * A view of {@code in} that fails with an {@link IOException} once more than {@code limit} bytes
     * have been read through it (for a compressed body read by a decompressor). Closing it closes
     * {@code in}.
     */
    public static InputStream limited(final InputStream in, final long limit) {
        if (limit < 0) throw new IllegalArgumentException("limit " + limit);
        return new java.io.FilterInputStream(in) {
            private long read;

            @Override
            public int read() throws IOException {
                final int b = super.read();
                if (b != -1) count(1);
                return b;
            }

            @Override
            public int read(final byte[] b, final int off, final int len) throws IOException {
                final int n = super.read(b, off, len);
                if (n > 0) count(n);
                return n;
            }

            @Override
            public long skip(final long n) throws IOException {
                final long skipped = super.skip(n);
                if (skipped > 0) count(skipped);
                return skipped;
            }

            private void count(final long n) throws IOException {
                this.read += n;
                if (this.read > limit) throw new IOException("body exceeds " + limit + " bytes");
            }
        };
    }

    /** Copies the stream into {@code target} (created or truncated); returns the bytes written. */
    public static long copy(final InputStream in, final Path target, final long limit) throws IOException {
        if (limit < 0) throw new IllegalArgumentException("limit " + limit);
        long total = 0L;
        final byte[] buf = new byte[BUFFER];
        try (OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > limit) {
                    throw new IOException("body exceeds " + limit + " bytes");
                }
                out.write(buf, 0, n);
            }
        }
        return total;
    }
}
