package dev.iyanz.sourbycraft.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StallFilesTest {

    @TempDir
    Path tmp;

    private static long at(final String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void createsTheDirectoryAndNamesTheFileByUtcTime() throws IOException {
        final StallFiles files = new StallFiles(this.tmp.resolve("logs/stalls"), 20);
        final Path file = files.create(at("2026-10-22T04:05:06Z"));
        assertEquals("stall-20261022-040506.txt", file.getFileName().toString());
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void twoStallsInTheSameSecondGetDistinctFilesInOrder() throws IOException {
        final StallFiles files = new StallFiles(this.tmp, 20);
        final Path first = files.create(at("2026-10-22T04:05:06Z"));
        final Path second = files.create(at("2026-10-22T04:05:06Z"));
        final Path third = files.create(at("2026-10-22T04:05:06Z"));
        assertEquals("stall-20261022-040506.txt", first.getFileName().toString());
        assertEquals("stall-20261022-040506-2.txt", second.getFileName().toString());
        assertEquals("stall-20261022-040506-3.txt", third.getFileName().toString());
        assertEquals(List.of(first, second, third), files.list(), "oldest first, the suffixed ones after the plain name");
    }

    @Test
    void keepsOnlyTheNewestFilesIncludingTheOneJustCreated() throws IOException {
        final StallFiles files = new StallFiles(this.tmp, 3);
        final Path[] created = new Path[6];
        for (int i = 0; i < created.length; ++i) {
            created[i] = files.create(at("2026-10-22T04:00:0" + i + "Z"));
        }
        assertEquals(List.of(created[3], created[4], created[5]), files.list());
        assertFalse(Files.exists(created[0]));
        assertFalse(Files.exists(created[2]));
    }

    @Test
    void rotationNeverTouchesFilesItDidNotCreate() throws IOException {
        Files.writeString(this.tmp.resolve("latest.log"), "x");
        Files.writeString(this.tmp.resolve("stall-notes.md"), "x");
        Files.createDirectory(this.tmp.resolve("stall-dir.txt"));
        final StallFiles files = new StallFiles(this.tmp, 1);
        files.create(at("2026-10-22T04:00:00Z"));
        files.create(at("2026-10-22T04:00:01Z"));
        assertEquals(1, files.list().size());
        assertTrue(Files.exists(this.tmp.resolve("latest.log")));
        assertTrue(Files.exists(this.tmp.resolve("stall-notes.md")));
        assertTrue(Files.isDirectory(this.tmp.resolve("stall-dir.txt")));
    }

    @Test
    void aSymlinkNamedLikeAStallFileIsIgnoredAndItsTargetKept() throws IOException {
        final Path target = Files.writeString(this.tmp.resolve("precious.dat"), "keep me");
        final Path dir = Files.createDirectory(this.tmp.resolve("d"));
        try {
            Files.createSymbolicLink(dir.resolve("stall-20000101-000000.txt"), target);
        } catch (final UnsupportedOperationException | IOException noSymlinks) {
            return; // platform without symlinks
        }
        final StallFiles files = new StallFiles(dir, 1);
        files.create(at("2026-10-22T04:00:00Z"));
        files.create(at("2026-10-22T04:00:01Z"));
        assertEquals("keep me", Files.readString(target));
    }

    @Test
    void appendAddsToTheEndAndIsReadableAfterwards() throws IOException {
        final StallFiles files = new StallFiles(this.tmp, 20);
        final Path file = files.create(at("2026-10-22T04:00:00Z"));
        files.append(file, "dump one\n");
        files.append(file, "dump two é\n");
        assertEquals("dump one\ndump two é\n", Files.readString(file));
    }
}
