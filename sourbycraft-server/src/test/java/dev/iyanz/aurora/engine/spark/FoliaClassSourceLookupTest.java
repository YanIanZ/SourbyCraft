package dev.iyanz.aurora.engine.spark;

import dev.iyanz.aurora.engine.spark.provider.FoliaClassSourceLookup;
import java.lang.reflect.Field;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine spark: {@link FoliaClassSourceLookup} names the plugin a class came from by reading a
 * private field of the Bukkit and Paper plugin class loaders. Constructing it at all proves both
 * classes and both field names still exist in this build (its static initialiser throws otherwise),
 * which is the thing most likely to break on a Paper update.
 */
class FoliaClassSourceLookupTest {

    private static ClassLoader loaderHolding(final String className, final String fieldName, final String pluginName) throws Exception {
        final Class<?> loaderClass = Class.forName(className);
        final ClassLoader loader = (ClassLoader) mock(loaderClass);
        final JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn(pluginName);
        final Field field = loaderClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(loader, plugin);
        return loader;
    }

    @Test
    void theReflectiveTargetsStillExist() {
        new FoliaClassSourceLookup();   // would throw ExceptionInInitializerError if a class or field moved
    }

    @Test
    void aBukkitPluginClassLoaderIsIdentifiedByItsPluginsName() throws Exception {
        final ClassLoader loader = loaderHolding("org.bukkit.plugin.java.PluginClassLoader", "plugin", "Essentials");
        assertEquals("Essentials", new FoliaClassSourceLookup().identify(loader));
    }

    @Test
    void aPaperPluginClassLoaderIsIdentifiedByItsLoadedPluginsName() throws Exception {
        final ClassLoader loader = loaderHolding("io.papermc.paper.plugin.entrypoint.classloader.PaperPluginClassLoader", "loadedJavaPlugin", "Vault");
        assertEquals("Vault", new FoliaClassSourceLookup().identify(loader));
    }

    @Test
    void anyOtherLoaderIsNotAPluginSource() throws Exception {
        final FoliaClassSourceLookup lookup = new FoliaClassSourceLookup();
        assertNull(lookup.identify(ClassLoader.getSystemClassLoader()));
        assertNull(lookup.identify(FoliaClassSourceLookupTest.class.getClassLoader()));
    }
}
