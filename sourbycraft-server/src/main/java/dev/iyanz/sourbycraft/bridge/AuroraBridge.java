package dev.iyanz.sourbycraft.bridge;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * The server-facing side of the Aurora Compatibility Bridge: what the plugin loader and
 * {@code CraftScheduler} patches call, and what {@code /plugins} reads.
 *
 * <p>Plugin providers are built during Paper's bootstrap, before {@code SourbyCraftBootstrap}
 * loads configuration, so the bridge reads its own two keys straight from the TOML files on first
 * use, read-only. {@code aurora.bridge.mode} is therefore fixed for the run (RESTART_REQUIRED);
 * {@code aurora.bridge.quarantine-after} follows later reloads (LIVE).</p>
 */
public final class AuroraBridge {

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.toml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.toml");
    private static volatile BridgeRuntime runtime;
    private static volatile int earlyQuarantineAfter = AuroraConfig.Bridge.DEFAULT.quarantineAfter();

    private AuroraBridge() {}

    static BridgeRuntime runtime() {
        BridgeRuntime current = runtime;
        if (current == null) {
            synchronized (AuroraBridge.class) {
                current = runtime;
                if (current == null) {
                    final AuroraConfig.Bridge settings = readEarly(AURORA_FILE, UNIFIED_FILE);
                    earlyQuarantineAfter = settings.quarantineAfter();
                    current = new BridgeRuntime(settings.mode(), AuroraBridge::quarantineAfter,
                        new FoliaExecutor(), new BridgeTelemetry(),
                        SourbyLogger::warn);
                    runtime = current;
                }
            }
        }
        return current;
    }

    private static int quarantineAfter() {
        try {
            final AuroraConfig loaded = dev.iyanz.sourbycraft.SourbyCraftConfig.aurora();
            // Before configuration loads, aurora() is the default; the early read is what the
            // operator set.
            return loaded == AuroraConfig.DEFAULT ? earlyQuarantineAfter : loaded.bridge().quarantineAfter();
        } catch (final Throwable unavailable) {
            return earlyQuarantineAfter;
        }
    }

    /**
     * Reads the bridge keys without the config system: Aurora's file over the unified file, the
     * same precedence {@code SourbyCraftConfig} applies. Missing or unreadable files mean defaults.
     */
    static AuroraConfig.Bridge readEarly(final Path auroraFile, final Path unifiedFile) {
        final Map<String, Object> values = new HashMap<>();
        for (final Path file : List.of(unifiedFile, auroraFile)) {
            if (!Files.isRegularFile(file)) continue;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                final var config = new com.electronwill.nightconfig.toml.TomlParser().parse(reader);
                for (final String key : List.of(AuroraConfig.BRIDGE_MODE_KEY, AuroraConfig.BRIDGE_QUARANTINE_KEY)) {
                    final Object value = config.get(key);
                    if (value != null) values.put(key, value);
                }
            } catch (final Exception unreadable) {
                SourbyLogger.warn("Aurora Bridge could not read " + file + " (" + unreadable.getMessage()
                    + "); the bridge stays off for this run");
                return AuroraConfig.Bridge.DEFAULT;
            }
        }
        final AuroraConfig.Parsed parsed = AuroraConfig.parse(new ConfigSnapshot(values));
        for (final String key : parsed.invalidKeys()) {
            SourbyLogger.warn("Aurora config key '" + key + "' is invalid; the bridge uses its default");
        }
        return parsed.config().bridge();
    }

    /**
     * Called by the plugin loader for a plugin that declares neither {@code folia-supported} nor
     * {@code canvas-supported}.
     *
     * @return whether the plugin may load through the bridge
     */
    public static boolean admitLegacy(final String pluginName) {
        final BridgeRuntime current = runtime();
        final boolean known = current.isBridged(pluginName);
        final boolean admitted = current.admit(pluginName);
        // The provider factory and the API-version check both ask; say it once.
        if (admitted && !known) {
            SourbyLogger.warn("Aurora Bridge admitted " + pluginName + " (no folia-supported/canvas-supported)."
                + " Its scheduler tasks are routed by ownership; it is not qualified for region threading.");
        }
        return admitted;
    }

    /** The bridge mode for this run. */
    public static AuroraConfig.BridgeMode runtimeMode() {
        return runtime().mode();
    }

    /** Whether the bridge owns this plugin's scheduler tasks. */
    public static boolean handles(final Plugin plugin) {
        final BridgeRuntime current = runtime;
        return plugin != null && current != null && current.isBridged(plugin.getName());
    }

    /** Schedules a bridged plugin's task; see {@link BridgeRuntime#submit}. */
    public static void submit(final Plugin plugin, final BridgeRuntime.Task task, final long delayTicks) {
        runtime().submit(plugin.getName(), plugin, task, delayTicks);
    }

    /** Cancels a task if the bridge scheduled it. */
    public static boolean cancel(final int taskId) {
        final BridgeRuntime current = runtime;
        return current != null && current.cancel(taskId);
    }

    /** Cancels every bridged task of a plugin; returns false when the plugin is not bridged. */
    public static boolean cancelAll(final Plugin plugin) {
        if (!handles(plugin)) return false;
        runtime.cancelAll(plugin.getName());
        return true;
    }

    /** Evidence for {@link CompatibilityClassifier}: bridged, not quarantined. */
    public static boolean bridgeInitialized(final Plugin plugin) {
        return handles(plugin) && !runtime.quarantined(plugin.getName());
    }

    /** Evidence for {@link CompatibilityClassifier}: quarantined for ownership violations. */
    public static boolean fatalViolation(final Plugin plugin) {
        return handles(plugin) && runtime.quarantined(plugin.getName());
    }

    /** Telemetry for one plugin, or {@code null} when it is not bridged. */
    public static BridgeTelemetry.PluginStats stats(final Plugin plugin) {
        final BridgeRuntime current = runtime;
        return plugin == null || current == null ? null : current.telemetry().stats(plugin.getName());
    }

    /** Telemetry for every bridged plugin; empty before the bridge is first used. */
    public static java.util.List<BridgeTelemetry.PluginStats> allStats() {
        final BridgeRuntime current = runtime;
        return current == null ? java.util.List.of() : current.telemetry().snapshot();
    }

    /** The bridge mode, or {@code null} when nothing has asked the bridge anything yet. */
    public static AuroraConfig.BridgeMode modeIfStarted() {
        final BridgeRuntime current = runtime;
        return current == null ? null : current.mode();
    }

    /** Records whether a plugin's startup analysis came from the startup cache. */
    public static void startupCacheState(final String pluginName, final String state) {
        final BridgeRuntime current = runtime;
        if (current != null && current.isBridged(pluginName)) {
            current.telemetry().cacheState(pluginName, state);
        }
    }

    /**
     * Cancels a bridged plugin's tasks when it disables. Folia no longer does this for the Bukkit
     * scheduler on disable, and the bridge's tasks live on the Folia schedulers.
     */
    public static void registerListener(final Plugin owner) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onDisable(final PluginDisableEvent event) {
                cancelAll(event.getPlugin());
            }
        }, owner);
    }

    /** Runs routed work on the Folia global region and async schedulers. */
    static final class FoliaExecutor implements BridgeRuntime.Executor {
        private static final long MILLIS_PER_TICK = 50L;

        @Override
        public BridgeRuntime.Handle global(final Object owner, final Runnable body, final long delayTicks,
                                           final long periodTicks) {
            final Plugin plugin = (Plugin)owner;
            final var scheduler = Bukkit.getGlobalRegionScheduler();
            final var task = periodTicks > 0
                ? scheduler.runAtFixedRate(plugin, t -> body.run(), Math.max(1L, delayTicks), periodTicks)
                : delayTicks > 0 ? scheduler.runDelayed(plugin, t -> body.run(), delayTicks)
                : scheduler.run(plugin, t -> body.run());
            return task::cancel;
        }

        @Override
        public BridgeRuntime.Handle async(final Object owner, final Runnable body, final long delayTicks,
                                          final long periodTicks) {
            final Plugin plugin = (Plugin)owner;
            final var scheduler = Bukkit.getAsyncScheduler();
            // Folia's async scheduler only times the work; the Resource Governor's bounded
            // BRIDGE_IO lane runs it, so legacy async tasks cannot grow into unbounded threads.
            final Runnable governed = () -> {
                try {
                    dev.iyanz.sourbycraft.execution.ResourceGovernor.GLOBAL
                        .lane(dev.iyanz.sourbycraft.execution.ResourceGovernor.Lane.BRIDGE_IO).execute(body);
                } catch (final java.util.concurrent.RejectedExecutionException full) {
                    runtime().telemetry().rejected(plugin.getName());
                }
            };
            final var task = periodTicks > 0
                ? scheduler.runAtFixedRate(plugin, t -> governed.run(), Math.max(1L, delayTicks) * MILLIS_PER_TICK,
                    periodTicks * MILLIS_PER_TICK, TimeUnit.MILLISECONDS)
                : delayTicks > 0 ? scheduler.runDelayed(plugin, t -> governed.run(), delayTicks * MILLIS_PER_TICK,
                    TimeUnit.MILLISECONDS)
                : scheduler.runNow(plugin, t -> governed.run());
            return task::cancel;
        }
    }
}
