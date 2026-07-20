package dev.iyanz.sourbyclip.cherry.fabric;

import dev.iyanz.sourbyclip.cherry.discovery.DiscoveredAccessWidener;
import dev.iyanz.sourbyclip.cherry.discovery.DiscoveredMixinConfig;
import dev.iyanz.sourbyclip.cherry.discovery.DiscoveryReport;
import dev.iyanz.sourbyclip.cherry.discovery.SkippedItem;
import org.leavesmc.leavesclip.logger.Logger;
import org.leavesmc.leavesclip.logger.SimpleLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Cherry — extracts Fabric-format mixin configs and access-wideners (declarations the Leaves engine
 * has no notion of at all, since they never appear in a {@code leaves-plugin.json}) into a cached,
 * classpath-loadable jar per contributing plugin, mirroring what LeavesMC/Leavesclip's own
 * {@code PluginResolver}/{@code MixinJarResolver} do for its own {@code mixin} block — but entirely
 * independently, so this bridge can neither destabilize nor depend on the Leaves pipeline.
 *
 * <p><b>What this class does NOT do.</b> It does not run a Fabric mod loader, does not resolve
 * Fabric mod dependencies or entrypoints, and does not touch anything client-side (environments
 * other than {@code server}/{@code *} are filtered out before a config ever reaches this class — see
 * {@link dev.iyanz.sourbyclip.cherry.discovery.CherryDiscovery}). It loads exactly three things a
 * Fabric-ecosystem plugin jar can declare for a <i>server</i>: SpongePowered Mixin configs, their
 * refmaps, and Fabric access-wideners. See the repository README's "Fabric support" section for the
 * full, honest scope statement.
 *
 * <p><b>Why this can only go so far standalone.</b> Actually registering an extracted config with
 * {@code Mixins.addConfiguration(String)}, and making its resource resolvable at all, requires the
 * returned jar URL to be on the host launcher's classpath <i>before</i> {@code MixinURLClassLoader}
 * is constructed — a timing constraint outside this repository's control (see
 * {@link FabricResolution}'s javadoc for the exact integration contract the host launcher must
 * implement). This class does the part that is fully within Cherry's control and independently
 * testable: discovering what to extract, extracting it correctly (including the refmap, when
 * declared), caching it so unchanged plugins are not re-extracted every boot, and handing back
 * exactly what the host needs to finish the wiring.
 */
public final class CherryFabricBridge {

    /** Jar entry name used to stamp the source plugin jar's MD5 hash into an extracted cache jar. */
    static final String HASH_MARKER_ENTRY = "cherry-fabric-plugin-hash";

    /** Suffix every cache jar this bridge writes carries, kept distinct from Leaves' own {@code .mixins.jar}. */
    static final String CACHE_JAR_SUFFIX = ".cherry-fabric.mixins.jar";

    private static final Logger LOGGER = new SimpleLogger("Cherry/Fabric");

    private CherryFabricBridge() {
    }

    /**
     * Extract every Fabric-declared mixin config and access-widener found in {@code report} into
     * {@code cacheDir}, reusing a previously extracted jar when the source plugin jar is unchanged
     * (compared by MD5, the same scheme Leaves' own {@code PluginResolver} uses). Never throws: any
     * per-plugin failure is caught, logged, and recorded in the returned
     * {@link FabricResolution#skipped()} — one broken plugin's Fabric declarations never prevent
     * another plugin's from being extracted.
     *
     * @param report   a discovery report, typically from {@code CherryDiscovery.scan(pluginsDir)}
     * @param cacheDir the directory to write/read cached extraction jars in, e.g.
     *                 {@code new File("plugins/.cherry-mixins")}; created if missing
     * @return what was extracted, ready for the host launcher to wire in; {@link FabricResolution#empty()}
     * if nothing in {@code report} needed Cherry's own extraction, or if {@code cacheDir} could not
     * be created
     */
    public static FabricResolution resolve(DiscoveryReport report, File cacheDir) {
        List<DiscoveredMixinConfig> fabricConfigs = report.mixinConfigs().stream()
            .filter(DiscoveredMixinConfig::requiresCherryExtraction)
            .toList();
        List<DiscoveredAccessWidener> fabricWideners = report.accessWideners().stream()
            .filter(DiscoveredAccessWidener::requiresCherryExtraction)
            .toList();

        if (fabricConfigs.isEmpty() && fabricWideners.isEmpty()) {
            return FabricResolution.empty();
        }
        if (!ensureDir(cacheDir)) {
            return FabricResolution.empty();
        }

        Map<File, String> jarToPlugin = new LinkedHashMap<>();
        Map<File, List<DiscoveredMixinConfig>> configsByJar = new LinkedHashMap<>();
        Map<File, List<DiscoveredAccessWidener>> widenersByJar = new LinkedHashMap<>();
        for (DiscoveredMixinConfig c : fabricConfigs) {
            configsByJar.computeIfAbsent(c.jarFile(), k -> new ArrayList<>()).add(c);
            jarToPlugin.putIfAbsent(c.jarFile(), c.pluginName());
        }
        for (DiscoveredAccessWidener w : fabricWideners) {
            widenersByJar.computeIfAbsent(w.jarFile(), k -> new ArrayList<>()).add(w);
            jarToPlugin.putIfAbsent(w.jarFile(), w.pluginName());
        }

        List<File> jarsSorted = new ArrayList<>(jarToPlugin.keySet());
        jarsSorted.sort(Comparator.comparing(jarToPlugin::get));

        List<URL> jarUrls = new ArrayList<>();
        List<SkippedItem> skipped = new ArrayList<>();
        Set<String> extractedConfigEntries = new HashSet<>();
        Set<String> extractedWidenerEntries = new HashSet<>();
        Set<File> keepCacheFiles = new HashSet<>();

        for (File pluginJar : jarsSorted) {
            String pluginName = jarToPlugin.get(pluginJar);
            List<DiscoveredMixinConfig> configs = configsByJar.getOrDefault(pluginJar, List.of());
            List<DiscoveredAccessWidener> wideners = widenersByJar.getOrDefault(pluginJar, List.of());
            try {
                ExtractedJar extracted = extractPlugin(pluginJar, pluginName, configs, wideners, cacheDir);
                jarUrls.add(extracted.jarUrl());
                extractedConfigEntries.addAll(extracted.configEntries());
                extractedWidenerEntries.addAll(extracted.widenerEntries());
                keepCacheFiles.add(extracted.cacheFile());
            } catch (Throwable t) {
                skipped.add(new SkippedItem(pluginJar.getName(), pluginName, "fabric mixin/widener extraction",
                    "failed: " + describe(t)));
                LOGGER.error("Cherry: failed extracting Fabric declarations for '" + pluginName + "'", t);
            }
        }

        cleanStaleCacheFiles(cacheDir, keepCacheFiles);

        List<String> mixinConfigNames = fabricConfigs.stream()
            .map(DiscoveredMixinConfig::configEntry)
            .distinct()
            .filter(extractedConfigEntries::contains)
            .toList();
        List<String> accessWidenerConfigs = fabricWideners.stream()
            .map(DiscoveredAccessWidener::entryName)
            .distinct()
            .filter(extractedWidenerEntries::contains)
            .toList();

        return new FabricResolution(mixinConfigNames, List.copyOf(jarUrls), accessWidenerConfigs, List.copyOf(skipped));
    }

    private static ExtractedJar extractPlugin(
        File pluginJar, String pluginName, List<DiscoveredMixinConfig> configs, List<DiscoveredAccessWidener> wideners, File cacheDir
    ) throws IOException {
        File cacheFile = new File(cacheDir, sanitizeFileName(pluginName) + CACHE_JAR_SUFFIX);
        String pluginJarHash = md5(pluginJar);

        if (cacheFile.isFile() && pluginJarHash != null && pluginJarHash.equals(readHashMarker(cacheFile))) {
            LOGGER.debug("Cherry: reusing cached fabric mixin jar for '{}' (unchanged since last extraction)", pluginName);
            return new ExtractedJar(cacheFile.toURI().toURL(), cacheFile, entryNames(configs), widenerEntryNames(wideners));
        }

        Set<String> filesToCopy = new HashSet<>();
        Set<String> packagePrefixes = new HashSet<>();
        for (DiscoveredMixinConfig config : configs) {
            filesToCopy.add(config.configEntry());
            if (config.packageName() != null && !config.packageName().isBlank()) {
                packagePrefixes.add(config.packageName().replace('.', '/'));
            }
        }
        for (DiscoveredAccessWidener widener : wideners) {
            filesToCopy.add(widener.entryName());
        }

        File tmp = File.createTempFile("cherry-fabric-", ".tmp", cacheDir);
        try (JarFile sourceJar = new JarFile(pluginJar)) {
            // The refmap check needs the open source jar, so resolve it here (rather than at
            // discovery time) and warn - but not fail - when a declared refmap is missing: Mixin
            // itself tolerates a missing refmap (falls back to identity mapping), so this must not
            // be treated as fatal, only as a clearly logged degradation.
            for (DiscoveredMixinConfig config : configs) {
                String refmap = config.refmap();
                if (refmap == null || refmap.isBlank()) {
                    continue;
                }
                if (sourceJar.getJarEntry(refmap) != null) {
                    filesToCopy.add(refmap);
                } else {
                    LOGGER.warn("Cherry: fabric mixin config '{}' (plugin '{}') declares refmap '{}' but it is missing "
                            + "from the jar; Mixin will fall back to an identity reference map for this config",
                        config.configEntry(), pluginName, refmap);
                }
            }

            try (JarOutputStream out = new JarOutputStream(new FileOutputStream(tmp))) {
                Enumeration<JarEntry> entries = sourceJar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    boolean underMixinPackage = packagePrefixes.stream().anyMatch(name::startsWith);
                    if (!underMixinPackage && !filesToCopy.contains(name)) {
                        continue;
                    }
                    out.putNextEntry(new JarEntry(name));
                    try (InputStream in = sourceJar.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
                out.putNextEntry(new JarEntry(HASH_MARKER_ENTRY));
                out.write((pluginJarHash == null ? "" : pluginJarHash).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("Cherry: extracted {} fabric mixin config(s) and {} widener(s) for '{}' into {}",
            configs.size(), wideners.size(), pluginName, cacheFile.getName());
        return new ExtractedJar(cacheFile.toURI().toURL(), cacheFile, entryNames(configs), widenerEntryNames(wideners));
    }

    private static void cleanStaleCacheFiles(File cacheDir, Set<File> keep) {
        File[] existing = cacheDir.listFiles((dir, name) -> name.endsWith(CACHE_JAR_SUFFIX));
        if (existing == null) {
            return;
        }
        for (File file : existing) {
            if (!keep.contains(file)) {
                try {
                    Files.deleteIfExists(file.toPath());
                    LOGGER.debug("Cherry: removed stale fabric mixin cache jar {}", file.getName());
                } catch (IOException e) {
                    LOGGER.warn("Cherry: failed to remove stale fabric mixin cache jar {}: {}", file.getName(), describe(e));
                }
            }
        }
    }

    private static Set<String> entryNames(List<DiscoveredMixinConfig> configs) {
        Set<String> names = new HashSet<>();
        for (DiscoveredMixinConfig c : configs) {
            names.add(c.configEntry());
        }
        return names;
    }

    private static Set<String> widenerEntryNames(List<DiscoveredAccessWidener> wideners) {
        Set<String> names = new HashSet<>();
        for (DiscoveredAccessWidener w : wideners) {
            names.add(w.entryName());
        }
        return names;
    }

    private static boolean ensureDir(File dir) {
        if (dir.exists() && !dir.isDirectory()) {
            LOGGER.warn("Cherry: '{}' exists and is not a directory; Fabric mixin/widener extraction disabled", dir.getAbsolutePath());
            return false;
        }
        if (dir.exists()) {
            return true;
        }
        if (!dir.mkdirs()) {
            LOGGER.warn("Cherry: failed to create '{}'; Fabric mixin/widener extraction disabled", dir.getAbsolutePath());
            return false;
        }
        return true;
    }

    private static String readHashMarker(File cacheJar) {
        try (JarFile jarFile = new JarFile(cacheJar)) {
            JarEntry entry = jarFile.getJarEntry(HASH_MARKER_ENTRY);
            if (entry == null) {
                return null;
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String md5(File file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        try (InputStream is = Files.newInputStream(file.toPath());
             DigestInputStream dis = new DigestInputStream(is, digest)) {
            byte[] buffer = new byte[8192];
            //noinspection StatementWithEmptyBody
            while (dis.read(buffer) != -1) {
            }
        } catch (IOException e) {
            return null;
        }
        StringBuilder sb = new StringBuilder(32);
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String sanitizeFileName(String name) {
        String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "plugin" : sanitized;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** One plugin's successfully extracted cache jar and what it contains. */
    private record ExtractedJar(URL jarUrl, File cacheFile, Set<String> configEntries, Set<String> widenerEntries) {
    }
}
