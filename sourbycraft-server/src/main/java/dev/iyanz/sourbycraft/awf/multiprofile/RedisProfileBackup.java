package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The verified pre-migration export of a Redis profile namespace into files (OD-2: the way back from
 * format 2 is loading this export into an isolated Redis).
 *
 * <p>{@link #create} resolves {@code migration.backup-dir} (relative to the server directory when not
 * absolute), creates a new {@code multiprofile-redis-format1-<utc time>-<random>} directory inside it and
 * exports every key of the namespace, one file per key under {@code keys/}, with a bounded {@code SCAN}
 * and bounded {@code HSCAN}/{@code SSCAN}/{@code ZSCAN}/{@code GET} reads (each value at most a record's
 * size). Each file is forced, re-read and compared by SHA-256 and size, and only then is
 * {@value #MANIFEST} written durably (namespace, counts, one line per file, last line {@code complete}).
 * A crash before that last line leaves a directory {@link #find} ignores.</p>
 *
 * <p>File format of one key: {@code int keyLength, key (UTF-8), byte type} ({@code 1} hash, {@code 2} set,
 * {@code 3} sorted set, {@code 4} string), then entries each introduced by a {@code 1} byte and closed by a
 * {@code 0} byte: a hash entry is {@code int fieldLength, field, int valueLength, value}; a set member
 * {@code int length, member}; a sorted-set member the same followed by its score as a {@code double}; a
 * string {@code int length, value}. Nothing here reads the export back into Redis; restoring is the
 * operator's procedure with the Redis tooling of their choice.</p>
 *
 * <p>Redis has no migration lock: the export is consistent only while every writer of the namespace is
 * stopped, which the caller checks as far as it can ({@link ProfileMigrationStore#requireNoOtherWriter}).
 * Blocking I/O proportional to the store size; offline migration only.</p>
 */
final class RedisProfileBackup {

    static final String PREFIX = "multiprofile-redis-format1-";
    static final String MANIFEST = "backup.manifest";
    private static final String COMPLETE = "complete";
    private static final int MAX_MANIFEST = 64 * 1024 * 1024;
    private static final int MAX_BACKUPS_LISTED = 4096;
    private static final int MAX_KEYS = 1_000_000;
    /** Largest single value a key may hold: a snapshot frame. */
    private static final int VALUE_BOUND = ProfileRecords.MAX_PAYLOAD + ProfileRecords.OVERHEAD;
    private static final byte TYPE_HASH = 1;
    private static final byte TYPE_SET = 2;
    private static final byte TYPE_ZSET = 3;
    private static final byte TYPE_STRING = 4;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private RedisProfileBackup() {}

    private record Exported(String relative, long size, String sha256) {}

    /** Exports and verifies every key of {@code namespace}; returns the backup directory. */
    static Path create(final RedisClient client, final String namespace, final String configuredDir) throws IOException {
        return create(client, namespace, List.of(), configuredDir);
    }

    /**
     * Exports and verifies every key of {@code namespace} and of each of {@code alsoPrefixes} (the fence and
     * session-epoch counters that live under the fabric prefix; without them a restore would restart the
     * fences at 0); returns the backup directory.
     */
    static Path create(final RedisClient client, final String namespace, final List<String> alsoPrefixes, final String configuredDir)
        throws IOException {
        final Path backupRoot = resolveBackupRoot(configuredDir, true);
        final Path target = backupRoot.resolve(PREFIX + TIME.format(java.time.Instant.now()) + "-"
            + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectory(target);
        FileProfileIo.forceDirectory(backupRoot);
        if (!target.toRealPath().getParent().equals(backupRoot)) {
            throw new IOException("the backup directory " + target + " does not resolve inside " + backupRoot + "; refused");
        }
        final Path keysDir = target.resolve("keys");
        Files.createDirectory(keysDir);
        final List<Exported> exported = new ArrayList<>();
        int number = 0;
        final Set<String> keys = new LinkedHashSet<>(scan(client, namespace));
        for (final String prefix : alsoPrefixes) keys.addAll(scan(client, prefix));
        for (final String key : keys) {
            final String relative = "keys/" + (number++) + ".awfk";
            exported.add(export(client, key, target.resolve(relative), relative));
        }
        FileProfileIo.forceDirectory(keysDir);
        long bytes = 0L;
        for (final Exported file : exported) {
            final Path copy = target.resolve(file.relative());
            if (Files.isSymbolicLink(copy) || Files.size(copy) != file.size() || !digest(copy).equals(file.sha256())) {
                throw new IOException("the backup file " + copy + " does not read back as written; the backup is incomplete and"
                    + " the migration does not start");
            }
            bytes += file.size();
        }
        final StringBuilder manifest = new StringBuilder();
        manifest.append("source=redis ").append(namespace).append('\n');
        manifest.append("created=").append(System.currentTimeMillis()).append('\n');
        manifest.append("files=").append(exported.size()).append('\n');
        manifest.append("bytes=").append(bytes).append('\n');
        for (final Exported file : exported) {
            manifest.append(file.sha256()).append(' ').append(file.size()).append(' ').append(file.relative()).append('\n');
        }
        manifest.append(COMPLETE).append('\n');
        FileProfileIo.writeDurable(target.resolve(MANIFEST), manifest.toString().getBytes(StandardCharsets.UTF_8));
        return target;
    }

    /**
     * The newest complete export of {@code namespace} in the backup directory whose files all exist with
     * their recorded sizes (digests were compared when the export was written).
     */
    static Optional<Path> find(final String namespace, final String configuredDir) throws IOException {
        final Path backupRoot = resolveBackupRoot(configuredDir, false);
        if (backupRoot == null) return Optional.empty();
        final List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(backupRoot, PREFIX + "*")) {
            for (final Path directory : found) {
                if (candidates.size() >= MAX_BACKUPS_LISTED) break;
                if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) candidates.add(directory);
            }
        }
        candidates.sort(java.util.Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
        for (final Path directory : candidates) {
            final Path manifest = directory.resolve(MANIFEST);
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) continue;
            final String text = new String(FileProfileIo.readBounded(manifest, MAX_MANIFEST), StandardCharsets.UTF_8);
            final List<String> lines = text.lines().toList();
            if (lines.isEmpty() || !lines.getLast().equals(COMPLETE) || !lines.getFirst().equals("source=redis " + namespace)) continue;
            boolean intact = true;
            for (final String line : lines) {
                final String[] parts = line.split(" ", 3);
                if (parts.length != 3 || parts[0].length() != 64) continue;
                final Path copy = directory.resolve(parts[2]).normalize();
                if (!copy.startsWith(directory) || !Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(copy) != Long.parseLong(parts[1])) {
                    intact = false;
                    break;
                }
            }
            if (intact) return Optional.of(directory);
        }
        return Optional.empty();
    }

    /** Every key of the namespace, in SCAN order, at most {@value #MAX_KEYS}. */
    private static List<String> scan(final RedisClient client, final String namespace) throws IOException {
        final Set<String> keys = new LinkedHashSet<>();
        String cursor = "0";
        do {
            final Object reply = client.call("SCAN", cursor, "MATCH", namespace + "*", "COUNT", "1000");
            final List<?> page = reply instanceof List<?> list ? list : List.of();
            if (page.size() < 2) throw new IOException("SCAN of " + namespace + "* returned a malformed reply");
            cursor = RedisClient.text(page.get(0));
            for (final Object key : (page.get(1) instanceof List<?> list ? list : List.of())) {
                final String text = RedisClient.text(key);
                if (text == null || !text.startsWith(namespace)) continue;
                keys.add(text);
                if (keys.size() > MAX_KEYS) throw new IOException("more than " + MAX_KEYS + " keys under " + namespace + "; refusing to export");
            }
        } while (!"0".equals(cursor));
        return List.copyOf(keys);
    }

    private static Exported export(final RedisClient client, final String key, final Path file, final String relative) throws IOException {
        final String type = RedisClient.text(client.call("TYPE", key));
        final MessageDigest sha = sha256();
        long size;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             CountingOutput output = new CountingOutput(new DigestOutputStream(java.nio.channels.Channels.newOutputStream(channel), sha))) {
            final byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            output.writeInt(keyBytes.length);
            output.write(keyBytes);
            switch (type == null ? "" : type) {
                case "hash" -> {
                    output.write(TYPE_HASH);
                    scanEntries(client, "HSCAN", key, flat -> {
                        for (int i = 0; i + 1 < flat.size(); i += 2) {
                            output.write(1);
                            output.writeBulk(bytes(flat.get(i), key));
                            output.writeBulk(bytes(flat.get(i + 1), key));
                        }
                    });
                }
                case "set" -> {
                    output.write(TYPE_SET);
                    scanEntries(client, "SSCAN", key, flat -> {
                        for (final Object member : flat) {
                            output.write(1);
                            output.writeBulk(bytes(member, key));
                        }
                    });
                }
                case "zset" -> {
                    output.write(TYPE_ZSET);
                    scanEntries(client, "ZSCAN", key, flat -> {
                        for (int i = 0; i + 1 < flat.size(); i += 2) {
                            output.write(1);
                            output.writeBulk(bytes(flat.get(i), key));
                            final String score = RedisClient.text(flat.get(i + 1));
                            try {
                                output.writeDouble(Double.parseDouble(score));
                            } catch (final NumberFormatException | NullPointerException malformed) {
                                throw new IOException("sorted set " + key + " has a malformed score '" + score + "'");
                            }
                        }
                    });
                }
                case "string" -> {
                    output.write(TYPE_STRING);
                    output.write(1);
                    output.writeBulk(bytes(client.callBounded(VALUE_BOUND, "GET", key), key));
                }
                case "none" -> throw new IOException("key " + key + " disappeared during the export; another writer is active, the"
                    + " migration does not start");
                default -> throw new IOException("key " + key + " has type " + type + ", which the profile store never writes;"
                    + " the namespace is not a profile store, the migration does not start");
            }
            output.write(0);
            output.flush();
            channel.force(true);
            size = output.count;
        }
        return new Exported(relative, size, FileProfileIo.hex(sha.digest()));
    }

    private interface Entries {
        void accept(List<?> flat) throws IOException;
    }

    /** One {@code *SCAN} pass over a key with bounded value sizes. */
    private static void scanEntries(final RedisClient client, final String command, final String key, final Entries entries)
        throws IOException {
        String cursor = "0";
        do {
            final Object reply = client.callBounded(VALUE_BOUND, command, key, cursor, "COUNT", "32");
            final List<?> page = reply instanceof List<?> list ? list : List.of();
            if (page.size() < 2) throw new IOException(command + " of " + key + " returned a malformed reply");
            cursor = RedisClient.text(page.get(0));
            entries.accept(page.get(1) instanceof List<?> list ? list : List.of());
        } while (!"0".equals(cursor));
    }

    private static byte[] bytes(final Object reply, final String key) throws IOException {
        if (reply instanceof byte[] bytes) return bytes;
        final String text = RedisClient.text(reply);
        if (text == null) throw new IOException("key " + key + " returned a null value during the export");
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Path resolveBackupRoot(final String configuredDir, final boolean create) throws IOException {
        if (configuredDir == null || configuredDir.isBlank()) {
            throw new IOException("migration.backup-dir is not configured; a migration that writes needs a backup first");
        }
        Path configured = Path.of(configuredDir.strip());
        if (!configured.isAbsolute()) configured = Path.of("").toAbsolutePath().resolve(configured);
        configured = configured.normalize();
        if (create) {
            Files.createDirectories(configured);
        } else if (!Files.isDirectory(configured)) {
            return null;
        }
        return configured.toRealPath();
    }

    private static String digest(final Path file) throws IOException {
        final MessageDigest sha = sha256();
        try (InputStream input = new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), sha)) {
            final byte[] buffer = new byte[64 * 1024];
            while (input.read(buffer) != -1) {
                // digesting
            }
        }
        return FileProfileIo.hex(sha.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("the JDK has no SHA-256", impossible);
        }
    }

    /** Counts the bytes written and offers the length-prefixed primitives of the file format. */
    private static final class CountingOutput extends OutputStream {

        private final OutputStream out;
        long count;

        CountingOutput(final OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(final int b) throws IOException {
            this.out.write(b);
            this.count++;
        }

        @Override
        public void write(final byte[] data, final int offset, final int length) throws IOException {
            this.out.write(data, offset, length);
            this.count += length;
        }

        void writeInt(final int value) throws IOException {
            write(ByteBuffer.allocate(4).putInt(value).array());
        }

        void writeDouble(final double value) throws IOException {
            write(ByteBuffer.allocate(8).putDouble(value).array());
        }

        void writeBulk(final byte[] bytes) throws IOException {
            writeInt(bytes.length);
            write(bytes);
        }

        @Override
        public void flush() throws IOException {
            this.out.flush();
        }

        @Override
        public void close() throws IOException {
            this.out.close();
        }
    }
}
