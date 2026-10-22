package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The backend SPI: selection, refusal without fallback, and a non-FILE backend end to end. */
class AwfBackendTest {

    @TempDir Path dir;

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A stand-in for a database backend: stores under its own root, and records calls. */
    private static final class RecordingBackend implements AwfBackend {
        final AwfBackend.FileBackend delegate;
        final List<String> calls = new ArrayList<>();

        RecordingBackend(final Path root) {
            this.delegate = new AwfBackend.FileBackend(root);
        }

        @Override public String name() { return "memoryish"; }

        @Override public boolean exists(final String id) {
            this.calls.add("exists " + id);
            return this.delegate.exists(id);
        }

        @Override public AwfStore open(final String id, final WorldRole role, final int retained) throws IOException {
            this.calls.add("open " + id + " " + role);
            return this.delegate.open(id, role, retained);
        }

        @Override public String retire(final String id) throws IOException {
            this.calls.add("retire " + id);
            return this.delegate.retire(id);
        }
    }

    private AwfSettings settings(final String backend, final Set<String> worlds, final Set<String> export) {
        return new AwfSettings(worlds, export, backend, PersistenceMode.INCREMENTAL, 30, 64, 2, 2);
    }

    private Path region() throws IOException {
        final Path folder = this.dir.resolve("world").resolve("region");
        Files.createDirectories(folder);
        return folder;
    }

    @Test
    void fileIsBuiltInAndSelectedByDefault() {
        assertEquals("file", AwfSettings.DEFAULT.backend());
        assertTrue(AwfBackend.named("file") == AwfBackend.FILE);
        assertThrows(IllegalStateException.class, () -> AwfBackend.register(new AwfBackend.FileBackend(this.dir)),
            "a second backend named file is refused");
    }

    @Test
    void aListedWorldOnAnUnregisteredBackendFailsToLoad() throws Exception {
        final AwfEngine engine = new AwfEngine(settings("mongodb", Set.of("world"), Set.of()), Runnable::run,
            System::nanoTime, name -> null);
        final IOException failure = assertThrows(IOException.class, () -> engine.open(region()));
        assertTrue(failure.getMessage().contains("not registered"));
    }

    @Test
    void anUnlistedWorldOnAnUnregisteredBackendUsesRegionFiles() throws Exception {
        final AwfEngine engine = new AwfEngine(settings("mongodb", Set.of(), Set.of()), Runnable::run,
            System::nanoTime, name -> null);
        assertNull(engine.open(region()));
    }

    @Test
    void anotherBackendCarriesStoreExportAndRetire() throws Exception {
        final RecordingBackend backend = new RecordingBackend(this.dir.resolve("elsewhere"));
        final AwfEngine storing = new AwfEngine(settings("memoryish", Set.of("world"), Set.of()), Runnable::run,
            System::nanoTime, name -> name.equals("memoryish") ? backend : null);
        final AwfRegionStorage storage = storing.open(region());
        storage.write(0, 0, b("remote"));
        storage.close();
        assertTrue(Files.notExists(AwfRegionStorage.storeFor(region())), "nothing went to the FILE location");
        assertTrue(backend.calls.stream().anyMatch(c -> c.startsWith("open ") && c.endsWith("VANILLA")));

        final List<Map.Entry<ChunkKey, byte[]>> exported = new ArrayList<>();
        final AwfEngine exporting = new AwfEngine(settings("memoryish", Set.of(), Set.of("world")), Runnable::run,
            System::nanoTime, name -> name.equals("memoryish") ? backend : null);
        assertNull(exporting.open(region(), new AwfEngine.RegionSink() {
            @Override public void write(final int x, final int z, final byte[] nbt) {
                exported.add(Map.entry(new ChunkKey(x, z), nbt));
            }

            @Override public void flush() {}
        }));
        assertEquals(1, exported.size());
        assertArrayEquals(b("remote"), exported.get(0).getValue());
        assertTrue(backend.calls.stream().anyMatch(c -> c.startsWith("open ") && c.endsWith("READ_ONLY")),
            "an export opens read-only");
        assertTrue(backend.calls.stream().anyMatch(c -> c.startsWith("retire ")));
    }

    @Test
    void storageIdsAreRelativeInsideTheServerDirectory() {
        assertEquals("world/region", AwfEngine.storageId(Path.of("world", "region")));
        assertTrue(AwfEngine.storageId(this.dir.resolve("x")).startsWith("/") || AwfEngine.storageId(this.dir.resolve("x")).contains(":"),
            "outside the server directory the id is absolute");
    }
}
