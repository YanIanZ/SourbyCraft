package dev.iyanz.sourbyclip.cherry.discovery;

import com.google.gson.Gson;
import dev.iyanz.sourbyclip.cherry.manifest.FabricMixinEntry;
import dev.iyanz.sourbyclip.cherry.manifest.FabricModManifest;
import dev.iyanz.sourbyclip.cherry.manifest.ManifestSource;
import dev.iyanz.sourbyclip.cherry.manifest.MixinConfigMeta;
import org.leavesmc.leavesclip.logger.Logger;
import org.leavesmc.leavesclip.logger.SimpleLogger;
import org.leavesmc.leavesclip.mixin.LeavesPluginMeta;
import org.leavesmc.leavesclip.mixin.PluginResolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Cherry — the multi-format, read-only plugin manifest scanner. This is the single place that knows
 * how to find a mixin config, access-transformer file, or access-widener file regardless of which of
 * the four manifest shapes declared it:
 *
 * <ol>
 *   <li>{@code cherry-plugin.json} (preferred unified manifest)</li>
 *   <li>{@code leaves-plugin.json} (legacy back-compat manifest; ignored for a jar that already has
 *       a {@code cherry-plugin.json})</li>
 *   <li>{@code fabric.mod.json}'s {@code mixins} array (environment-filtered to server/{@code "*"})
 *       and {@code accessWidener} field</li>
 *   <li>the standalone {@code *.mixins.json} SpongePowered Mixin config files those manifests point
 *       at, opened here to read {@code package}/{@code refmap}/{@code priority}</li>
 * </ol>
 *
 * <p><b>Every failure is isolated and recorded, never thrown.</b> A jar that cannot be opened, a
 * manifest that is not valid JSON, a mixin config that is missing its {@code package} field, a
 * declared file that does not exist in the jar, or a resource name that collides with another
 * plugin's — all of these are caught, logged, and added to {@link DiscoveryReport#skipped()} with a
 * reason. Nothing here aborts the scan of the plugins directory as a whole, or of any other plugin's
 * jar; this is what goal 1's "one broken plugin must not break other plugins" discipline looks like
 * for discovery.
 *
 * <p>This class performs no registration, extraction, or locking — see
 * {@link dev.iyanz.sourbyclip.cherry.CherryPluginResolver} (commits access-transformers) and
 * {@link dev.iyanz.sourbyclip.cherry.fabric.CherryFabricBridge} (extracts and stages Fabric-format
 * mixin configs/wideners) for the two consumers that act on a {@link DiscoveryReport}.
 */
public final class CherryDiscovery {

    private static final Logger LOGGER = new SimpleLogger("Cherry/Discovery");
    private static final Gson GSON = new Gson();
    private static final String CHERRY_MANIFEST = "cherry-plugin.json";
    private static final String LEAVES_MANIFEST = PluginResolver.LEAVES_PLUGIN_JSON_FILE;
    private static final String FABRIC_MANIFEST = "fabric.mod.json";

    private CherryDiscovery() {
    }

    /**
     * Scan every {@code .jar} in {@code pluginsDir} for the manifest formats described above and
     * build a complete, de-duplicated, priority-sorted {@link DiscoveryReport}. Safe to call with a
     * missing or empty directory (returns {@link DiscoveryReport#empty()}), safe to call regardless
     * of {@link dev.iyanz.sourbyclip.cherry.Cherry#enabled()}, and safe to call repeatedly (purely
     * read-only — see the class javadoc).
     *
     * @param pluginsDir the directory to scan, typically {@code new File(PluginResolver.PLUGIN_DIRECTORY)}
     * @return the discovery result; never {@code null}
     */
    public static DiscoveryReport scan(File pluginsDir) {
        File[] jars = pluginsDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return DiscoveryReport.empty();
        }
        // Deterministic order: first-seen-wins de-dup below must not depend on filesystem listing order.
        Arrays.sort(jars, Comparator.comparing(File::getName));

        List<DiscoveredMixinConfig> mixinConfigs = new ArrayList<>();
        List<DiscoveredAccessTransformer> accessTransformers = new ArrayList<>();
        List<DiscoveredAccessWidener> accessWideners = new ArrayList<>();
        List<SkippedItem> skipped = new ArrayList<>();
        Set<String> seenConfigResources = new HashSet<>();
        Set<String> seenWidenerResources = new HashSet<>();

        int scanned = 0;
        for (File jar : jars) {
            scanned++;
            try {
                scanJar(jar, mixinConfigs, accessTransformers, accessWideners, skipped, seenConfigResources, seenWidenerResources);
            } catch (Throwable t) {
                skipped.add(new SkippedItem(jar.getName(), jar.getName(), "plugin jar", "failed to scan: " + describe(t)));
                LOGGER.warn("Cherry: failed scanning plugin jar '{}': {}", jar.getName(), describe(t));
            }
        }

        mixinConfigs.sort(Comparator.comparingInt(DiscoveredMixinConfig::priority)
            .thenComparing(DiscoveredMixinConfig::pluginName)
            .thenComparing(DiscoveredMixinConfig::configEntry));

        return new DiscoveryReport(
            List.copyOf(mixinConfigs),
            List.copyOf(accessTransformers),
            List.copyOf(accessWideners),
            List.copyOf(skipped),
            scanned
        );
    }

    private static void scanJar(
        File jar,
        List<DiscoveredMixinConfig> mixinConfigs,
        List<DiscoveredAccessTransformer> accessTransformers,
        List<DiscoveredAccessWidener> accessWideners,
        List<SkippedItem> skipped,
        Set<String> seenConfigResources,
        Set<String> seenWidenerResources
    ) throws IOException {
        try (JarFile jarFile = new JarFile(jar)) {
            ManifestSource leavesFamilySource = ManifestSource.CHERRY_PLUGIN_JSON;
            LeavesPluginMeta leavesFamilyMeta = parseJson(jarFile, CHERRY_MANIFEST, LeavesPluginMeta.class);
            if (leavesFamilyMeta == null) {
                leavesFamilyMeta = parseJson(jarFile, LEAVES_MANIFEST, LeavesPluginMeta.class);
                leavesFamilySource = ManifestSource.LEAVES_PLUGIN_JSON;
            }
            FabricModManifest fabricMeta = parseJson(jarFile, FABRIC_MANIFEST, FabricModManifest.class);

            if (leavesFamilyMeta == null && fabricMeta == null) {
                return; // a plain plugin with no Cherry-relevant manifest at all - nothing to report
            }

            String pluginName = resolvePluginName(leavesFamilyMeta, fabricMeta, jar);

            if (CherryPluginFilter.isDisabled(pluginName)) {
                skipped.add(new SkippedItem(jar.getName(), pluginName, "all Cherry declarations",
                    "plugin disabled via -Dcherry.disable.plugins"));
                LOGGER.info("Cherry: plugin '{}' is disabled via -Dcherry.disable.plugins, skipping its declarations", pluginName);
                return;
            }

            if (leavesFamilyMeta != null) {
                collectAccessTransformers(jarFile, jar, pluginName, leavesFamilyMeta, leavesFamilySource, accessTransformers, skipped);
                collectLeavesMixinBlock(jarFile, jar, pluginName, leavesFamilyMeta, leavesFamilySource,
                    mixinConfigs, accessWideners, skipped, seenConfigResources, seenWidenerResources);
            }
            if (fabricMeta != null) {
                collectFabricMixins(jarFile, jar, pluginName, fabricMeta, mixinConfigs, skipped, seenConfigResources);
                collectFabricWidener(jarFile, jar, pluginName, fabricMeta, accessWideners, skipped, seenWidenerResources);
            }
        }
    }

    private static void collectAccessTransformers(
        JarFile jarFile, File jar, String pluginName, LeavesPluginMeta meta, ManifestSource source,
        List<DiscoveredAccessTransformer> out, List<SkippedItem> skipped
    ) {
        List<String> atFiles = meta.getAccessTransformers();
        if (atFiles == null || atFiles.isEmpty()) {
            return;
        }
        for (String atFile : atFiles) {
            if (atFile == null || atFile.isBlank()) {
                continue;
            }
            if (jarFile.getJarEntry(atFile) == null) {
                skipped.add(new SkippedItem(jar.getName(), pluginName, "access-transformer " + atFile, "entry missing from jar"));
                continue;
            }
            out.add(new DiscoveredAccessTransformer(pluginName, jar, atFile, source));
        }
    }

    private static void collectLeavesMixinBlock(
        JarFile jarFile, File jar, String pluginName, LeavesPluginMeta meta, ManifestSource source,
        List<DiscoveredMixinConfig> mixinConfigs, List<DiscoveredAccessWidener> accessWideners, List<SkippedItem> skipped,
        Set<String> seenConfigResources, Set<String> seenWidenerResources
    ) {
        LeavesPluginMeta.MixinConfig mixin = meta.getMixin();
        if (mixin == null) {
            return;
        }
        List<String> configs = mixin.getMixins();
        if (configs != null) {
            for (String configEntry : configs) {
                if (configEntry == null || configEntry.isBlank()) {
                    continue;
                }
                addMixinConfig(jarFile, jar, pluginName, configEntry, mixin.getPackageName(), source, "server",
                    mixinConfigs, skipped, seenConfigResources);
            }
        }
        String widener = mixin.getAccessWidener();
        if (widener != null && !widener.isBlank()) {
            addWidener(jarFile, jar, pluginName, widener, source, accessWideners, skipped, seenWidenerResources);
        }
    }

    private static void collectFabricMixins(
        JarFile jarFile, File jar, String pluginName, FabricModManifest fabricMeta,
        List<DiscoveredMixinConfig> mixinConfigs, List<SkippedItem> skipped, Set<String> seenConfigResources
    ) {
        for (FabricMixinEntry entry : fabricMeta.mixinEntries()) {
            if (!entry.appliesToServer()) {
                skipped.add(new SkippedItem(jar.getName(), pluginName, "fabric mixin config " + entry.config(),
                    "client-only environment ('" + entry.environment() + "'), not loaded by a server-side engine"));
                continue;
            }
            addMixinConfig(jarFile, jar, pluginName, entry.config(), null, ManifestSource.FABRIC_MOD_JSON, entry.environment(),
                mixinConfigs, skipped, seenConfigResources);
        }
    }

    private static void collectFabricWidener(
        JarFile jarFile, File jar, String pluginName, FabricModManifest fabricMeta,
        List<DiscoveredAccessWidener> accessWideners, List<SkippedItem> skipped, Set<String> seenWidenerResources
    ) {
        String widener = fabricMeta.getAccessWidener();
        if (widener == null || widener.isBlank()) {
            return;
        }
        addWidener(jarFile, jar, pluginName, widener, ManifestSource.FABRIC_MOD_JSON, accessWideners, skipped, seenWidenerResources);
    }

    private static void addMixinConfig(
        JarFile jarFile, File jar, String pluginName, String configEntry, String fallbackPackage,
        ManifestSource source, String environment,
        List<DiscoveredMixinConfig> out, List<SkippedItem> skipped, Set<String> seenConfigResources
    ) {
        JarEntry entry = jarFile.getJarEntry(configEntry);
        if (entry == null) {
            skipped.add(new SkippedItem(jar.getName(), pluginName, "mixin config " + configEntry, "entry missing from jar"));
            return;
        }

        MixinConfigMeta configMeta = tryParseMixinConfig(jarFile, entry);
        int priority = configMeta != null ? configMeta.priorityOrDefault() : MixinConfigMeta.DEFAULT_PRIORITY;
        String refmap = configMeta != null ? configMeta.getRefmap() : null;
        String packageName = (configMeta != null && configMeta.getPackageName() != null)
            ? configMeta.getPackageName() : fallbackPackage;

        if (source == ManifestSource.FABRIC_MOD_JSON && (packageName == null || packageName.isBlank())) {
            skipped.add(new SkippedItem(jar.getName(), pluginName, "fabric mixin config " + configEntry,
                "config's 'package' field is missing or unreadable; cannot determine which classes to extract"));
            return;
        }

        if (!seenConfigResources.add(configEntry)) {
            skipped.add(new SkippedItem(jar.getName(), pluginName, "mixin config " + configEntry,
                "duplicate resource name already claimed by another plugin/declaration on the shared classpath"));
            return;
        }

        out.add(new DiscoveredMixinConfig(pluginName, jar, configEntry, packageName, priority, refmap, environment, source));
    }

    private static void addWidener(
        JarFile jarFile, File jar, String pluginName, String resource, ManifestSource source,
        List<DiscoveredAccessWidener> out, List<SkippedItem> skipped, Set<String> seenWidenerResources
    ) {
        if (jarFile.getJarEntry(resource) == null) {
            skipped.add(new SkippedItem(jar.getName(), pluginName, "access-widener " + resource, "entry missing from jar"));
            return;
        }
        if (!seenWidenerResources.add(resource)) {
            skipped.add(new SkippedItem(jar.getName(), pluginName, "access-widener " + resource,
                "duplicate resource name already claimed by another plugin/declaration"));
            return;
        }
        out.add(new DiscoveredAccessWidener(pluginName, jar, resource, source));
    }

    private static MixinConfigMeta tryParseMixinConfig(JarFile jarFile, JarEntry entry) {
        try (InputStream in = jarFile.getInputStream(entry)) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return GSON.fromJson(json, MixinConfigMeta.class);
        } catch (Exception e) {
            LOGGER.warn("Cherry: failed to parse mixin config '{}' in {}: {}", entry.getName(), jarFile.getName(), describe(e));
            return null;
        }
    }

    private static <T> T parseJson(JarFile jarFile, String manifestName, Class<T> type) {
        JarEntry entry = jarFile.getJarEntry(manifestName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = jarFile.getInputStream(entry)) {
            return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            LOGGER.warn("Cherry: failed reading {} from {}: {}", manifestName, jarFile.getName(), describe(e));
            return null;
        }
    }

    private static String resolvePluginName(LeavesPluginMeta leavesFamilyMeta, FabricModManifest fabricMeta, File jar) {
        if (leavesFamilyMeta != null && leavesFamilyMeta.getName() != null && !leavesFamilyMeta.getName().isBlank()) {
            return leavesFamilyMeta.getName();
        }
        if (fabricMeta != null && fabricMeta.getId() != null && !fabricMeta.getId().isBlank()) {
            return fabricMeta.getId();
        }
        return jar.getName();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
