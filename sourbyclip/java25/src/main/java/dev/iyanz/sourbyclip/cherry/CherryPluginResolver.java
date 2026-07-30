package dev.iyanz.sourbyclip.cherry;

import dev.iyanz.sourbyclip.cherry.at.CherryAccessTransformers;
import dev.iyanz.sourbyclip.cherry.discovery.CherryDiscovery;
import dev.iyanz.sourbyclip.cherry.discovery.DiscoveredAccessTransformer;
import dev.iyanz.sourbyclip.cherry.discovery.DiscoveryReport;
import dev.iyanz.sourbyclip.cherry.discovery.SkippedItem;
import org.leavesmc.leavesclip.logger.Logger;
import org.leavesmc.leavesclip.logger.SimpleLogger;
import org.leavesmc.leavesclip.mixin.PluginResolver;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>As of Cherry's multi-format discovery pipeline, the actual manifest scanning (including
 * de-duplication and the per-plugin {@code -Dcherry.disable.plugins} kill-switch) is delegated to
 * {@link CherryDiscovery}; this class's own job is narrowed to committing the AT files a
 * {@link DiscoveryReport} found into {@link CherryAccessTransformers} and locking the registry. See
 * {@link Cherry#status()}/{@link Cherry#dryRun()} for a read-only view of the same discovery that
 * never registers or locks anything.
 */
public final class CherryPluginResolver {

    private static final Logger LOGGER = new SimpleLogger("Cherry");

    private CherryPluginResolver() {
    }

    /** Scan {@code plugins/} for AT declarations, register + lock them. Safe to call with none. */
    public static void resolveAccessTransformers() {
        DiscoveryReport report = CherryDiscovery.scan(new File(PluginResolver.PLUGIN_DIRECTORY));
        commitAccessTransformers(report);
    }

    /**
     * Register every access-transformer file a discovery pass found into
     * {@link CherryAccessTransformers}, then lock the registry. Each file is read and registered
     * independently: a jar that cannot be re-opened, or an AT file whose content cannot be read,
     * is logged and skipped without affecting any other plugin's access-transformers.
     *
     * <p>Logging here is scoped to access-transformers only (both per-file DEBUG detail and any
     * {@code "access-transformer ..."}-prefixed skip WARNs) — it deliberately does not re-log the
     * rest of {@code report}'s skipped items, since a caller that also drives the Fabric mixin
     * bridge (see {@link Cherry#init(java.io.File, java.io.File)}) logs those itself; this avoids
     * every skip reason being logged twice when both engines run against the same report.
     *
     * @param report a discovery report, typically from {@code CherryDiscovery.scan(pluginsDir)}
     */
    public static void commitAccessTransformers(DiscoveryReport report) {
        for (DiscoveredAccessTransformer at : report.accessTransformers()) {
            try (JarFile jarFile = new JarFile(at.jarFile())) {
                JarEntry entry = jarFile.getJarEntry(at.entryName());
                if (entry == null) {
                    // Discovery already verified this existed; a concurrent modification of the jar
                    // between scan and commit is exceedingly unlikely, but still handled, not fatal.
                    LOGGER.warn("Cherry: access-transformer '{}' declared by '{}' vanished before it could be read",
                        at.entryName(), at.pluginName());
                    continue;
                }
                try (InputStream in = jarFile.getInputStream(entry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    LOGGER.debug("Cherry: loading access-transformer {}:{}", at.pluginName(), at.entryName());
                    CherryAccessTransformers.INSTANCE.register(at.pluginName() + ":" + at.entryName(), reader);
                }
            } catch (Throwable t) {
                LOGGER.error("Cherry: failed loading access-transformer '" + at.entryName() + "' from '" + at.pluginName() + "'", t);
            }
        }
        CherryAccessTransformers.INSTANCE.lock();
        for (SkippedItem item : report.skipped()) {
            if (item.item().startsWith("access-transformer")) {
                LOGGER.warn("Cherry: skipped {}", item.describe());
            }
        }
    }
}
