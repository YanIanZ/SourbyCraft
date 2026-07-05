package dev.iyanz.sourbycraft.mod;

import dev.iyanz.sourbycraft.core.ModuleRegistry;
import dev.iyanz.sourbycraft.core.SourbyModule;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.plugin.Plugin;

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

/**
 * ML1 — SourbyMod native mod loader.
 *
 * <p>Scans {@code mods/*.jar} for a {@code sourbymod.yml} descriptor and loads each
 * valid SourbyMod into its own {@link URLClassLoader} (parent = server classloader,
 * giving mods full NMS + Bukkit + dev.iyanz API visibility). Called once from
 * {@code DedicatedServer.initServer} after SourbyCraftSecurityConfig/SourbyCraftConfig init.
 *
 * <p>Mods are automatically enrolled into the MT1 {@link ModuleRegistry} as persistent
 * modules ({@code mod:<id>}) so their {@link SourbyMod#onEnable}/{@link SourbyMod#onDisable}
 * ride the existing lifecycle. Persistent modules survive {@link ModuleRegistry#clear()}
 * (which guards against double-enroll on same-classloader plugin reloads).
 *
 * <p>Security: mods run in-process with full server privileges — same trust level as plugins.
 * The loader adds no sandbox (none is possible in-JVM). Descriptor parsing uses
 * {@code SafeConstructor} (no YAML gadget RCE). Jars are loaded only when the operator
 * placed them in {@code mods/}.
 */
public final class ModLoader {

    /** SourbyMod API generation that this build of the loader understands. Reject {@code api > API_GENERATION}. */
    public static final int API_GENERATION = 1;

    /** Strong references to mod classloaders so GC cannot unload mod classes at runtime. */
    private static final List<URLClassLoader> MOD_CLASSLOADERS = new ArrayList<>();

    private ModLoader() {}

    /**
     * Entry point — scan {@code mods/}, load valid SourbyMods, emit boot summary.
     * Called from {@code DedicatedServer.initServer} after config init.
     * Zero mods → zero cost beyond one directory listing.
     */
    public static void bootstrap() {
        Path modsDir = Path.of("mods");
        try {
            Files.createDirectories(modsDir);
        } catch (Exception e) {
            SourbyLogger.warn("[SourbyCraft] mods: could not create mods/ directory: " + e.getMessage());
        }

        File[] jarFiles = modsDir.toFile().listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            SourbyLogger.info("[SourbyCraft] mods: (0 loaded)");
            return;
        }

        // First pass: parse + validate descriptors, resolve duplicates
        record Entry(File file, ModDescriptor desc) {}
        List<Entry> toLoad = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int skipped = 0;

        for (File jarFile : jarFiles) {
            String jarName = jarFile.getName();
            ModDescriptor descriptor;

            try (JarFile jar = new JarFile(jarFile)) {
                JarEntry entry = jar.getJarEntry("sourbymod.yml");
                if (entry == null) {
                    // Non-SourbyMod jar — honesty WARN explaining why it is ignored
                    SourbyLogger.warn("[SourbyCraft] mods/" + jarName
                        + ": no sourbymod.yml — Fabric/Forge mods are not supported;"
                        + " SourbyMod format: docs/SOURBYMODS.md");
                    skipped++;
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    descriptor = ModDescriptor.parse(in, jarName);
                }
            } catch (Exception e) {
                SourbyLogger.warn("[SourbyCraft] mods/" + jarName + ": failed to open jar: " + e.getMessage());
                skipped++;
                continue;
            }

            if (descriptor == null) {
                // parse already logged the reason
                skipped++;
                continue;
            }

            if (descriptor.api > API_GENERATION) {
                SourbyLogger.warn("[SourbyCraft] mods/" + jarName + " (" + descriptor.id
                    + "): requires api=" + descriptor.api
                    + " but this loader supports up to api=" + API_GENERATION + " — skipped");
                skipped++;
                continue;
            }

            if (seenIds.contains(descriptor.id)) {
                SourbyLogger.warn("[SourbyCraft] mods/" + jarName
                    + ": duplicate mod id '" + descriptor.id + "' (already registered by another jar) — skipped");
                skipped++;
                continue;
            }

            seenIds.add(descriptor.id);
            toLoad.add(new Entry(jarFile, descriptor));
        }

        // Second pass: instantiate, call onLoad, enroll into ModuleRegistry
        int loaded = 0;
        StringBuilder sb = new StringBuilder("[SourbyCraft] mods:");
        ClassLoader parent = ModLoader.class.getClassLoader();

        for (Entry e : toLoad) {
            ModDescriptor desc = e.desc();
            File jarFile = e.file();
            URLClassLoader cl = null;

            try {
                cl = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, parent);

                Class<?> mainClass = cl.loadClass(desc.main);
                if (!SourbyMod.class.isAssignableFrom(mainClass)) {
                    SourbyLogger.warn("[SourbyCraft] mods/" + jarFile.getName()
                        + ": main class " + desc.main + " does not implement SourbyMod — skipped");
                    skipped++;
                    cl.close();
                    continue;
                }

                SourbyMod mod = (SourbyMod) mainClass.getDeclaredConstructor().newInstance();
                ModContext ctx = new ModContext(desc.id, desc.version);

                // onLoad — isolated; a broken mod never kills the server
                try {
                    mod.onLoad(ctx);
                } catch (Throwable t) {
                    SourbyLogger.warn("[SourbyCraft] mod:" + desc.id
                        + " onLoad threw " + t.getClass().getSimpleName() + ": " + t.getMessage() + " — mod disabled");
                    skipped++;
                    cl.close();
                    continue;
                }

                // Persist classloader reference so GC cannot unload mod classes
                MOD_CLASSLOADERS.add(cl);

                // Enroll as persistent module — survives ModuleRegistry.clear() so onEnable/onDisable
                // are called by SWPlugin's existing enableAll/disableAll without any SWPlugin changes
                final SourbyMod finalMod = mod;
                final String modId = desc.id;
                ModuleRegistry.addPersistent(new SourbyModule() {
                    @Override public String name() { return "mod:" + modId; }
                    @Override public void enable(Plugin plugin) { finalMod.onEnable(); }
                    @Override public void disable() { finalMod.onDisable(); }
                });

                sb.append(' ').append(desc.id).append('@').append(desc.version);
                loaded++;

            } catch (Throwable t) {
                SourbyLogger.warn("[SourbyCraft] mods/" + jarFile.getName()
                    + " (" + desc.id + "): failed to load: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                skipped++;
                if (cl != null) {
                    try { cl.close(); } catch (Exception ignored) {}
                }
            }
        }

        if (loaded == 0 && skipped == 0) {
            // All were non-SourbyMod jars; individual WARNs already emitted above
            sb.append(" (0 loaded)");
        } else {
            sb.append(" (").append(loaded).append(" loaded");
            if (skipped > 0) sb.append(", ").append(skipped).append(" skipped");
            sb.append(')');
        }
        SourbyLogger.info(sb.toString());
    }
}
