package dev.iyanz.sourbycraft.testplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.OptionalInt;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenFilesTest {

    @TempDir Path dir;

    @Test
    void lsofOutputIsCountedByPathPrefixNotBySubstring() {
        final Path root = Path.of("/srv/world");
        final String output = "p123\nfcwd\nn/srv/world\nf5\nn/srv/world/region/r.0.0.mca\nf6\nn/srv/world2/x\nf7\nn/srv/other\n";
        assertEquals(2, OpenFiles.countLsofNames(output, root));
        assertEquals(0, OpenFiles.countLsofNames("", root));
    }

    @Test
    void anOpenFileUnderTheDirectoryIsSeenAndAClosedOneIsNot() throws IOException {
        final Path file = Files.createFile(this.dir.resolve("r.0.0.mca"));
        final OptionalInt before = OpenFiles.under(this.dir);
        Assumptions.assumeTrue(before.isPresent(), "neither /proc/self/fd nor lsof is available");
        assertEquals(0, before.getAsInt());
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertTrue(OpenFiles.under(this.dir).getAsInt() >= 1);
            assertTrue(channel.isOpen());
        }
        assertEquals(0, OpenFiles.under(this.dir).getAsInt());
    }
}
