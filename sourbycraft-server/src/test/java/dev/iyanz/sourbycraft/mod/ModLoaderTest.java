package dev.iyanz.sourbycraft.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModLoaderTest {

    @TempDir
    Path mods;

    @AfterEach
    void clear() {
        ModLoader.shutdown();
        TestMod.reset();
        BrokenMod.loaded = 0;
    }

    private Path jar(final String name, final String yaml) throws IOException {
        final Path jar = this.mods.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("sourbymod.yml"));
            jarOut.write(yaml.getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jar;
    }

    private static String descriptor(final String id, final String main, final int api) {
        return "id: " + id + "\nversion: 1.2.3\nmain: " + main + "\napi: " + api + "\n";
    }

    @Test
    void loadsOneModAndRunsTheWholeLifecycle() throws IOException {
        jar("test.jar", descriptor("test", TestMod.class.getName(), 1));

        ModLoader.bootstrap(this.mods);
        assertEquals(1, TestMod.loaded);
        assertEquals(0, TestMod.enabled);
        assertEquals(java.util.List.of("test@1.2.3"), ModLoader.loadedIds());

        ModLoader.enableAll();
        assertEquals(1, TestMod.enabled);

        ModLoader.shutdown();
        assertEquals(1, TestMod.disabled);
        assertTrue(ModLoader.loadedIds().isEmpty());
    }

    @Test
    void rejectsDuplicateIdsAndNewerApis() throws IOException {
        jar("a.jar", descriptor("dup", TestMod.class.getName(), 1));
        jar("b.jar", descriptor("dup", TestMod.class.getName(), 1));
        jar("c.jar", descriptor("future", TestMod.class.getName(), 2));

        ModLoader.bootstrap(this.mods);
        assertEquals(java.util.List.of("dup@1.2.3"), ModLoader.loadedIds());
    }

    @Test
    void aFailingOnLoadDoesNotStopTheOtherModsOrTheServer() throws IOException {
        jar("broken.jar", descriptor("broken", BrokenMod.class.getName(), 1));
        jar("good.jar", descriptor("good", TestMod.class.getName(), 1));

        ModLoader.bootstrap(this.mods);
        assertEquals(1, BrokenMod.loaded);
        assertEquals(1, TestMod.loaded);
        assertEquals(java.util.List.of("good@1.2.3"), ModLoader.loadedIds());
    }

    @Test
    void ignoresJarsWithoutADescriptor() throws IOException {
        try (OutputStream out = Files.newOutputStream(this.mods.resolve("plain.jar"));
             JarOutputStream ignored = new JarOutputStream(out)) {
            ignored.putNextEntry(new JarEntry("readme.txt"));
            ignored.write("not a mod".getBytes(StandardCharsets.UTF_8));
            ignored.closeEntry();
        }
        ModLoader.bootstrap(this.mods);
        assertTrue(ModLoader.loadedIds().isEmpty());
    }

    public static final class TestMod implements SourbyMod {
        static int loaded;
        static int enabled;
        static int disabled;

        public TestMod() {}

        @Override
        public void onLoad(final ModContext ctx) {
            loaded++;
        }

        @Override
        public void onEnable() {
            enabled++;
        }

        @Override
        public void onDisable() {
            disabled++;
        }

        static void reset() {
            loaded = 0;
            enabled = 0;
            disabled = 0;
        }
    }

    public static final class BrokenMod implements SourbyMod {
        static int loaded;

        public BrokenMod() {}

        @Override
        public void onLoad(final ModContext ctx) {
            loaded++;
            throw new IllegalStateException("intentional");
        }
    }
}
