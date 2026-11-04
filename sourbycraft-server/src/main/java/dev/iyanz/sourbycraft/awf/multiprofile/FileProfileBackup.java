package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The verified pre-migration copy of a FILE profile directory (OD-2: the way back from format 2 is
 * restoring this copy into an isolated store).
 *
 * <p>{@link #create} resolves the real path of the store and of {@code migration.backup-dir} (the
 * configured value, relative to the server directory when not absolute), refuses when either contains
 * the other, creates a new {@code multiprofile-format1-<utc time>-<random>} directory inside the backup
 * directory and refuses when it does not resolve to a direct child of it. It walks the store without
 * following links and refuses a store that contains a symbolic link or a special file, copies every
 * regular file except the held {@code repository.lock} and interrupted {@code *.tmp} writes, forces each
 * copy, re-reads each copy and compares its SHA-256 and size with the source, and only then writes
 * {@value #MANIFEST} (source path, counts, one line per file, last line {@code complete}) durably. A
 * crash before the manifest's last line leaves a directory {@link #find} ignores.</p>
 *
 * <p>Blocking file I/O proportional to the store size; offline migration only.</p>
 */
final class FileProfileBackup {

    static final String PREFIX = "multiprofile-format1-";
    static final String MANIFEST = "backup.manifest";
    private static final String COMPLETE = "complete";
    private static final int MAX_MANIFEST = 64 * 1024 * 1024;
    private static final int MAX_BACKUPS_LISTED = 4096;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private FileProfileBackup() {}

    /** One copied file of the manifest. */
    private record Copied(String relative, long size, String sha256) {}

    /** Copies and verifies the store; returns the backup directory. */
    static Path create(final Path storeRoot, final String configuredDir, final String lockFile) throws IOException {
        final Path source = storeRoot.toRealPath();
        final Path backupRoot = resolveBackupRoot(source, configuredDir, true);
        final Path target = backupRoot.resolve(PREFIX + TIME.format(java.time.Instant.now()) + "-"
            + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectory(target);
        FileProfileIo.forceDirectory(backupRoot);
        if (!target.toRealPath().getParent().equals(backupRoot)) {
            throw new IOException("the backup directory " + target + " does not resolve inside " + backupRoot + "; refused");
        }
        final List<Copied> copied = new ArrayList<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || attributes.isOther()) throw link(directory);
                if (!directory.equals(source)) {
                    Files.createDirectory(target.resolve(source.relativize(directory).toString()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) throw link(file);
                final String name = file.getFileName().toString();
                if (file.getParent().equals(source) && name.equals(lockFile)) return FileVisitResult.CONTINUE;
                if (name.endsWith(".tmp")) return FileVisitResult.CONTINUE;
                final String relative = source.relativize(file).toString();
                copied.add(copy(file, target.resolve(relative), relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path directory, final IOException failed) throws IOException {
                if (failed != null) throw failed;
                FileProfileIo.forceDirectory(directory.equals(source) ? target : target.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
        long bytes = 0L;
        for (final Copied file : copied) {
            final Path copy = target.resolve(file.relative());
            if (Files.isSymbolicLink(copy) || Files.size(copy) != file.size() || !digest(copy).equals(file.sha256())) {
                throw new IOException("the backup copy " + copy + " does not match its source; the backup is incomplete and the"
                    + " migration does not start");
            }
            bytes += file.size();
        }
        final StringBuilder manifest = new StringBuilder();
        manifest.append("source=").append(source).append('\n');
        manifest.append("created=").append(System.currentTimeMillis()).append('\n');
        manifest.append("files=").append(copied.size()).append('\n');
        manifest.append("bytes=").append(bytes).append('\n');
        for (final Copied file : copied) {
            manifest.append(file.sha256()).append(' ').append(file.size()).append(' ').append(file.relative()).append('\n');
        }
        manifest.append(COMPLETE).append('\n');
        FileProfileIo.writeDurable(target.resolve(MANIFEST), manifest.toString().getBytes(StandardCharsets.UTF_8));
        return target;
    }

    /**
     * The newest complete backup of this store in the backup directory, after checking that every file
     * the manifest names exists with its recorded size (sizes, not digests: the digests were compared
     * when the backup was written).
     *
     * <p>Known limits (Build 51, review P3-18, not fixed): the match is by source path and newest manifest,
     * so a store moved or a backup directory shared by two stores at the same path can be resumed against the
     * wrong backup; the completeness check compares sizes only; at most {@value #MAX_BACKUPS_LISTED} backup
     * directories are listed before the newest is chosen; and the IN_PROGRESS marker does not record which
     * backup it belongs to. The operator confirms the backup named in the migration report before resuming.</p>
     */
    static Optional<Path> find(final Path storeRoot, final String configuredDir) throws IOException {
        final Path source = storeRoot.toRealPath();
        final Path backupRoot = resolveBackupRoot(source, configuredDir, false);
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
            if (lines.isEmpty() || !lines.getLast().equals(COMPLETE) || !lines.getFirst().equals("source=" + source)) continue;
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

    /**
     * The real backup root; creates it when {@code create}. Refuses one that is, is inside, or contains
     * the store.
     */
    private static Path resolveBackupRoot(final Path source, final String configuredDir, final boolean create)
        throws IOException {
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
        final Path real = configured.toRealPath();
        if (real.startsWith(source) || source.startsWith(real)) {
            throw new IOException("migration.backup-dir " + real + " overlaps the profile directory " + source
                + " after resolving links; refused, nothing was written");
        }
        return real;
    }

    private static Copied copy(final Path from, final Path to, final String relative) throws IOException {
        final MessageDigest sha = sha256();
        long size = 0L;
        try (InputStream input = new DigestInputStream(Files.newInputStream(from, LinkOption.NOFOLLOW_LINKS), sha);
             FileChannel channel = FileChannel.open(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             OutputStream output = java.nio.channels.Channels.newOutputStream(channel)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                size += read;
            }
            output.flush();
            channel.force(true);
        }
        return new Copied(relative, size, FileProfileIo.hex(sha.digest()));
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

    private static IOException link(final Path path) {
        return new IOException("the profile directory contains " + path + ", which is a symbolic link or special file;"
            + " the backup refuses to follow or copy it, and the migration does not start");
    }
}
