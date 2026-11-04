package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed chunk storage: each object is a chunk's deflated bytes, named by the SHA-256
 * of its uncompressed bytes.
 *
 * <p>Content addressing is what makes {@link PersistenceMode#INCREMENTAL} both cheap and safe: a
 * chunk that has not changed already has an object with its name, so a commit writes only new
 * objects and references the rest, while every generation still describes the complete world.</p>
 *
 * <p>Writes are batched for durability. {@link #put} writes an object to a temporary file in its
 * shard and keeps it pending; {@link #sync} then forces every pending file in one pass, moves each
 * into place under its name, and forces each touched directory once. So an object either exists
 * whole under its name — forced before it was renamed, exactly as when every object was forced on
 * its own — or is a temporary file that the next writable open removes. The caller forces nothing that
 * references an object before {@link #sync} has returned. Objects no retained generation
 * references are removed by {@link #retainOnly}; that runs after a commit, so a crash before it
 * only leaves garbage.</p>
 */
final class ObjectStore {

    private static final int RAW_LENGTH_BYTES = 4;
    private static final String TEMP_SUFFIX = ".tmp";
    /** What {@link #name} produces: lower-case SHA-256 hex. Nothing else is ever resolved to a path. */
    static final Pattern NAME = Pattern.compile("[0-9a-f]{64}");
    private final Path root;
    private final Durability durability;
    /** Whether this store may create, write or delete anything; false for roles that take no commits. */
    private final boolean writable;
    /** Objects written since the last {@link #sync}: name to its temporary file. Guarded by this. */
    private final Map<String, Path> pending = new LinkedHashMap<>();
    /**
     * Shards with an object renamed into them whose force has not succeeded yet. Kept across a
     * failed {@link #sync}: an object already in place must still get its directory forced by the
     * retry, or an index could become durable while the entry naming the object is not.
     */
    private final Set<Path> dirtyShards = new LinkedHashSet<>();
    /** Whether a shard was created since the root was last forced successfully. */
    private boolean rootDirty;

    ObjectStore(final Path root) throws IOException {
        this(root, Durability.DISK);
    }

    /** A writable store; see {@link #ObjectStore(Path, Durability, WorldRole)}. */
    ObjectStore(final Path root, final Durability durability) throws IOException {
        this(root, durability, WorldRole.VANILLA);
    }

    /**
     * Opens the object directory for a world of {@code role}. A role that accepts commits creates
     * the directory and removes the temporary files a crash left; any other role changes nothing on
     * disk (it would otherwise delete the pending objects of a live writer of the same store) and
     * refuses {@link #put}, {@link #delete} and {@link #retainOnly}.
     */
    ObjectStore(final Path root, final Durability durability, final WorldRole role) throws IOException {
        this.root = root;
        this.durability = durability;
        this.writable = role.acceptsCommits();
        if (!this.writable) return;
        // A fresh store: the entries naming the root and any ancestor it needed are made durable
        // now, before any commit can depend on them.
        durability.createDirectoriesDurably(root);
        // Unfinished writes from a crash.
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(root)) {
            for (final Path shard : shards) {
                if (!Files.isDirectory(shard)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(shard, "*" + TEMP_SUFFIX)) {
                    for (final Path temp : files) Files.deleteIfExists(temp);
                }
            }
        }
    }

    static String name(final byte[] raw) {
        return HexFormat.of().formatHex(AwfFile.sha256(raw));
    }

    /**
     * Where an object lives. Refuses anything but an object name, so a damaged or hostile index
     * can never make this store read or delete a file outside its directory.
     */
    private Path path(final String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("not an AWF object name: " + name);
        return this.root.resolve(name.substring(0, 2)).resolve(name);
    }

    private void requireWritable() {
        if (!this.writable) throw new IllegalStateException("this AWF object store was opened read-only");
    }

    /** Whether an object is in place under its name; a pending one is not yet. */
    boolean contains(final String name) {
        return Files.isRegularFile(path(name));
    }

    /**
     * Writes a chunk as a pending object; {@link #sync} makes it durable and names it. Readable
     * through {@link #get} meanwhile.
     *
     * @param rewrite write even if an object of this name exists (FULL mode)
     * @return the object's name and the bytes written, 0 when it already existed or is pending
     */
    synchronized Written put(final byte[] raw, final boolean rewrite) throws IOException {
        requireWritable();
        final String name = name(raw);
        final Path pendingTemp = this.pending.get(name);
        if (pendingTemp != null) {
            if (Files.isRegularFile(pendingTemp)) return new Written(name, 0L);
            // Its temporary file was removed under us (a temp cleaner, antivirus): written again below.
            this.pending.remove(name);
        }
        final Path target = path(name);
        if (!rewrite && Files.isRegularFile(target)) {
            return new Written(name, 0L);
        }
        final Path shard = target.getParent();
        if (!Files.isDirectory(shard)) {
            Files.createDirectories(shard);
            this.rootDirty = true;
        }
        final byte[] deflated = AwfFile.deflate(raw);
        final Path temp = Files.createTempFile(shard, name, TEMP_SUFFIX);
        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            final ByteBuffer buffer = ByteBuffer.allocate(RAW_LENGTH_BYTES + deflated.length);
            buffer.putInt(raw.length).put(deflated).flip();
            while (buffer.hasRemaining()) out.write(buffer);
        } catch (final IOException | RuntimeException failed) {
            Files.deleteIfExists(temp);
            throw failed;
        }
        this.pending.put(name, temp);
        return new Written(name, RAW_LENGTH_BYTES + deflated.length);
    }

    record Written(String name, long bytes) {}

    /**
     * Makes every pending object durable and puts it in place: one pass of file forces, then the
     * moves, then one force per dirty directory (shards, then the root when a shard was created).
     * Returns how many objects were synced. On failure everything not yet done stays pending or
     * dirty, so a later sync finishes it — including the directory of an object the failed
     * attempt had already renamed; nothing referencing a pending object may be forced until this
     * returns. A pending object whose temporary file has disappeared (removed by something outside
     * the server) is dropped from the batch and the sync fails, so the commit fails once and its
     * retry writes the object again ({@link #put} no longer finds it pending).
     */
    synchronized int sync() throws IOException {
        if (this.pending.isEmpty() && this.dirtyShards.isEmpty() && !this.rootDirty) return 0;
        NoSuchFileException vanished = null;
        for (final Iterator<Path> it = this.pending.values().iterator(); it.hasNext();) {
            try {
                this.durability.forceFile(it.next());
            } catch (final NoSuchFileException missing) {
                it.remove();
                if (vanished == null) vanished = missing;
            }
        }
        if (vanished != null) throw vanishedTemp(vanished);
        int synced = 0;
        for (final Iterator<Map.Entry<String, Path>> it = this.pending.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<String, Path> object = it.next();
            final Path target = path(object.getKey());
            this.dirtyShards.add(target.getParent());
            try {
                this.durability.moveAtomically(object.getValue(), target);
            } catch (final NoSuchFileException missing) {
                // Gone between its force and its rename: dropped like above.
                it.remove();
                throw vanishedTemp(missing);
            } catch (final AtomicMoveNotSupportedException notAtomic) {
                // The temporary file sits in the target's own shard, so a refusal here means the
                // filesystem cannot rename atomically at all: an object must appear whole under
                // its name or not at all, and a copy-and-delete would not promise that. The
                // object stays pending and the commit fails.
                throw new IOException("AWF needs an atomic rename to publish " + target + " and this filesystem refused one;"
                    + " the commit is refused rather than published by copying", notAtomic);
            }
            it.remove();
            synced++;
        }
        for (final Iterator<Path> it = this.dirtyShards.iterator(); it.hasNext();) {
            this.durability.forceDirectory(it.next());
            it.remove();
        }
        if (this.rootDirty) {
            this.durability.forceDirectory(this.root);
            this.rootDirty = false;
        }
        return synced;
    }

    private static IOException vanishedTemp(final NoSuchFileException missing) {
        return new IOException("a pending AWF object's temporary file " + missing.getFile() + " disappeared before it was"
            + " made durable (removed outside the server?); it was dropped from the batch and the next commit attempt"
            + " writes it again", missing);
    }

    /** Directories whose entries still need a force; non-empty only after a failed {@link #sync}. */
    synchronized int dirtyDirectories() {
        return this.dirtyShards.size() + (this.rootDirty ? 1 : 0);
    }

    /** Objects written and not yet synced. */
    synchronized int pending() {
        return this.pending.size();
    }

    /** Reads and verifies an object, pending or in place. */
    byte[] get(final String name) throws IOException {
        final Path temp;
        synchronized (this) {
            temp = this.pending.get(name);
        }
        byte[] stored = null;
        if (temp != null) {
            try {
                stored = Files.readAllBytes(temp);
            } catch (final NoSuchFileException moved) {
                // A sync put it in place between the lookup and the read.
            }
        }
        if (stored == null) {
            try {
                stored = Files.readAllBytes(path(name));
            } catch (final NoSuchFileException missing) {
                throw new AwfFile.CorruptAwfException("object " + name + " is missing");
            }
        }
        if (stored.length < RAW_LENGTH_BYTES) throw new AwfFile.CorruptAwfException("object " + name + " is truncated");
        final int rawLength = ByteBuffer.wrap(stored, 0, RAW_LENGTH_BYTES).getInt();
        if (rawLength < 0 || rawLength > AwfFile.MAX_CHUNK_BYTES) {
            throw new AwfFile.CorruptAwfException("object " + name + " declares size " + rawLength);
        }
        final byte[] body = java.util.Arrays.copyOfRange(stored, RAW_LENGTH_BYTES, stored.length);
        final byte[] raw = AwfFile.inflate(body, rawLength, null);
        if (!name(raw).equals(name)) throw new AwfFile.CorruptAwfException("object " + name + " fails its SHA-256");
        return raw;
    }

    /** Deletes one object, pending or in place, if it exists; returns whether it did. */
    synchronized boolean delete(final String name) throws IOException {
        requireWritable();
        final Path temp = this.pending.remove(name);
        if (temp != null) {
            Files.deleteIfExists(temp);
            return true;
        }
        return Files.deleteIfExists(path(name));
    }

    /**
     * Deletes every object in place that is not in {@code live}; returns how many were removed.
     * Pending objects are left alone: they are this store's own unfinished batch.
     */
    synchronized int retainOnly(final Set<String> live) throws IOException {
        requireWritable();
        int removed = 0;
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(this.root)) {
            for (final Path shard : shards) {
                if (!Files.isDirectory(shard)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(shard)) {
                    for (final Path object : files) {
                        final String name = object.getFileName().toString();
                        if (name.endsWith(TEMP_SUFFIX) || live.contains(name)) continue;
                        if (Files.deleteIfExists(object)) removed++;
                    }
                }
            }
        }
        return removed;
    }
}
