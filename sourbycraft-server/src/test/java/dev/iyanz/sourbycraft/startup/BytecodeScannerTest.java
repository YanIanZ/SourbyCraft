package dev.iyanz.sourbycraft.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Constant-pool reading, and the compatibility flags derived from it. */
class BytecodeScannerTest {

    @TempDir Path dir;

    @Test
    void readsTheClassesAClassReferences() throws IOException {
        final Set<String> refs = BytecodeScanner.referencedClasses(new ByteArrayInputStream(
            ClassFiles.classReferencing("a/Main", "org/bukkit/scheduler/BukkitScheduler", "[Lorg/bukkit/World;")));
        assertTrue(refs.contains("a/Main"));
        assertTrue(refs.contains("java/lang/Object"));
        assertTrue(refs.contains("org/bukkit/scheduler/BukkitScheduler"));
        assertTrue(refs.contains("org/bukkit/World"), "array descriptors name their element");
    }

    @Test
    void aRealJdkClassParses() throws IOException {
        try (InputStream in = String.class.getResourceAsStream("String.class")) {
            if (in == null) return; // not every runtime exposes jrt bytes this way
            assertTrue(BytecodeScanner.referencedClasses(in).contains("java/lang/Object"));
        }
    }

    @Test
    void garbageIsAnIoFailure() {
        assertThrows(IOException.class, () -> BytecodeScanner.referencedClasses(new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5})));
        assertThrows(IOException.class, () -> BytecodeScanner.referencedClasses(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void aJarIsClassifiedByWhatItReferences() throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.yml", "name: X\n".getBytes());
        entries.put("a/Main.class", ClassFiles.classReferencing("a/Main", "org/bukkit/scheduler/BukkitRunnable"));
        entries.put("a/util/Nms.class", ClassFiles.classReferencing("a/util/Nms", "net/minecraft/server/level/ServerLevel"));
        entries.put("a/util/Broken.class", new byte[] {0, 1, 2});
        final Path jar = this.dir.resolve("x.jar");
        ClassFiles.jar(jar, entries);

        final CompatibilityScan scan = CompatibilityScan.of(jar);
        assertEquals(3, scan.classCount());
        assertEquals(2, scan.packageCount());
        assertEquals(1, scan.unreadableClasses());
        assertTrue(scan.bukkitScheduler());
        assertTrue(scan.serverInternals());
        assertFalse(scan.regionSchedulers());
        assertEquals("internals", scan.verdict(false));
        assertEquals("declared", scan.verdict(true));
        assertEquals(scan, CompatibilityScan.decode(scan.encode()));
    }
}
