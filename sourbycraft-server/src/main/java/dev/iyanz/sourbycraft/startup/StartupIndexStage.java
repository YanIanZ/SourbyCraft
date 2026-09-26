package dev.iyanz.sourbycraft.startup;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The boot stage that builds the {@link PluginStartupIndex} before the plugin manager runs, and
 * keeps the result for {@code /sys}.
 *
 * <p>Runs from {@code SourbyCraftBootstrap#init()}, which the server calls before
 * {@code CraftServer#loadPlugins}. It changes nothing about plugin loading: the output is one
 * structured log line and, when a jar will be refused, one warning naming it.</p>
 *
 * <p>{@code -Daurora.startup.cache=false} keeps the analysis but neither reads nor writes the
 * cache file; the flag is read once per boot (RESTART_REQUIRED).</p>
 */
public final class StartupIndexStage {

    /** Relative to the server root, beside the other SourbyCraft-owned state. */
    static final Path CACHE_FILE = Path.of("sourbycraft_config", "cache", "startup-index.cache");

    private static volatile PluginStartupIndex.Result last;

    private StartupIndexStage() {}

    public static void run() {
        final boolean useCache = !"false".equalsIgnoreCase(System.getProperty("aurora.startup.cache"));
        final Path pluginsDir = pluginsFolder();
        final CacheEnvironment environment = CacheEnvironment.current(org.bukkit.Bukkit.getMinecraftVersion());
        final int width = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        final boolean[] warned = {false};
        final PluginStartupIndex.Result result = PluginStartupIndex.build(pluginsDir,
            useCache ? CACHE_FILE : null, environment, width, message -> {
                if (!warned[0]) {
                    warned[0] = true;
                    SourbyLogger.warn(message);
                }
            });
        last = result;
        for (final PluginStartupIndex.Indexed indexed : result.plugins()) {
            if (indexed.descriptor() != null) {
                dev.iyanz.sourbycraft.bridge.AuroraBridge.startupCacheState(indexed.descriptor().name(),
                    indexed.cacheHit() ? "hit" : "miss");
            }
        }

        final StartupTelemetry.Summary t = result.telemetry();
        SourbyLogger.info(String.format(Locale.ROOT,
            "Aurora startup index: %d jar(s), %s start (hit %d, miss %d, discarded %d) in %.1f ms%s",
            result.plugins().size(), t.startClass(), t.hits(), t.misses(), t.discarded(), t.totalMillis(),
            useCache ? "" : ", cache disabled"));
        final var undeclared = result.undeclared();
        if (!undeclared.isEmpty()) {
            SourbyLogger.warn(undeclared.size() + " plugin(s) do not declare folia-supported or "
                + "canvas-supported and will be "
                + (dev.iyanz.sourbycraft.bridge.AuroraBridge.runtimeMode() == dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode.SAFE
                    ? "loaded through the Aurora Bridge (aurora.bridge.mode = safe): "
                    : "refused by the region-threading base (aurora.bridge.mode = off): ")
                + undeclared.stream().map(p -> p.descriptor().name() + " [" + p.scan().verdict(false) + "]")
                    .collect(Collectors.joining(", ")));
            // No bridge can make server-internals access region-safe; say so separately.
            final String internals = undeclared.stream().filter(p -> p.scan().serverInternals())
                .map(p -> p.descriptor().name()).collect(Collectors.joining(", "));
            if (!internals.isEmpty()) {
                SourbyLogger.warn("Undeclared plugin(s) referencing net.minecraft/CraftBukkit internals, "
                    + "which the Aurora Bridge cannot route: " + internals);
            }
        }

        final long graphBegun = System.nanoTime();
        final DependencyGraph.Report graph = DependencyGraph.analyse(result.plugins().stream()
            .map(PluginStartupIndex.Indexed::descriptor).filter(java.util.Objects::nonNull).toList());
        lastGraph = graph;
        StartupTimeline.phase("startup-index:dependency-graph", System.nanoTime() - graphBegun);
        graph.missing().forEach((plugin, deps) -> SourbyLogger.warn("Plugin " + plugin
            + " depends on missing plugin(s) " + String.join(", ", deps) + "; the plugin manager will not load it"));
        for (final java.util.List<String> cycle : graph.cycles()) {
            SourbyLogger.warn("Plugin dependency cycle: " + String.join(" -> ", cycle));
        }

        t.phaseNanos().forEach((phase, nanos) -> StartupTimeline.phase("startup-index:" + phase, nanos));
        StartupTimeline.startClass(t.startClass());
    }

    private static volatile DependencyGraph.Report lastGraph;

    /** The dependency graph from the most recent build, or {@code null}. */
    public static DependencyGraph.Report lastGraph() {
        return lastGraph;
    }

    /** The most recent build, or {@code null} before the stage has run. */
    public static PluginStartupIndex.Result last() {
        return last;
    }

    private static Path pluginsFolder() {
        try {
            return org.bukkit.Bukkit.getPluginsFolder().toPath();
        } catch (final RuntimeException unavailable) {
            return Path.of("plugins");
        }
    }
}
