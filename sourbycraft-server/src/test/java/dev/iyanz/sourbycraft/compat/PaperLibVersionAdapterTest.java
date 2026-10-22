package dev.iyanz.sourbycraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.plugin.entrypoint.classloader.PaperClassloaderBytecodeModifier;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * PaperLib (relocated into EssentialsX and other plugins) parses {@code Bukkit.getVersion()} with
 * {@code \(MC: (\d)\.(\d+)...\)}, which cannot match a 26.x version, so it keeps its synchronous
 * fallbacks: {@code World#getChunkAt} on the calling thread for every "async" chunk load, which a
 * region thread may not do for a chunk it does not own (live: every EssentialsX teleport to an
 * unowned chunk failed with "Async chunk retrieval"). The adapter makes PaperLib's own parse see
 * the 1.x line SourbyCraft's API is a superset of, so it selects World#getChunkAtAsync and
 * Entity#teleportAsync, which are region-safe.
 */
class PaperLibVersionAdapterTest {

    private static final Pattern PAPERLIB_PATTERN = Pattern.compile(PaperLibVersionAdapter.PAPERLIB_VERSION_REGEX);

    private static final String ENVIRONMENT = "dev.iyanz.sourbycraft.compat.fixture.paperlib.environments.Environment";
    private static final String OTHER = "dev.iyanz.sourbycraft.compat.fixture.other.VersionReader";

    @Test
    void rootCausePaperLibCannotParseA26Version() {
        // CraftServer#getVersion is "<serverVersion> (MC: <getServerVersion()>)" on Paper and SourbyCraft alike.
        assertFalse(PAPERLIB_PATTERN.matcher("26.2-DEV-5d3b72e0 (MC: 26.2)").find());
        assertFalse(PAPERLIB_PATTERN.matcher("26.2.1-DEV (MC: 26.2.1)").find());
    }

    @Test
    void theLegacyLineSelectsPaperLibsAsyncPaths() {
        final Matcher matcher = PAPERLIB_PATTERN.matcher(PaperLibVersionAdapter.PAPERLIB_LEGACY_VERSION);
        assertTrue(matcher.find());
        assertEquals("21", matcher.group(2)); // PaperLib's "minecraftVersion" is the 1.x minor; its lazy patch group reads "1"
    }

    @Test
    void environmentConstructorNoLongerReadsBukkitVersionAndParsesTheLegacyLine() throws Exception {
        final byte[] original = classBytes(ENVIRONMENT);
        final byte[] adapted = PaperLibVersionAdapter.adapt("Essentials", original);
        assertNotSame(original, adapted);

        final Object environment = define(ENVIRONMENT, adapted).getConstructor().newInstance();
        assertEquals(21, environment.getClass().getMethod("getMinecraftVersion").invoke(environment));
        // PaperEnvironment: isVersion(13, 1) -> AsyncChunksPaper_13 + AsyncTeleportPaper_13.
        assertEquals(true, environment.getClass().getMethod("isVersion", int.class, int.class).invoke(environment, 13, 1));
    }

    @Test
    void unadaptedEnvironmentStillCallsBukkitGetVersion() throws Exception {
        // Control: without the adapter the constructor reaches Bukkit.getVersion(). With no server set
        // that throws; with a test server set (other suites install one) it parses a 26.x/test version to 0.
        final Object environment;
        try {
            environment = define(ENVIRONMENT, classBytes(ENVIRONMENT)).getConstructor().newInstance();
        } catch (final InvocationTargetException noServer) {
            assertTrue(noServer.getCause() instanceof NullPointerException, String.valueOf(noServer.getCause()));
            return;
        }
        assertEquals(false, environment.getClass().getMethod("isVersion", int.class, int.class).invoke(environment, 13, 1));
    }

    @Test
    void otherClassesAreReturnedUnchanged() throws Exception {
        final byte[] other = classBytes(OTHER);
        assertSame(other, PaperLibVersionAdapter.adapt("SomePlugin", other));
        final byte[] self = classBytes(PaperLibVersionAdapterTest.class.getName());
        assertSame(self, PaperLibVersionAdapter.adapt("SomePlugin", self));
    }

    @Test
    void malformedBytesWithTheMarkerAreReturnedUnchanged() {
        final byte[] junk = ("xx" + PaperLibVersionAdapter.PAPERLIB_VERSION_REGEX + "xx").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertSame(junk, PaperLibVersionAdapter.adapt("Broken", junk));
    }

    @Test
    void killSwitchKeepsTheOriginalBytes() throws Exception {
        final byte[] original = classBytes(ENVIRONMENT);
        assertSame(original, PaperLibVersionAdapter.adapt("Essentials", original, false));
    }

    @Test
    void paperPluginClassLoaderPathIsAdaptedToo() throws Exception {
        final byte[] original = classBytes(ENVIRONMENT);
        final byte[] adapted = new PaperClassloaderBytecodeModifier().modify(null, original);
        assertNotSame(original, adapted);
        final Object environment = define(ENVIRONMENT, adapted).getConstructor().newInstance();
        assertEquals(21, environment.getClass().getMethod("getMinecraftVersion").invoke(environment));
    }

    private static byte[] classBytes(final String name) throws IOException {
        try (InputStream in = PaperLibVersionAdapterTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    private static Class<?> define(final String name, final byte[] bytes) {
        return new ClassLoader(PaperLibVersionAdapterTest.class.getClassLoader()) {
            Class<?> load() {
                return this.defineClass(name, bytes, 0, bytes.length);
            }
        }.load();
    }
}
