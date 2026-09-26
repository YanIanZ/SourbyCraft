package dev.iyanz.sourbycraft.awf;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The {@code .awf} file: an immutable world image for templates and virtual worlds.
 *
 * <p>Layout: a header, immutable string metadata, a chunk index, then each chunk deflate-compressed.
 * {@link #open} reads only the header, metadata and index — enough to register a world — and a
 * chunk is decompressed the first time {@link #read} asks for it. That is the lazy materialisation
 * {@code aurora-world-fabric.md} describes: registering a world costs its index, not its size.</p>
 *
 * <p>Every chunk carries the SHA-256 of its uncompressed bytes. A chunk that fails to inflate or to
 * match its hash is reported as corrupt, never returned.</p>
 *
 * <pre>
 * "AWF1" int(version)
 * int(metaCount)  { UTF key, UTF value } * metaCount
 * int(chunkCount) { int x, int z, long offset, int storedLength, int rawLength, byte[32] sha256 } * chunkCount
 * chunk data
 * </pre>
 */
public final class AwfFile implements ChunkSource, AutoCloseable {

    static final int MAGIC = 0x41574631; // "AWF1"
    static final int VERSION = 1;
    static final int MAX_META = 1024;
    static final int MAX_CHUNKS = 4_000_000;
    /** No serialized chunk approaches this; a larger claim is corruption. */
    static final int MAX_CHUNK_BYTES = 64 * 1024 * 1024;
    private static final int INDEX_ENTRY_BYTES = 4 + 4 + 8 + 4 + 4 + 32;

    /** Thrown when stored data does not match what the file says it is. */
    public static final class CorruptAwfException extends IOException {
        private static final long serialVersionUID = 1L;

        CorruptAwfException(final String message) {
            super(message);
        }
    }

    private record Slot(long offset, int storedLength, int rawLength, byte[] sha256) {}

    private final FileChannel channel;
    private final Map<String, String> metadata;
    private final SortedMap<ChunkKey, Slot> index;
    private final AtomicLong materialized = new AtomicLong();
    private final AtomicLong bytesRead = new AtomicLong();

    private AwfFile(final FileChannel channel, final Map<String, String> metadata, final SortedMap<ChunkKey, Slot> index) {
        this.channel = channel;
        this.metadata = metadata;
        this.index = index;
    }

    /** Writes an image atomically: to a temporary sibling, forced, then moved into place. */
    public static void write(final Path file, final Map<String, String> metadata,
                             final SortedMap<ChunkKey, byte[]> chunks) throws IOException {
        if (metadata.size() > MAX_META || chunks.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("too many metadata entries or chunks");
        }
        // Compress first so the index can carry final offsets.
        final Map<ChunkKey, byte[]> stored = new LinkedHashMap<>();
        for (final Map.Entry<ChunkKey, byte[]> chunk : chunks.entrySet()) {
            if (chunk.getValue().length > MAX_CHUNK_BYTES) {
                throw new IllegalArgumentException("chunk " + chunk.getKey() + " exceeds " + MAX_CHUNK_BYTES + " bytes");
            }
            stored.put(chunk.getKey(), deflate(chunk.getValue()));
        }
        final ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        final DataOutputStream header = new DataOutputStream(headerBytes);
        header.writeInt(MAGIC);
        header.writeInt(VERSION);
        header.writeInt(metadata.size());
        for (final Map.Entry<String, String> m : new TreeMap<>(metadata).entrySet()) {
            header.writeUTF(m.getKey());
            header.writeUTF(m.getValue());
        }
        header.writeInt(chunks.size());
        long offset = headerBytes.size() + (long)chunks.size() * INDEX_ENTRY_BYTES;
        for (final Map.Entry<ChunkKey, byte[]> chunk : chunks.entrySet()) {
            final byte[] data = stored.get(chunk.getKey());
            header.writeInt(chunk.getKey().x());
            header.writeInt(chunk.getKey().z());
            header.writeLong(offset);
            header.writeInt(data.length);
            header.writeInt(chunk.getValue().length);
            header.write(sha256(chunk.getValue()));
            offset += data.length;
        }
        header.flush();

        final Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        final Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 OutputStream stream = new BufferedOutputStream(Channels.newOutputStream(out))) {
                headerBytes.writeTo(stream);
                for (final byte[] data : stored.values()) stream.write(data);
                stream.flush();
                out.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Opens an image, reading its header, metadata and index only. */
    public static AwfFile open(final Path file) throws IOException {
        final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        try {
            final long size = channel.size();
            final DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Channels.newInputStream(channel.position(0))));
            if (in.readInt() != MAGIC) throw new CorruptAwfException(file + " is not an AWF image");
            final int version = in.readInt();
            if (version != VERSION) throw new CorruptAwfException(file + " has unsupported AWF version " + version);
            final int metaCount = in.readInt();
            if (metaCount < 0 || metaCount > MAX_META) throw new CorruptAwfException("bad metadata count " + metaCount);
            final Map<String, String> metadata = new TreeMap<>();
            for (int i = 0; i < metaCount; i++) metadata.put(in.readUTF(), in.readUTF());
            final int chunkCount = in.readInt();
            if (chunkCount < 0 || chunkCount > MAX_CHUNKS) throw new CorruptAwfException("bad chunk count " + chunkCount);
            final SortedMap<ChunkKey, Slot> index = new TreeMap<>();
            for (int i = 0; i < chunkCount; i++) {
                final ChunkKey key = new ChunkKey(in.readInt(), in.readInt());
                final long offset = in.readLong();
                final int stored = in.readInt();
                final int raw = in.readInt();
                final byte[] sha = new byte[32];
                in.readFully(sha);
                if (offset < 0 || stored < 0 || raw < 0 || raw > MAX_CHUNK_BYTES || offset + stored > size) {
                    throw new CorruptAwfException("index entry " + key + " points outside the file");
                }
                if (index.put(key, new Slot(offset, stored, raw, sha)) != null) {
                    throw new CorruptAwfException("duplicate chunk " + key);
                }
            }
            return new AwfFile(channel, Collections.unmodifiableMap(metadata), Collections.unmodifiableSortedMap(index));
        } catch (final IOException | RuntimeException failure) {
            channel.close();
            throw failure instanceof IOException io ? io : new CorruptAwfException(String.valueOf(failure.getMessage()));
        }
    }

    public Map<String, String> metadata() {
        return this.metadata;
    }

    @Override
    public Set<ChunkKey> keys() {
        return this.index.keySet();
    }

    /** Decompresses and verifies one chunk. Safe to call from several threads. */
    @Override
    public Optional<byte[]> read(final ChunkKey key) throws IOException {
        final Slot slot = this.index.get(key);
        if (slot == null) return Optional.empty();
        final ByteBuffer buffer = ByteBuffer.allocate(slot.storedLength());
        long position = slot.offset();
        while (buffer.hasRemaining()) {
            final int n = this.channel.read(buffer, position);
            if (n < 0) throw new CorruptAwfException("chunk " + key + " is truncated");
            position += n;
        }
        this.bytesRead.addAndGet(slot.storedLength());
        final byte[] raw = inflate(buffer.array(), slot.rawLength(), key);
        if (!Arrays.equals(sha256(raw), slot.sha256())) {
            throw new CorruptAwfException("chunk " + key + " fails its SHA-256");
        }
        this.materialized.incrementAndGet();
        return Optional.of(raw);
    }

    /** Chunks decompressed so far. */
    public long materialized() {
        return this.materialized.get();
    }

    /** Compressed bytes read so far. */
    public long bytesRead() {
        return this.bytesRead.get();
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }

    static byte[] deflate(final byte[] raw) {
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
            final byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    static byte[] inflate(final byte[] stored, final int rawLength, final ChunkKey key) throws CorruptAwfException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(stored);
            final byte[] raw = new byte[rawLength];
            final byte[] probe = new byte[1];
            int filled = 0;
            while (!inflater.finished()) {
                final int n;
                if (filled < rawLength) {
                    n = inflater.inflate(raw, filled, rawLength - filled);
                    filled += n;
                } else {
                    // Declared size reached: any further output means the stream is longer.
                    n = inflater.inflate(probe);
                    if (n > 0) throw new CorruptAwfException("chunk " + key + " inflates past its declared size");
                }
                if (n == 0 && !inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new CorruptAwfException("chunk " + key + " is a truncated deflate stream");
                }
            }
            if (filled != rawLength) {
                throw new CorruptAwfException("chunk " + key + " does not inflate to its declared size");
            }
            return raw;
        } catch (final DataFormatException malformed) {
            throw new CorruptAwfException("chunk " + key + " is not valid deflate data");
        } finally {
            inflater.end();
        }
    }

    static byte[] sha256(final byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
