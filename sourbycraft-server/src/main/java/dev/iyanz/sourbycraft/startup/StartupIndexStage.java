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
 * structured log line and, when a jar does not declare region support, one warning naming it and what the bridge mode did with it.</p>
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
        final PluginStartupIndex.Result result = PluginStartupIndex.build(pluginsDir,
            useCache ? CACHE_FILE : null, environment, width, warnings(SourbyLogger::warn));
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
                + "canvas-supported and "
                + (dev.iyanz.sourbycraft.bridge.AuroraBridge.runtimeMode() == dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode.SAFE
                    ? "are loaded through the Aurora Bridge (aurora.bridge.mode = safe): "
                    : "were not loaded: the region-threading base refuses them while aurora.bridge.mode = off"
                        + " (set \"safe\" and restart to try them through the Aurora Bridge): ")
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

    /**
     * At most one WARN for reading (or listing) and one for writing the cache. A cache that can be
     * neither read nor rewritten, e.g. a directory at its path, used to log only the read line, so
     * the next boot's identical miss had no visible cause.
     */
    static java.util.function.Consumer<String> warnings(final java.util.function.Consumer<String> sink) {
        final boolean[] warned = {false, false};
        return message -> {
            final int kind = message.startsWith(PluginStartupIndex.CACHE_WRITE_FAILED) ? 1 : 0;
            if (!warned[kind]) {
                warned[kind] = true;
                sink.accept(message);
            }
        };
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
