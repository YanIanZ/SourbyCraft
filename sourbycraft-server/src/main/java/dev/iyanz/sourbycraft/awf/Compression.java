package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Chunk codecs for AWF files. zstd comes from the engine's runtime libraries (zstd-jni), which are
 * not on SourbyCraft's compile classpath, so it is bound once through method handles; when it is
 * missing, writers fall back to deflate and readers report which codec they cannot read.
 */
public final class Compression {

    /** Codec ids as stored in files. */
    public static final byte NONE = 0;
    public static final byte DEFLATE = 1;
    public static final byte ZSTD = 2;

    private static final MethodHandle ZSTD_COMPRESS;
    private static final MethodHandle ZSTD_DECOMPRESS;

    static {
        MethodHandle compress = null;
        MethodHandle decompress = null;
        try {
            final Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd");
            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            compress = lookup.findStatic(zstd, "compress", MethodType.methodType(byte[].class, byte[].class, int.class));
            decompress = lookup.findStatic(zstd, "decompress", MethodType.methodType(byte[].class, byte[].class, int.class));
            // Loads the native library now, so a missing one shows as "unavailable", not as a failed write.
            compress.invoke(new byte[] {1}, 1);
        } catch (final Throwable unavailable) {
            compress = null;
            decompress = null;
        }
        ZSTD_COMPRESS = compress;
        ZSTD_DECOMPRESS = decompress;
    }

    private Compression() {}

    public static boolean zstdAvailable() {
        return ZSTD_COMPRESS != null;
    }

    /** zstd at the given level; fails when zstd is unavailable. */
    public static byte[] zstd(final byte[] raw, final int level) throws IOException {
        if (ZSTD_COMPRESS == null) throw new IOException("zstd is not available on this server");
        try {
            return (byte[]) ZSTD_COMPRESS.invoke(raw, level);
        } catch (final Throwable failed) {
            throw new IOException("zstd compression failed: " + failed.getMessage(), failed);
        }
    }

    /** Inverse of {@link #zstd}; the result must be exactly {@code rawLength} bytes. */
    public static byte[] unzstd(final byte[] stored, final int rawLength) throws IOException {
        if (ZSTD_DECOMPRESS == null) throw new IOException("zstd is not available on this server");
        final byte[] raw;
        try {
            raw = (byte[]) ZSTD_DECOMPRESS.invoke(stored, rawLength);
        } catch (final Throwable failed) {
            throw new IOException("corrupt zstd data: " + failed.getMessage(), failed);
        }
        if (raw.length != rawLength) throw new IOException("zstd data inflated to " + raw.length + " of " + rawLength + " bytes");
        return raw;
    }

    /** Compresses with the best codec available: zstd, else deflate. */
    public static byte codecForWriting() {
        return zstdAvailable() ? ZSTD : DEFLATE;
    }

    public static byte[] compress(final byte codec, final byte[] raw) throws IOException {
        return switch (codec) {
            case NONE -> raw.clone();
            case DEFLATE -> AwfFile.deflate(raw);
            case ZSTD -> zstd(raw, 3);
            default -> throw new IllegalArgumentException("codec " + codec);
        };
    }

    public static byte[] decompress(final byte codec, final byte[] stored, final int rawLength, final ChunkKey key)
        throws IOException {
        return switch (codec) {
            case NONE -> {
                if (stored.length != rawLength) throw new AwfFile.CorruptAwfException("chunk " + key + " has the wrong length");
                yield stored;
            }
            case DEFLATE -> AwfFile.inflate(stored, rawLength, key);
            case ZSTD -> unzstd(stored, rawLength);
            default -> throw new AwfFile.CorruptAwfException("chunk " + key + " uses unknown codec " + codec);
        };
    }

    public static String name(final byte codec) {
        return switch (codec) {
            case NONE -> "none";
            case DEFLATE -> "deflate";
            case ZSTD -> "zstd";
            default -> "codec " + codec;
        };
    }
}
