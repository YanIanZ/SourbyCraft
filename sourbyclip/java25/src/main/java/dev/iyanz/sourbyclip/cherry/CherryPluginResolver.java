package dev.iyanz.sourbyclip.cherry;

import com.google.gson.Gson;
import dev.iyanz.sourbyclip.cherry.at.CherryAccessTransformers;
import org.leavesmc.leavesclip.logger.Logger;
import org.leavesmc.leavesclip.logger.SimpleLogger;
import org.leavesmc.leavesclip.mixin.LeavesPluginMeta;
import org.leavesmc.leavesclip.mixin.PluginResolver;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Cherry — discovers access-transformer ({@code .at}) files declared by plugins and feeds them to
 * {@link CherryAccessTransformers}.
 *
 * <p>This is deliberately an INDEPENDENT scan of {@code plugins/*.jar} (not a consumer of the
 * Leaves {@code PluginResolver.leavesPluginMetas} list) for two reasons: (1) a plugin may ship an
 * AT with NO mixin block, and the Leaves pipeline drops mixin-less plugins; (2) keeping Cherry's AT
 * discovery off the proven Leaves mixin path means Cherry cannot destabilise mixin loading. A
 * plugin declares ATs via the unified Cherry manifest (or the legacy Leaves one):
 *
 * <pre>{@code
 * // cherry-plugin.json  (preferred) — or leaves-plugin.json (back-compat)
 * {
 *   "name": "MyPlugin",
 *   "mixin": { "package-name": "com.me.mixin", "mixins": ["mixins.me.json"],
 *              "access-widener": "me.accesswidener" },   // Leaves engine (optional)
 *   "access-transformers": ["me.at"]                      // Cherry engine (optional)
 * }
 * }</pre>
 *
 * The {@code mixin} block continues to be handled by the Leaves engine (Fabric mixins + access
 * wideners); the {@code access-transformers} list is Cherry's Horizon-grafted addition.
 */
public final class CherryPluginResolver {

    private static final Logger LOGGER = new SimpleLogger("Cherry");
    private static final Gson GSON = new Gson();
    private static final String CHERRY_MANIFEST = "cherry-plugin.json";
    private static final String LEAVES_MANIFEST = PluginResolver.LEAVES_PLUGIN_JSON_FILE;

    private CherryPluginResolver() {
    }

    /** Scan {@code plugins/} for AT declarations, register + lock them. Safe to call with none. */
    public static void resolveAccessTransformers() {
        File pluginsDir = new File(PluginResolver.PLUGIN_DIRECTORY);
        File[] jars = pluginsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            CherryAccessTransformers.INSTANCE.lock();
            return;
        }

        for (File jar : jars) {
            try (JarFile jarFile = new JarFile(jar)) {
                LeavesPluginMeta meta = readManifest(jarFile);
                if (meta == null) continue;
                List<String> atFiles = meta.getAccessTransformers();
                if (atFiles == null || atFiles.isEmpty()) continue;

                String pluginName = meta.getName() == null ? jar.getName() : meta.getName();
                for (String atFile : atFiles) {
                    JarEntry entry = jarFile.getJarEntry(atFile);
                    if (entry == null) {
                        LOGGER.warn("Cherry: plugin '{}' declares access-transformer '{}' but it is missing from the jar",
                            pluginName, atFile);
                        continue;
                    }
                    try (InputStream in = jarFile.getInputStream(entry);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        LOGGER.info("Cherry: loading access-transformer {}:{}", pluginName, atFile);
                        CherryAccessTransformers.INSTANCE.register(pluginName + ":" + atFile, reader);
                    }
                }
            } catch (Throwable t) {
                LOGGER.error("Cherry: failed scanning plugin jar for access-transformers: " + jar.getName(), t);
            }
        }

        CherryAccessTransformers.INSTANCE.lock();
    }

    private static LeavesPluginMeta readManifest(JarFile jarFile) {
        LeavesPluginMeta meta = parse(jarFile, CHERRY_MANIFEST);
        if (meta != null) return meta;
        return parse(jarFile, LEAVES_MANIFEST);
    }

    private static LeavesPluginMeta parse(JarFile jarFile, String manifestName) {
        JarEntry entry = jarFile.getJarEntry(manifestName);
        if (entry == null) return null;
        try (InputStream in = jarFile.getInputStream(entry)) {
            return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), LeavesPluginMeta.class);
        } catch (Exception e) {
            LOGGER.warn("Cherry: failed reading {} from {}", manifestName, jarFile.getName());
            return null;
        }
    }
}
