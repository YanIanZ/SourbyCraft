package dev.iyanz.sourbycraft.testplugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdaterFixtureProbeTest {

    @TempDir Path dir;

    private Path jar(final String name, final byte[] marker) throws IOException {
        final Path file = this.dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (marker != null) {
                zip.putNextEntry(new ZipEntry(UpdaterFixtureProbe.MARKER));
                zip.write(marker);
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void aFailMarkerAsksForTheGivenExitCode() throws IOException {
        final UpdaterFixtureProbe.Marker m = UpdaterFixtureProbe.parse("role=new-broken\nboot=fail\nexit-code=7\n");
        assertEquals("new-broken", m.role());
        assertTrue(m.failBeforeReady());
        assertEquals(7, m.exitCode());
    }

    @Test
    void anOkOrEmptyMarkerBootsNormally() throws IOException {
        assertFalse(UpdaterFixtureProbe.parse("role=original\nboot=ok\n").failBeforeReady());
        final UpdaterFixtureProbe.Marker empty = UpdaterFixtureProbe.parse("");
        assertFalse(empty.failBeforeReady());
        assertEquals("unmarked", empty.role());
    }

    @Test
    void aFailMarkerNeverExitsWithZeroOrAnUnparseableCode() throws IOException {
        assertEquals(UpdaterFixtureProbe.DEFAULT_EXIT_CODE, UpdaterFixtureProbe.parse("boot=fail\nexit-code=0\n").exitCode());
        assertEquals(UpdaterFixtureProbe.DEFAULT_EXIT_CODE, UpdaterFixtureProbe.parse("boot=fail\nexit-code=banana\n").exitCode());
        assertEquals(UpdaterFixtureProbe.DEFAULT_EXIT_CODE, UpdaterFixtureProbe.parse("boot=fail\nexit-code=999\n").exitCode());
        assertEquals(UpdaterFixtureProbe.DEFAULT_EXIT_CODE, UpdaterFixtureProbe.parse("boot=fail\n").exitCode());
    }

    @Test
    void theMarkerIsReadFromAStreamAndAMissingEntryIsEmpty() throws IOException {
        final Path marked = this.jar("marked.jar", "role=new-broken\nboot=fail\nexit-code=3\n".getBytes(StandardCharsets.UTF_8));
        try (ZipFile zip = new ZipFile(marked.toFile())) {
            assertTrue(UpdaterFixtureProbe.read(zip.getInputStream(zip.getEntry(UpdaterFixtureProbe.MARKER))).orElseThrow().failBeforeReady());
        }
        assertTrue(UpdaterFixtureProbe.read(null).isEmpty());
        assertTrue(UpdaterFixtureProbe.readRunning().isEmpty(), "the unit-test class path has no marker entry");
    }

    @Test
    void anOversizeMarkerIsRefused() {
        final byte[] big = new byte[UpdaterFixtureProbe.MAX_MARKER_BYTES + 1];
        assertThrows(IOException.class, () -> UpdaterFixtureProbe.read(new ByteArrayInputStream(big)));
        assertDoesNotThrow(() -> UpdaterFixtureProbe.read(new ByteArrayInputStream(new byte[UpdaterFixtureProbe.MAX_MARKER_BYTES])));
    }

    @Test
    void theProbeIsOffUnlessItsPropertyIsSet() {
        assertFalse(UpdaterFixtureProbe.enabled());
    }

    @Test
    void sha256MatchesTheKnownDigestOfAFile() throws Exception {
        final Path file = Files.writeString(this.dir.resolve("abc.bin"), "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdaterFixtureProbe.sha256(file));
    }
}
