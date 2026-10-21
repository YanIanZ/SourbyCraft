package dev.iyanz.sourbycraft.diagnostics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The {@code logs/stalls/} directory: one {@code stall-<UTC time>.txt} per stall, dumps appended to
 * it, and only the newest {@code keep} files retained so a server that stalls every few minutes for
 * weeks cannot fill its disk.
 */
final class StallFiles {

    private static final String PREFIX = "stall-";
    private static final String SUFFIX = ".txt";
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path directory;
    private final int keep;

    StallFiles(final Path directory, final int keep) {
        this.directory = directory;
        this.keep = keep;
    }

    Path directory() {
        return this.directory;
    }

    /**
     * Creates the next stall file (deleting the oldest ones first so at most {@code keep} remain
     * afterwards). A second stall in the same second gets a {@code -2}, {@code -3}... suffix.
     */
    synchronized Path create(final long epochMillis) throws IOException {
        Files.createDirectories(this.directory);
        this.prune(this.keep - 1);
        final String stamp = STAMP.format(Instant.ofEpochMilli(epochMillis));
        for (int n = 1; ; ++n) {
            final Path candidate = this.directory.resolve(PREFIX + stamp + (n == 1 ? "" : "-" + n) + SUFFIX);
            try {
                Files.createFile(candidate);
                return candidate;
            } catch (final FileAlreadyExistsException taken) {
                if (n > 1000) {
                    throw taken;
                }
            }
        }
    }

    /** Appends {@code text} as one write and asks the OS to push it to disk (a killed process loses nothing already written). */
    void append(final Path file, final String text) throws IOException {
        final ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.APPEND)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            try {
                channel.force(false);
            } catch (final IOException unsupported) {
                // best effort: the bytes are already in the page cache, which survives a SIGKILL
            }
        }
    }

    /** Deletes the oldest stall files until at most {@code retain} remain. */
    synchronized void prune(final int retain) throws IOException {
        final List<Path> files = this.list();
        for (int i = 0; i < files.size() - Math.max(0, retain); ++i) {
            Files.deleteIfExists(files.get(i));
        }
    }

    /** Stall files, oldest first. */
    synchronized List<Path> list() throws IOException {
        if (!Files.isDirectory(this.directory)) {
            return List.of();
        }
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(this.directory)) {
            stream.filter(p -> {
                final String name = p.getFileName().toString();
                return name.startsWith(PREFIX) && name.endsWith(SUFFIX)
                    && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
            }).forEach(files::add);
        }
        // Compare without the extension: "x" sorts before "x-2", where ".txt" would put "x.txt" after "x-2.txt".
        files.sort(Comparator.comparing(p -> {
            final String name = p.getFileName().toString();
            return name.substring(0, name.length() - SUFFIX.length());
        }));
        return files;
    }
}
