package dev.iyanz.sourbycraft.mod;

import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

/**
 * ML1 — SourbyMod native mod loader.
 *
 * <p>Scans {@code mods/*.jar} for a {@code sourbymod.yml} descriptor and loads each valid mod in its
 * own {@link URLClassLoader} (parent = the server classloader, so mods see NMS, the Bukkit/Paper API
 * and every {@code dev.iyanz.sourbycraft} API). The loader is called from SourbyCraft's bootstrap
 * after configuration and before worlds load. It is <b>not</b> a Fabric/Forge loader: those formats
 * use different mappings, mixins and lifecycles and are refused with one WARN per jar.</p>
 *
 * <p>Lifecycle: {@link #bootstrap()} scans and calls {@link SourbyMod#onLoad}. A
 * {@code ServerLoadEvent(STARTUP)} listener calls {@link #enableAll()} once worlds and plugins are
 * ready. {@link #shutdown()} calls {@link SourbyMod#onDisable} in reverse order and closes the mod
 * classloaders. A failure in one mod is logged and does not stop the server or the other mods.</p>
 *
 * <p><b>Security:</b> mods run in-process with full server privileges, the same trust level as a
 * plugin. No sandbox is added (none is possible in-JVM). Descriptor parsing uses SnakeYAML's
 * {@code SafeConstructor}, so a descriptor cannot instantiate arbitrary classes.</p>
 */
public final class ModLoader {

    /** SourbyMod API generation this build supports; a mod declaring a newer {@code api} is skipped. */
    public static final int API_GENERATION = 1;

    private record Loaded(ModDescriptor descriptor, SourbyMod mod) {}

    private static final List<Loaded> LOADED = new ArrayList<>();
    /** Strong references so the GC cannot unload mod classes while the server runs. */
    private static final List<URLClassLoader> CLASSLOADERS = new ArrayList<>();
    private static volatile boolean enabled;
    private static volatile boolean readyListenerRegistered;

    private ModLoader() {}

    /**
     * Production entry point: scan {@code mods/}, call every mod's {@code onLoad}, and register the
     * server-ready listener. Zero mods cost one directory listing.
     */
    public static void bootstrap() {
        bootstrap(Path.of("mods"));
        registerReadyListener();
    }

    /** Test seam: load from another directory without touching the Bukkit event system. */
    static void bootstrap(final Path modsDir) {
        reset();
        try {
            Files.createDirectories(modsDir);
        } catch (final Exception failed) {
            SourbyLogger.warn("mods: could not create " + modsDir + " directory: " + failed.getMessage());
            return;
        }

        final File[] jarFiles = modsDir.toFile().listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            SourbyLogger.info("mods: (0 loaded)");
            return;
        }

        final Set<String> seenIds = new LinkedHashSet<>();
        final List<Entry> toLoad = new ArrayList<>();
        int skipped = 0;

        for (final File jarFile : jarFiles) {
            final String jarName = jarFile.getName();
            final ModDescriptor descriptor;
            try (JarFile jar = new JarFile(jarFile)) {
                final JarEntry entry = jar.getJarEntry("sourbymod.yml");
                if (entry == null) {
                    SourbyLogger.warn("mods/" + jarName + ": no sourbymod.yml — Fabric/Forge mods are not"
                        + " supported; SourbyMod format: docs/SOURBYMODS.md. Ignored.");
                    skipped++;
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    descriptor = ModDescriptor.parse(in, jarName);
                }
            } catch (final Exception failed) {
                SourbyLogger.warn("mods/" + jarName + ": failed to open jar: " + failed.getMessage());
                skipped++;
                continue;
            }

            if (descriptor == null) {
                skipped++;
                continue;
            }
            if (descriptor.api > API_GENERATION) {
                SourbyLogger.warn("mods/" + jarName + " (" + descriptor.id + "): requires api="
                    + descriptor.api + " but this loader supports up to api=" + API_GENERATION + " — skipped");
                skipped++;
                continue;
            }
            if (!seenIds.add(descriptor.id)) {
                SourbyLogger.warn("mods/" + jarName + ": duplicate mod id '" + descriptor.id
                    + "' (already registered by another jar) — skipped");
                skipped++;
                continue;
            }
            toLoad.add(new Entry(jarFile, descriptor));
        }

        final StringBuilder summary = new StringBuilder("mods:");
        ClassLoader parent = ModLoader.class.getClassLoader();
        int loaded = 0;

        for (final Entry entry : toLoad) {
            final ModDescriptor descriptor = entry.descriptor();
            final File jarFile = entry.file();
            URLClassLoader loader = null;
            try {
                loader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, parent);
                final Class<?> mainClass = Class.forName(descriptor.main, true, loader);
                if (!SourbyMod.class.isAssignableFrom(mainClass)) {
                    SourbyLogger.warn("mods/" + jarFile.getName() + ": main class " + descriptor.main
                        + " does not implement SourbyMod — skipped");
                    skipped++;
                    closeQuietly(loader);
                    continue;
                }

                final SourbyMod mod = (SourbyMod) mainClass.getDeclaredConstructor().newInstance();
                final ModContext context = new ModContext(descriptor, modsDir);
                try {
                    mod.onLoad(context);
                } catch (final Throwable failed) {
                    SourbyLogger.warn("mod:" + descriptor.id + " onLoad threw "
                        + failed.getClass().getSimpleName() + ": " + failed.getMessage() + " — mod disabled");
                    skipped++;
                    closeQuietly(loader);
                    continue;
                }

                CLASSLOADERS.add(loader);
                LOADED.add(new Loaded(descriptor, mod));
                summary.append(' ').append(descriptor.id).append('@').append(descriptor.version);
                loaded++;
            } catch (final Throwable failed) {
                SourbyLogger.warn("mods/" + jarFile.getName() + " (" + descriptor.id + ") failed to load: "
                    + failed.getClass().getSimpleName() + ": " + failed.getMessage());
                skipped++;
                closeQuietly(loader);
            }
        }

        summary.append(" (").append(loaded).append(" loaded");
        if (skipped > 0) summary.append(", ").append(skipped).append(" skipped");
        summary.append(')');
        SourbyLogger.info(summary.toString());
    }

    /** Called once on {@code ServerLoadEvent(STARTUP)}; idempotent. */
    public static void enableAll() {
        if (enabled) return;
        enabled = true;
        for (final Loaded loaded : LOADED) {
            try {
                loaded.mod().onEnable();
            } catch (final Throwable failed) {
                SourbyLogger.warn("mod:" + loaded.descriptor().id + " onEnable threw "
                    + failed.getClass().getSimpleName() + ": " + failed.getMessage());
            }
        }
    }

    /** Shutdown: {@code onDisable} in reverse order, then close the classloaders. Idempotent. */
    public static void shutdown() {
        if (enabled) {
            for (int i = LOADED.size() - 1; i >= 0; i--) {
                final Loaded loaded = LOADED.get(i);
                try {
                    loaded.mod().onDisable();
                } catch (final Throwable failed) {
                    SourbyLogger.warn("mod:" + loaded.descriptor().id + " onDisable threw "
                        + failed.getClass().getSimpleName() + ": " + failed.getMessage());
                }
            }
        }
        enabled = false;
        for (final URLClassLoader loader : CLASSLOADERS) closeQuietly(loader);
        CLASSLOADERS.clear();
        LOADED.clear();
    }

    /** Mods loaded by {@link #bootstrap()}, for diagnostics. */
    public static List<String> loadedIds() {
        final List<String> ids = new ArrayList<>(LOADED.size());
        for (final Loaded loaded : LOADED) ids.add(loaded.descriptor().id + "@" + loaded.descriptor().version);
        return List.copyOf(ids);
    }

    /** Production listener: enable mods once the server is ready (worlds loaded, plugins enabled). */
    private static void registerReadyListener() {
        if (readyListenerRegistered) return;
        readyListenerRegistered = true;
        try {
            MinecraftInternalPlugin.INSTANCE.getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onServerLoad(final ServerLoadEvent event) {
                    if (event.getType() == ServerLoadEvent.LoadType.STARTUP) enableAll();
                }
            }, MinecraftInternalPlugin.INSTANCE);
        } catch (final RuntimeException failed) {
            // No Bukkit server (unit test or very early failure): onEnable will not fire.
            SourbyLogger.warn("mods: could not register the server-ready listener: " + failed.getMessage());
        }
    }

    private static void reset() {
        for (final URLClassLoader loader : CLASSLOADERS) closeQuietly(loader);
        CLASSLOADERS.clear();
        LOADED.clear();
        enabled = false;
    }

    private static void closeQuietly(final URLClassLoader loader) {
        if (loader == null) return;
        try {
            loader.close();
        } catch (final Exception ignored) {
            // A failed close must not hide the original failure.
        }
    }

    private record Entry(File file, ModDescriptor descriptor) {}
}
