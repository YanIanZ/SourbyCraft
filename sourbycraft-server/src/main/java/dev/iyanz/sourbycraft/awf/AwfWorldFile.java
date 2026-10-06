package dev.iyanz.sourbycraft.awf;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.CRC32C;

/**
 * The portable world file, {@code .awf} version 2: one file holding a whole world — its chunks,
 * entities and POI — and what it needs to be created again (environment, seed, generator).
 *
 * <p>What makes it fast to use:</p>
 * <ul>
 *   <li><b>Random access.</b> Chunks are compressed one by one and located through an index, so a
 *       reader decompresses only the chunks it asks for; opening a file reads its index alone.
 *       A whole-world blob (the Slime layout) has to be inflated and parsed completely first.</li>
 *   <li><b>zstd</b> per chunk where the server has it, deflate otherwise; the codec is recorded per
 *       chunk, so either reader can read either file as long as it has the codec.</li>
 *   <li><b>Streaming writes.</b> The index is written after the chunks, so a writer holds only the
 *       index in memory, never the world.</li>
 *   <li><b>Checked.</b> Every chunk carries the CRC32C of its uncompressed bytes and the index its
 *       own CRC32C; damage is reported, never returned as a chunk. Files are written to a
 *       temporary sibling, forced and moved into place.</li>
 * </ul>
 *
 * <pre>
 * "AWFW" int(2)
 * chunk data ...
 * index:   int(metaCount) { UTF key, UTF value }
 *          int(streamCount) { UTF name, int(count) { int x, int z, long offset, int stored, int raw,
 *                                                    byte codec, int crc32c } }
 * trailer: long(indexOffset) int(indexLength) int(indexCrc32c) "AWFW"
 * </pre>
 *
 * <p>{@link AwfFile} (version 1, a single chunk stream with SHA-256) remains the immutable image
 * the qualification tests use; this is the format worlds are exported to and imported from.</p>
 */
public final class AwfWorldFile implements AutoCloseable {

    public static final int MAGIC = 0x41574657; // "AWFW"
    public static final int VERSION = 2;
    public static final String SUFFIX = ".awf";
    static final int TRAILER_BYTES = 8 + 4 + 4 + 4;
    static final int MAX_INDEX_BYTES = 512 * 1024 * 1024;
    static final int MAX_STREAMS = 16;

    /** Where one chunk is. */
    private record Slot(long offset, int stored, int raw, byte codec, int crc) {}

    private final FileChannel channel;
    private final Map<String, String> metadata;
    private final Map<String, SortedMap<ChunkKey, Slot>> streams;

    private AwfWorldFile(final FileChannel channel, final Map<String, String> metadata,
                         final Map<String, SortedMap<ChunkKey, Slot>> streams) {
        this.channel = channel;
        this.metadata = metadata;
        this.streams = streams;
    }

    /** Whether a file starts like an AWF world file. */
    public static boolean looksLike(final byte[] head) {
        return head.length >= 4 && ((head[0] & 0xFF) << 24 | (head[1] & 0xFF) << 16 | (head[2] & 0xFF) << 8
            | head[3] & 0xFF) == MAGIC;
    }

    /** Opens a file, reading its trailer and index only. */
    public static AwfWorldFile open(final Path file) throws IOException {
        final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        try {
            final long size = channel.size();
            if (size < 8 + TRAILER_BYTES) throw new AwfFile.CorruptAwfException(file + " is too short to be an AWF world");
            final ByteBuffer head = read(channel, 0, 8);
            if (head.getInt() != MAGIC) throw new AwfFile.CorruptAwfException(file + " is not an AWF world file");
            final int version = head.getInt();
            if (version != VERSION) throw new AwfFile.CorruptAwfException(file + " is AWF world version " + version + ", not " + VERSION);
            final ByteBuffer trailer = read(channel, size - TRAILER_BYTES, TRAILER_BYTES);
            final long indexOffset = trailer.getLong();
            final int indexLength = trailer.getInt();
            final int indexCrc = trailer.getInt();
            if (trailer.getInt() != MAGIC) throw new AwfFile.CorruptAwfException(file + " has no AWF trailer (truncated?)");
            if (indexOffset < 8 || indexLength < 0 || indexLength > MAX_INDEX_BYTES
                || indexOffset + indexLength != size - TRAILER_BYTES) {
                throw new AwfFile.CorruptAwfException(file + " has an index outside the file");
            }
            final byte[] indexBytes = read(channel, indexOffset, indexLength).array();
            if (crc(indexBytes) != indexCrc) throw new AwfFile.CorruptAwfException(file + " index fails its CRC32C");
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(indexBytes));
            final int metaCount = in.readInt();
            if (metaCount < 0 || metaCount > AwfFile.MAX_META) throw new AwfFile.CorruptAwfException("bad metadata count " + metaCount);
            final Map<String, String> metadata = new TreeMap<>();
            for (int i = 0; i < metaCount; i++) metadata.put(in.readUTF(), in.readUTF());
            final int streamCount = in.readInt();
            if (streamCount < 0 || streamCount > MAX_STREAMS) throw new AwfFile.CorruptAwfException("bad stream count " + streamCount);
            final Map<String, SortedMap<ChunkKey, Slot>> streams = new LinkedHashMap<>();
            for (int s = 0; s < streamCount; s++) {
                final String name = in.readUTF();
                final int count = in.readInt();
                if (count < 0 || count > AwfFile.MAX_CHUNKS) throw new AwfFile.CorruptAwfException("bad chunk count " + count);
                final SortedMap<ChunkKey, Slot> index = new TreeMap<>();
                for (int i = 0; i < count; i++) {
                    final ChunkKey key = new ChunkKey(in.readInt(), in.readInt());
                    final Slot slot = new Slot(in.readLong(), in.readInt(), in.readInt(), in.readByte(), in.readInt());
                    if (slot.offset() < 8 || slot.stored() < 0 || slot.raw() < 0 || slot.raw() > AwfFile.MAX_CHUNK_BYTES
                        || slot.offset() + slot.stored() > indexOffset) {
                        throw new AwfFile.CorruptAwfException(name + " chunk " + key + " points outside the chunk data");
                    }
                    if (index.put(key, slot) != null) throw new AwfFile.CorruptAwfException("duplicate " + name + " chunk " + key);
                }
                if (streams.put(name, Collections.unmodifiableSortedMap(index)) != null) {
                    throw new AwfFile.CorruptAwfException("duplicate stream " + name);
                }
            }
            return new AwfWorldFile(channel, Collections.unmodifiableMap(metadata), Collections.unmodifiableMap(streams));
        } catch (final IOException | RuntimeException failure) {
            channel.close();
            throw failure instanceof IOException io ? io : new AwfFile.CorruptAwfException(String.valueOf(failure.getMessage()));
        }
    }

    private static ByteBuffer read(final FileChannel channel, final long position, final int length) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(length);
        long at = position;
        while (buffer.hasRemaining()) {
            final int n = channel.read(buffer, at);
            if (n < 0) throw new AwfFile.CorruptAwfException("AWF world file is truncated");
            at += n;
        }
        return buffer.flip();
    }

    static int crc(final byte[] bytes) {
        final CRC32C crc = new CRC32C();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    public Map<String, String> metadata() {
        return this.metadata;
    }

    public Set<String> streamNames() {
        return this.streams.keySet();
    }

    /** One stream's chunks, decompressed and checked on demand; an absent stream is empty. */
    public ChunkSource stream(final String name) {
        final SortedMap<ChunkKey, Slot> index = this.streams.getOrDefault(name, Collections.emptySortedMap());
        return new ChunkSource() {
            @Override
            public Optional<byte[]> read(final ChunkKey key) throws IOException {
                final Slot slot = index.get(key);
                if (slot == null) return Optional.empty();
                final byte[] stored = AwfWorldFile.read(AwfWorldFile.this.channel, slot.offset(), slot.stored()).array();
                final byte[] raw = Compression.decompress(slot.codec(), stored, slot.raw(), key);
                if (crc(raw) != slot.crc()) throw new AwfFile.CorruptAwfException(name + " chunk " + key + " fails its CRC32C");
                return Optional.of(raw);
            }

            @Override
            public Set<ChunkKey> keys() {
                return index.keySet();
            }
        };
    }

    /** Chunks per stream, for display. */
    public Map<String, Integer> counts() {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        this.streams.forEach((name, index) -> counts.put(name, index.size()));
        return counts;
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }

    /** Starts a file at {@code target}; nothing appears there until {@link Writer#finish}. */
    public static Writer writer(final Path target) throws IOException {
        return new Writer(target, Compression.codecForWriting());
    }

    static Writer writer(final Path target, final byte codec) throws IOException {
        return new Writer(target, codec);
    }

    /**
     * Writes chunks as they come; holds only the index in memory. Not thread safe.
     */
    public static final class Writer implements AutoCloseable {
        private record Entry(ChunkKey key, Slot slot) {}

        private final Path target;
        private final Path temporary;
        private final FileChannel channel;
        private final OutputStream out;
        private final byte codec;
        private final Map<String, List<Entry>> streams = new LinkedHashMap<>();
        private long position;
        private boolean finished;

        private Writer(final Path target, final byte codec) throws IOException {
            this.target = target;
            this.codec = codec;
            final Path parent = target.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            this.temporary = Files.createTempFile(parent, "." + target.getFileName(), ".tmp");
            this.channel = FileChannel.open(this.temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            this.out = new BufferedOutputStream(Channels.newOutputStream(this.channel), 256 * 1024);
            final DataOutputStream header = new DataOutputStream(this.out);
            header.writeInt(MAGIC);
            header.writeInt(VERSION);
            this.position = 8;
        }

        public byte codec() {
            return this.codec;
        }

        /** Appends one chunk to a stream. A chunk already in that stream is refused. */
        public void add(final String stream, final ChunkKey key, final byte[] raw) throws IOException {
            if (raw.length > AwfFile.MAX_CHUNK_BYTES) throw new IOException(stream + " chunk " + key + " is too large");
            final byte[] stored = Compression.compress(this.codec, raw);
            this.out.write(stored);
            this.streams.computeIfAbsent(stream, ignored -> new ArrayList<>())
                .add(new Entry(key, new Slot(this.position, stored.length, raw.length, this.codec, crc(raw))));
            this.position += stored.length;
        }

        /** Writes the index and trailer, forces the file and moves it into place. */
        public void finish(final Map<String, String> metadata) throws IOException {
            if (metadata.size() > AwfFile.MAX_META) throw new IllegalArgumentException("too many metadata entries");
            if (this.streams.size() > MAX_STREAMS) throw new IllegalArgumentException("too many streams");
            final ByteArrayOutputStream indexBytes = new ByteArrayOutputStream();
            final DataOutputStream index = new DataOutputStream(indexBytes);
            index.writeInt(metadata.size());
            for (final Map.Entry<String, String> entry : new TreeMap<>(metadata).entrySet()) {
                index.writeUTF(entry.getKey());
                index.writeUTF(entry.getValue());
            }
            index.writeInt(this.streams.size());
            for (final Map.Entry<String, List<Entry>> stream : this.streams.entrySet()) {
                index.writeUTF(stream.getKey());
                final Set<ChunkKey> seen = new java.util.HashSet<>();
                index.writeInt(stream.getValue().size());
                for (final Entry entry : stream.getValue()) {
                    if (!seen.add(entry.key())) throw new IOException("duplicate " + stream.getKey() + " chunk " + entry.key());
                    index.writeInt(entry.key().x());
                    index.writeInt(entry.key().z());
                    index.writeLong(entry.slot().offset());
                    index.writeInt(entry.slot().stored());
                    index.writeInt(entry.slot().raw());
                    index.writeByte(entry.slot().codec());
                    index.writeInt(entry.slot().crc());
                }
            }
            final byte[] indexRaw = indexBytes.toByteArray();
            final DataOutputStream tail = new DataOutputStream(this.out);
            tail.write(indexRaw);
            tail.writeLong(this.position);
            tail.writeInt(indexRaw.length);
            tail.writeInt(crc(indexRaw));
            tail.writeInt(MAGIC);
            tail.flush();
            this.channel.force(true);
            this.channel.close();
            try {
                // A world file is meant to be shared; the temporary file was created owner-only.
                Files.setPosixFilePermissions(this.temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            } catch (final UnsupportedOperationException notPosix) {
                // Windows: the file inherits its folder's permissions.
            }
            try {
                Files.move(this.temporary, this.target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException notAtomic) {
                Files.move(this.temporary, this.target, StandardCopyOption.REPLACE_EXISTING);
            }
            this.finished = true;
        }

        /** Abandons an unfinished file. */
        @Override
        public void close() throws IOException {
            if (this.finished) return;
            this.channel.close();
            Files.deleteIfExists(this.temporary);
        }
    }
}
