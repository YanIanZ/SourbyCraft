package dev.iyanz.sourbyclip.cherry;

import dev.iyanz.sourbyclip.cherry.at.CherryAccessTransformers;
import dev.iyanz.sourbyclip.cherry.discovery.CherryDiscovery;
import dev.iyanz.sourbyclip.cherry.discovery.DiscoveryReport;
import dev.iyanz.sourbyclip.cherry.discovery.SkippedItem;
import dev.iyanz.sourbyclip.cherry.fabric.CherryFabricBridge;
import dev.iyanz.sourbyclip.cherry.fabric.FabricResolution;
import org.leavesmc.leavesclip.logger.Logger;
import org.leavesmc.leavesclip.logger.SimpleLogger;
import org.leavesmc.leavesclip.mixin.PluginResolver;

import java.io.File;
import java.net.URL;
import java.util.List;

/**
 * Cherry — SourbyCraft's unified server-side mixin system, merging the two Paper-fork mixin
 * launchers into one that runs natively inside SourbyClip (so mixins "just work": no separate
 * launcher jar, no operator step).
 *
 * <p><b>What Cherry merges.</b>
 * <ul>
 *   <li><b>From LeavesMC (Leavesclip)</b> — the base engine, already vendored in SourbyClip:
 *       SpongePowered Mixin bootstrap via a Fabric-Knot {@code IMixinService}, plugin-mixin
 *       discovery ({@code plugins/*.jar} carrying a manifest + a mixin package), Fabric
 *       access-<i>wideners</i>, MixinExtras, and the {@code leaves-plugin-mixin-condition}
 *       conditional-mixin system. Cherry keeps this as its skeleton.</li>
 *   <li><b>From CraftCanvasMC (Horizon)</b> — the capability Leaves lacked: Forge/Paper-style
 *       access-<i>transformers</i> ({@code .at}). Horizon's {@code TransformerContainer} AT engine
 *       is ported into {@link CherryAccessTransformers} and slotted into the Leaves transforming
 *       classloader. (Horizon itself is a whole separate launcher — its own paperclip, dependency
 *       resolver and Java-agent classloading — which cannot coexist with SourbyClip's launcher, so
 *       merging its <i>architecture</i> wholesale is not possible; its portable, additive
 *       <i>capability</i> is the AT engine, which is what Cherry takes.)</li>
 *   <li><b>From the Fabric ecosystem</b> — {@code fabric.mod.json} server-side mixin/access-widener
 *       declarations, bridged in by {@link CherryFabricBridge} since the Leaves engine only
 *       understands its own {@code leaves-plugin.json}/{@code cherry-plugin.json} manifest shape.
 *       This loads server-side mixins/refmaps/access-wideners a Fabric mod jar declares — it does
 *       <b>not</b> run a Fabric mod loader (no entrypoints, no client side); see the repository
 *       README's "Fabric support" section.</li>
 * </ul>
 *
 * <p><b>Result for a plugin author.</b> Ship a manifest — {@code cherry-plugin.json} (preferred),
 * the legacy {@code leaves-plugin.json}, or a Fabric-ecosystem {@code fabric.mod.json} — declaring
 * any combination of a {@code mixin}/{@code mixins} block, an {@code access-transformers} list, and
 * an access-widener. Enable the engine with {@code -Dcherry.enable.mixin=true} (aliases:
 * {@code -Dsourbyclip.enable.mixin}, {@code -Dleavesclip.enable.mixin}).
 *
 * <p><b>Integration entry points, in three tiers:</b>
 * <ol>
 *   <li>{@link #initAccessTransformers()} — the original, already-integrated entry point. Unchanged
 *       behavior: discovers and commits access-transformers only.</li>
 *   <li>{@link #initFabricMixins()} — new: discovers and extracts Fabric-format mixin
 *       configs/wideners. Must run <b>before</b> the host constructs its transforming classloader
 *       (see {@link FabricResolution}'s javadoc for why), which is earlier than when
 *       {@link #initAccessTransformers()} runs — so these two cannot simply be merged into one call
 *       without changing the existing AT integration's timing.</li>
 *   <li>{@link #init(File, File)} — new: the recommended all-in-one entry point for a host launcher
 *       being wired up fresh. Scans once, commits both engines, and logs the one combined "Cherry
 *       ready" summary. Requires the caller to run it before transforming-classloader construction
 *       (the earlier of the two timing requirements above).</li>
 * </ol>
 */
public final class Cherry {

    /** Human-readable name of this mixin engine, used for identification/branding purposes. */
    public static final String NAME = "Cherry";

    private static final Logger LOGGER = new SimpleLogger("Cherry");
    private static final String DEFAULT_CACHE_DIR_NAME = ".cherry-mixins";

    /** The most recent {@link CherryFabricBridge#resolve} result; empty until an init method runs. */
    private static volatile FabricResolution lastFabricResolution = FabricResolution.empty();

    /** System property that turns the Cherry mixin engine on (plus back-compat aliases). */
    public static boolean enabled() {
        return Boolean.getBoolean("cherry.enable.mixin")
            || Boolean.getBoolean("sourbyclip.enable.mixin")
            || Boolean.getBoolean("leavesclip.enable.mixin");
    }

    /**
     * Discover + register plugin access-transformers. Called by the launcher once the mixin
     * environment is up but before the transforming classloader starts loading server classes.
     */
    public static void initAccessTransformers() {
        CherryPluginResolver.resolveAccessTransformers();
    }

    /** @return true if any plugin registered an access-transformer. */
    public static boolean hasAccessTransformers() {
        return !CherryAccessTransformers.INSTANCE.isEmpty();
    }

    /**
     * Apply registered access-transformers to a class about to be defined. No-op (returns the
     * input array) for classes with no AT definitions, so it is cheap to call on every class.
     */
    public static byte[] applyAccessTransformers(byte[] classBytes) {
        return CherryAccessTransformers.INSTANCE.applyToBytes(classBytes);
    }

    /**
     * Discover and extract every Fabric-format ({@code fabric.mod.json}) mixin config and
     * access-widener declared under {@code plugins/}, using the default plugins directory and a
     * {@code plugins/.cherry-mixins} cache directory. See {@link #initFabricMixins(File, File)}.
     *
     * <p><b>Timing requirement:</b> unlike {@link #initAccessTransformers()}, this must run before
     * the host launcher constructs its transforming classloader — {@link #fabricJarUrls()} needs to
     * be merged into that classloader's classpath at construction time, not after.
     */
    public static void initFabricMixins() {
        initFabricMixins(defaultPluginsDir(), defaultCacheDir());
    }

    /**
     * As {@link #initFabricMixins()}, with an explicit plugins/cache directory (mainly for hosts
     * that lay out their working directory differently, and for tests).
     *
     * @param pluginsDir the plugins directory to scan
     * @param cacheDir   where to write/read cached extraction jars
     */
    public static void initFabricMixins(File pluginsDir, File cacheDir) {
        DiscoveryReport report = CherryDiscovery.scan(pluginsDir);
        FabricResolution resolution = CherryFabricBridge.resolve(report, cacheDir);
        lastFabricResolution = resolution;
        logSkips(report.skipped(), "access-transformer");
        logSkips(resolution.skipped(), null);
        LOGGER.info("Cherry: fabric bridge resolved {} mixin config(s) and {} widener(s) across {} jar(s)",
            resolution.mixinConfigNames().size(), resolution.accessWidenerConfigs().size(), resolution.jarUrls().size());
    }

    /**
     * @return the Fabric-declared mixin config resource names {@link #initFabricMixins} extracted,
     * in the order they should be handed to {@code Mixins.addConfiguration}. Empty until an init
     * method has run.
     */
    public static List<String> fabricMixinConfigNames() {
        return lastFabricResolution.mixinConfigNames();
    }

    /**
     * @return the cached jar URLs {@link #initFabricMixins} produced, to be added to the
     * transforming classloader's classpath before it is constructed. Empty until an init method has
     * run.
     */
    public static List<URL> fabricJarUrls() {
        return lastFabricResolution.jarUrls();
    }

    /**
     * @return the Fabric-declared ({@code fabric.mod.json}'s {@code accessWidener} field) widener
     * resource names {@link #initFabricMixins} extracted, to be merged into the host's access-widener
     * pipeline. Empty until an init method has run.
     */
    public static List<String> fabricAccessWidenerConfigs() {
        return lastFabricResolution.accessWidenerConfigs();
    }

    /**
     * The recommended all-in-one entry point: scans {@code plugins/} once, commits both the
     * access-transformer engine and the Fabric mixin/widener bridge, and logs the one combined
     * "Cherry ready" summary line. Uses the default plugins directory and a
     * {@code plugins/.cherry-mixins} cache directory.
     *
     * <p>Carries the earlier of {@link #initAccessTransformers()}'s and
     * {@link #initFabricMixins()}'s two timing requirements: run this before the host constructs
     * its transforming classloader.
     */
    public static void init() {
        init(defaultPluginsDir(), defaultCacheDir());
    }

    /**
     * As {@link #init()}, with an explicit plugins/cache directory.
     *
     * @param pluginsDir the plugins directory to scan
     * @param cacheDir   where to write/read cached Fabric extraction jars
     */
    public static void init(File pluginsDir, File cacheDir) {
        DiscoveryReport report = CherryDiscovery.scan(pluginsDir);
        CherryPluginResolver.commitAccessTransformers(report);
        lastFabricResolution = CherryFabricBridge.resolve(report, cacheDir);
        logSkips(report.skipped(), "access-transformer");
        logSkips(lastFabricResolution.skipped(), null);
        LOGGER.info(report.summaryLine());
    }

    /**
     * Report what a fresh scan of {@code plugins/} would load, without registering, extracting,
     * locking, or otherwise mutating anything — safe to call whether or not {@link #enabled()} is
     * true, for an operator previewing what enabling Cherry (or adding a new plugin) would do.
     *
     * @return a fresh {@link DiscoveryReport} for the default plugins directory
     */
    public static DiscoveryReport dryRun() {
        return dryRun(defaultPluginsDir());
    }

    /**
     * As {@link #dryRun()}, scanning an explicit directory instead of the default {@code plugins/}
     * (mainly for hosts with a non-standard layout, and for tests).
     *
     * @param pluginsDir the directory to scan
     * @return a fresh {@link DiscoveryReport} for {@code pluginsDir}
     */
    public static DiscoveryReport dryRun(File pluginsDir) {
        return CherryDiscovery.scan(pluginsDir);
    }

    /**
     * @return a full diagnostic snapshot: a fresh discovery of {@code plugins/} (what would load),
     * layered with what the access-transformer and Fabric engines actually have active right now
     * (what has already been committed by an init method, if any). Intended as the data source for
     * a future {@code /cherry} operator command.
     */
    public static CherryStatus status() {
        return status(defaultPluginsDir());
    }

    /**
     * As {@link #status()}, scanning an explicit directory instead of the default {@code plugins/}.
     *
     * @param pluginsDir the directory to scan for the {@link CherryStatus#discovery()} portion
     * @return a full diagnostic snapshot for {@code pluginsDir}
     */
    public static CherryStatus status(File pluginsDir) {
        DiscoveryReport report = CherryDiscovery.scan(pluginsDir);
        return new CherryStatus(
            enabled(),
            report,
            CherryAccessTransformers.INSTANCE.isLocked(),
            CherryAccessTransformers.INSTANCE.registeredCount(),
            CherryAccessTransformers.INSTANCE.targetClassCount(),
            lastFabricResolution
        );
    }

    private static void logSkips(List<SkippedItem> items, String excludedPrefix) {
        for (SkippedItem item : items) {
            if (excludedPrefix == null || !item.item().startsWith(excludedPrefix)) {
                LOGGER.warn("Cherry: skipped {}", item.describe());
            }
        }
    }

    private static File defaultPluginsDir() {
        return new File(PluginResolver.PLUGIN_DIRECTORY);
    }

    private static File defaultCacheDir() {
        return new File(PluginResolver.PLUGIN_DIRECTORY, DEFAULT_CACHE_DIR_NAME);
    }

    private Cherry() {
    }
}
