package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.iyanz.sourbycraft.execution.region.RegionAnchor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * Runs {@link SuperiorSpawnTaskOwner} against the classes of a real SuperiorSkyblock2 jar instead of the
 * test stand-in. Skipped unless {@code SOURBY_SSB2_JAR} names the unported upstream 2026.3 jar (and, for
 * the ported-shape case, {@code SOURBY_SSB2_FOLIA_JAR} the Folia-ported one): neither jar is redistributed here.
 *
 * <p>The plugin constructor is not run (it needs a live plugin). The island is allocated without a
 * constructor and its two final fields that {@code getCenter(null)} reads are set; the callback is linked
 * with the exact arguments of the jar's bootstrap method #2 (LambdaMetafactory, {@code lambda$new$0}),
 * which is what the JVM does when the constructor's invokedynamic runs. So the hidden class shape, nest
 * host, classloader and class-resource hashes are the real ones; the scheduler path is not exercised.
 */
class SuperiorSpawnRealJarTest {

    private static final String ISLAND = "com.bgsoftware.superiorskyblock.island.SpawnIsland";
    private static final String POSITION = "com.bgsoftware.superiorskyblock.core.SWorldPosition";

    /** Defined inside the plugin loader so its own lookup is full-privilege in the plugin's module. */
    public static final class InPluginLookup {
        public static MethodHandles.Lookup lookup() {
            return MethodHandles.lookup();
        }
    }

    /** Child-first for the plugin's packages, like a plugin classloader; the test stand-in must not win. */
    private static final class PluginLoader extends URLClassLoader {
        PluginLoader(final Path jar) throws IOException {
            super(new URL[] {jar.toUri().toURL()}, SuperiorSpawnRealJarTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null && name.equals(InPluginLookup.class.getName())) {
                    final byte[] bytes = testClassBytes(InPluginLookup.class);
                    type = defineClass(name, bytes, 0, bytes.length);
                } else if (type == null && name.startsWith("com.bgsoftware.")) {
                    type = findClass(name);
                }
                if (type == null) return super.loadClass(name, resolve);
                if (resolve) resolveClass(type);
                return type;
            }
        }

        @Override
        public URL getResource(final String name) {
            if (name.startsWith("com/bgsoftware/")) return findResource(name);
            return super.getResource(name);
        }
    }

    private static byte[] testClassBytes(final Class<?> type) {
        try (InputStream in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static Path jar(final String variable) {
        final String value = System.getenv(variable);
        assumeTrue(value != null && Files.isRegularFile(Path.of(value)), variable + " not set to a SuperiorSkyblock2 jar");
        return Path.of(value);
    }

    private record Loaded(PluginLoader loader, Class<?> island, Object instance, Runnable callback) {}

    private static Loaded load(final Path jar, final World world, final double x, final double z) throws Throwable {
        final PluginLoader loader = new PluginLoader(jar);
        final Class<?> island = Class.forName(ISLAND, true, loader);
        final Class<?> position = Class.forName(POSITION, true, loader);
        final Object center = position.getMethod("of", double.class, double.class, double.class).invoke(null, x, 100.0, z);
        final Object instance = allocate(island);
        set(island, instance, "center", center);
        set(island, instance, "spawnWorld", world);

        final MethodHandles.Lookup inPlugin = (MethodHandles.Lookup)Class.forName(InPluginLookup.class.getName(), true, loader)
            .getMethod("lookup").invoke(null);
        final MethodHandles.Lookup caller = MethodHandles.privateLookupIn(island, inPlugin);
        final MethodType noArgs = MethodType.methodType(void.class);
        final var implementation = caller.findSpecial(island, "lambda$new$0", noArgs, island);
        final Runnable callback = (Runnable)LambdaMetafactory.metafactory(caller, "run",
            MethodType.methodType(Runnable.class, island), noArgs, implementation, noArgs).getTarget().invoke(instance);
        return new Loaded(loader, island, instance, callback);
    }

    private static Object allocate(final Class<?> type) throws ReflectiveOperationException {
        final Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        final Object unsafe = theUnsafe.get(null);
        final Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, type);
    }

    private static void set(final Class<?> owner, final Object target, final String name, final Object value)
        throws ReflectiveOperationException {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** The plugin instance's class must come from the plugin loader, as for a real plugin. */
    private static Plugin plugin(final ClassLoader loader, final String version) {
        final Object meta = Proxy.newProxyInstance(loader,
            new Class<?>[] {io.papermc.paper.plugin.configuration.PluginMeta.class},
            (proxy, method, args) -> method.getName().equals("getVersion") ? version : null);
        return (Plugin)Proxy.newProxyInstance(loader, new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "SuperiorSkyblock2";
                case "getPluginMeta" -> meta;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "SuperiorSkyblock2";
                default -> null;
            });
    }

    private static World world() {
        return (World)Proxy.newProxyInstance(SuperiorSpawnRealJarTest.class.getClassLoader(), new Class<?>[] {World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "SpawnWorld";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    void upstreamJarSpawnCallbackResolvesToTheSpawnChunk() throws Throwable {
        final World world = world();
        final Loaded loaded = load(jar("SOURBY_SSB2_JAR"), world, -17.5, 40.25);
        try (PluginLoader own = loaded.loader()) {
            final Runnable callback = loaded.callback();
            assertSame(loaded.island(), callback.getClass().getNestHost(), "linked like the constructor's indy");
            assertTrue(callback.getClass().isHidden() && callback.getClass().isSynthetic());
            assertNotSame(SuperiorSpawnRealJarTest.class.getClassLoader(), loaded.island().getClassLoader());

            final RegionAnchor anchor = AuroraBridge.targetRegion(plugin(own, "2026.3"), callback);
            assertNotNull(anchor, "the audited 2026.3 bytes are accepted");
            assertSame(world, anchor.world());
            assertEquals(-2, anchor.chunkX(), "floor(-17.5) = -18, >> 4 = -2");
            assertEquals(2, anchor.chunkZ());
            // Resolution read only immutable coordinates; the biome read stays inside the callback.
            final Field biome = loaded.island().getDeclaredField("biome");
            biome.setAccessible(true);
            assertNull(biome.get(loaded.instance()), "resolving the owner never ran the callback");

            // The same audited bytes under another version string, or a plugin from another loader, are refused.
            assertThrows(IllegalArgumentException.class,
                () -> AuroraBridge.targetRegion(plugin(own, "2026.4"), callback));
            assertThrows(IllegalArgumentException.class,
                () -> AuroraBridge.targetRegion(plugin(SuperiorSpawnRealJarTest.class.getClassLoader(), "2026.3"), callback));
        }
    }

    @Test
    void upstreamJarSpawnWithoutWorldIsRefused() throws Throwable {
        final Loaded loaded = load(jar("SOURBY_SSB2_JAR"), null, 0, 0);
        try (PluginLoader own = loaded.loader()) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AuroraBridge.targetRegion(plugin(own, "2026.3"), loaded.callback()));
            assertTrue(refused.getMessage().contains("no world"), refused.getMessage());
        }
    }

    /** SpawnIsland's static settings Runnable captures nothing and keeps normal routing. */
    @Test
    void upstreamJarStaticSettingsCallbackIsNotInspected() throws Throwable {
        final Loaded loaded = load(jar("SOURBY_SSB2_JAR"), world(), 0, 0);
        try (PluginLoader own = loaded.loader()) {
            final MethodHandles.Lookup inPlugin = (MethodHandles.Lookup)Class.forName(InPluginLookup.class.getName(), true, own)
                .getMethod("lookup").invoke(null);
            final MethodHandles.Lookup caller = MethodHandles.privateLookupIn(loaded.island(), inPlugin);
            final MethodType noArgs = MethodType.methodType(void.class);
            final Runnable settings = (Runnable)LambdaMetafactory.metafactory(caller, "run",
                MethodType.methodType(Runnable.class), noArgs,
                caller.findStatic(loaded.island(), "onSettingsUpdate", noArgs), noArgs).getTarget().invoke();
            assertTrue(settings.getClass().getName().startsWith(ISLAND + "$$Lambda"));
            assertNull(AuroraBridge.targetRegion(plugin(own, "2026.3"), settings));
        }
    }

    /**
     * The Folia-ported jar reads the biome through a two-capture Runnable (island + Location) scheduled with
     * {@code BukkitExecutor.sync(Location, Runnable)}; it is not the audited shape, so the adapter leaves it to
     * normal routing (null) instead of reflecting into it. Its plugin.yml declares folia-supported, so the
     * bridge does not admit it in the first place; this only pins that the adapter cannot misfire on it.
     */
    @Test
    void foliaPortedJarCallbackIsNotTheAuditedShape() throws Throwable {
        final PluginLoader loader = new PluginLoader(jar("SOURBY_SSB2_FOLIA_JAR"));
        try (loader) {
            final Class<?> island = Class.forName(ISLAND, true, loader);
            final Object instance = allocate(island);
            final MethodHandles.Lookup inPlugin = (MethodHandles.Lookup)Class.forName(InPluginLookup.class.getName(), true, loader)
                .getMethod("lookup").invoke(null);
            final MethodHandles.Lookup caller = MethodHandles.privateLookupIn(island, inPlugin);
            final MethodType noArgs = MethodType.methodType(void.class);
            final Runnable callback = (Runnable)LambdaMetafactory.metafactory(caller, "run",
                MethodType.methodType(Runnable.class, island, org.bukkit.Location.class), noArgs,
                caller.findSpecial(island, "lambda$new$0", MethodType.methodType(void.class, org.bukkit.Location.class), island),
                noArgs).getTarget().invoke(instance, new org.bukkit.Location(world(), 0, 100, 0));
            assertTrue(callback.getClass().getName().startsWith(ISLAND + "$$Lambda"));
            assertNull(AuroraBridge.targetRegion(plugin(loader, "2026.3"), callback));
        }
    }
}
