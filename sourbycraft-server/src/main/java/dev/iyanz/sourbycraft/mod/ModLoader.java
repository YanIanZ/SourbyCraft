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
 * <p><b>Lifecycle sequencing:</b> {@link #bootstrap} only performs scan → load →
 * {@link SourbyMod#onLoad}. It does NOT enroll mods into {@link ModuleRegistry} directly,
 * because {@code SWPlugin.onEnable} calls {@link ModuleRegistry#clear()} at the top as a
 * same-classloader reload guard — a direct enroll at bootstrap would be wiped before
 * {@code enableAll} runs. Instead, {@link #enrollInto()} is called from
 * {@code SWPlugin.onEnable} AFTER all first-party {@code ModuleRegistry.add} calls and
 * BEFORE {@code ModuleRegistry.enableAll}, enrolling each loaded mod as a full
 * {@link SourbyModule} ({@code mod:<id>}) so {@code onEnable}/{@code onDisable} ride
 * the existing lifecycle.
 *
 * <p><b>Security:</b> Mods run in-process with full server privileges — same trust level
 * as plugins. The loader adds no sandbox (none is possible in-JVM). Descriptor parsing
 * uses {@code SafeConstructor} (no YAML gadget RCE). Jars are loaded only when the
 * operator placed them in {@code mods/}.
 */
public final class ModLoader {

    /** SourbyMod API generation that this build supports. Mods declaring {@code api > API_GENERATION} are rejected. */
    public static final int API_GENERATION = 1;

    private record LoadedMod(ModDescriptor desc, SourbyMod instance, ModContext ctx) {}

    /** Mods loaded by bootstrap(), consumed by enrollInto(). */
    private static final List<LoadedMod> LOADED = new ArrayList<>();

    /** Strong references to mod classloaders so GC cannot unload mod classes at runtime. */
    private static final List<URLClassLoader> MOD_CLASSLOADERS = new ArrayList<>();

    private ModLoader() {}

    /**
     * Scan {@code mods/}, load valid SourbyMods, call {@link SourbyMod#onLoad}.
     * Does NOT enroll into {@link ModuleRegistry} — see class-level javadoc.
     * Called once from {@code DedicatedServer.initServer} after config init.
     * Zero mods → zero cost beyond one directory listing.
     */
    public static void bootstrap() {
        LOADED.clear();
        MOD_CLASSLOADERS.clear();

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

        Set<String> seenIds = new LinkedHashSet<>();
        int skipped = 0;

        // First pass: parse + validate descriptors
        record Entry(File file, ModDescriptor desc) {}
        List<Entry> toLoad = new ArrayList<>();

        for (File jarFile : jarFiles) {
            String jarName = jarFile.getName();
            ModDescriptor descriptor;

            try (JarFile jar = new JarFile(jarFile)) {
                JarEntry entry = jar.getJarEntry("sourbymod.yml");
                if (entry == null) {
                    // Non-SourbyMod jar — honesty WARN (one per jar)
                    SourbyLogger.warn("[SourbyCraft] mods/" + jarName
                        + ": no sourbymod.yml — Fabric/Forge mods are not supported;"
                        + " SourbyMod format: docs/SOURBYMODS.md. Ignored.");
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
                // parse() already logged the reason
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

        // Second pass: URLClassLoader + instantiate + onLoad
        int loaded = 0;
        StringBuilder sb = new StringBuilder("[SourbyCraft] mods:");
        ClassLoader parent = ModLoader.class.getClassLoader();

        for (Entry e : toLoad) {
            ModDescriptor desc = e.desc();
            File jarFile = e.file();
            URLClassLoader cl = null;

            try {
                cl = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, parent);

                Class<?> mainClass = Class.forName(desc.main, true, cl);
                if (!SourbyMod.class.isAssignableFrom(mainClass)) {
                    SourbyLogger.warn("[SourbyCraft] mods/" + jarFile.getName()
                        + ": main class " + desc.main + " does not implement SourbyMod — skipped");
                    skipped++;
                    try { cl.close(); } catch (Exception ignored) {}
                    continue;
                }

                SourbyMod mod = (SourbyMod) mainClass.getDeclaredConstructor().newInstance();
                ModContext ctx = new ModContext(desc.id, desc.name, desc.version);

                // onLoad — isolated; a broken mod never kills the server
                try {
                    mod.onLoad(ctx);
                } catch (Throwable t) {
                    SourbyLogger.warn("[SourbyCraft] mod:" + desc.id + " onLoad threw "
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + " — mod disabled");
                    skipped++;
                    try { cl.close(); } catch (Exception ignored) {}
                    continue;
                }

                // Persist classloader reference — GC must not unload mod classes
                MOD_CLASSLOADERS.add(cl);
                LOADED.add(new LoadedMod(desc, mod, ctx));
                sb.append(' ').append(desc.id).append('@').append(desc.version);
                loaded++;

            } catch (Throwable t) {
                SourbyLogger.warn("[SourbyCraft] mods/" + jarFile.getName()
                    + " (" + desc.id + "): failed to load: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                skipped++;
                if (cl != null) try { cl.close(); } catch (Exception ignored) {}
            }
        }

        // Boot summary INFO
        sb.append(" (").append(loaded).append(" loaded");
        if (skipped > 0) sb.append(", ").append(skipped).append(" skipped");
        sb.append(')');
        SourbyLogger.info(sb.toString());
    }

    /**
     * Enroll all loaded mods into {@link ModuleRegistry} as full {@link SourbyModule} instances.
     * Each mod is registered as {@code mod:<id>} with enable→{@link SourbyMod#onEnable} and
     * disable→{@link SourbyMod#onDisable}. Any extra modules buffered via
     * {@link ModContext#registerModule} during onLoad are flushed here too.
     *
     * <p>Called from {@code SWPlugin.onEnable} AFTER all first-party
     * {@code ModuleRegistry.add} calls and BEFORE {@code ModuleRegistry.enableAll}, so mods
     * are enrolled after the reload-guard {@link ModuleRegistry#clear()} that runs at the
     * top of onEnable.
     */
    public static void enrollInto() {
        for (LoadedMod m : LOADED) {
            final SourbyMod mod = m.instance();
            final String id = m.desc().id;
            // Enroll the mod itself as a named SourbyModule covering the full lifecycle
            ModuleRegistry.add(new SourbyModule() {
                @Override public String name() { return "mod:" + id; }
                @Override public void enable(Plugin plugin) { mod.onEnable(); }
                @Override public void disable() { mod.onDisable(); }
            });
            // Flush any additional modules registered via ctx.registerModule() during onLoad
            for (SourbyModule extra : m.ctx().drainPendingModules()) {
                ModuleRegistry.add(extra);
            }
        }
    }
}
