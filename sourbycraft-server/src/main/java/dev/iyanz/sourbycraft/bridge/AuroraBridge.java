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
 * loads configuration, so the bridge reads its own keys straight from the TOML files on first
 * use, read-only. {@code aurora.bridge.mode} is therefore fixed for the run (RESTART_REQUIRED);
 * {@code aurora.bridge.quarantine-after} follows later reloads (LIVE).</p>
 */
public final class AuroraBridge {

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.toml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.toml");
    private static volatile BridgeRuntime runtime;
    private static volatile int earlyQuarantineAfter = AuroraConfig.Bridge.DEFAULT.quarantineAfter();
    private static volatile AuroraConfig.SyncRoute earlySyncRoute = AuroraConfig.Bridge.DEFAULT.syncRoute();
    private static volatile int earlyMaxPendingTasks = AuroraConfig.Bridge.DEFAULT.maxPendingTasksPerPlugin();
    private static volatile int earlyMaxRunningAsyncTasks = AuroraConfig.Bridge.DEFAULT.maxRunningAsyncTasksPerPlugin();

    private AuroraBridge() {}

    static BridgeRuntime runtime() {
        BridgeRuntime current = runtime;
        if (current == null) {
            synchronized (AuroraBridge.class) {
                current = runtime;
                if (current == null) {
                    final AuroraConfig.Bridge settings = readEarly(AURORA_FILE, UNIFIED_FILE);
                    earlyQuarantineAfter = settings.quarantineAfter();
                    earlySyncRoute = settings.syncRoute();
                    earlyMaxPendingTasks = settings.maxPendingTasksPerPlugin();
                    earlyMaxRunningAsyncTasks = settings.maxRunningAsyncTasksPerPlugin();
                    final dev.iyanz.sourbycraft.execution.region.CurrentRegion regions =
                        new dev.iyanz.sourbycraft.execution.region.FoliaRegionBackend();
                    current = new BridgeRuntime(settings.mode(), AuroraBridge::quarantineAfter,
                        new FoliaExecutor(), new BridgeTelemetry(),
                        SourbyLogger::warn, regions::anchor, AuroraBridge::syncRoute,
                        AuroraBridge::maxPendingTasks, AuroraBridge::maxRunningAsyncTasks);
                    runtime = current;
                }
            }
        }
        return current;
    }

    private static AuroraConfig.SyncRoute syncRoute() {
        try {
            final AuroraConfig loaded = dev.iyanz.sourbycraft.SourbyCraftConfig.aurora();
            return loaded == AuroraConfig.DEFAULT ? earlySyncRoute : loaded.bridge().syncRoute();
        } catch (final Throwable unavailable) {
            return earlySyncRoute;
        }
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

    private static int maxPendingTasks() {
        try {
            final AuroraConfig loaded = dev.iyanz.sourbycraft.SourbyCraftConfig.aurora();
            return loaded == AuroraConfig.DEFAULT ? earlyMaxPendingTasks : loaded.bridge().maxPendingTasksPerPlugin();
        } catch (final Throwable unavailable) {
            return earlyMaxPendingTasks;
        }
    }

    private static int maxRunningAsyncTasks() {
        try {
            final AuroraConfig loaded = dev.iyanz.sourbycraft.SourbyCraftConfig.aurora();
            return loaded == AuroraConfig.DEFAULT ? earlyMaxRunningAsyncTasks : loaded.bridge().maxRunningAsyncTasksPerPlugin();
        } catch (final Throwable unavailable) {
            return earlyMaxRunningAsyncTasks;
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
                for (final String key : List.of(AuroraConfig.BRIDGE_MODE_KEY, AuroraConfig.BRIDGE_QUARANTINE_KEY,
                    AuroraConfig.BRIDGE_SYNC_ROUTE_KEY, AuroraConfig.BRIDGE_MAX_PENDING_KEY,
                    AuroraConfig.BRIDGE_MAX_RUNNING_ASYNC_KEY)) {
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

    /** {@code CraftScheduler.isQueued}: whether the bridge holds this task. Never creates the runtime. */
    public static boolean knows(final int taskId) {
        final BridgeRuntime current = runtime;
        return current != null && current.knows(taskId);
    }

    /** {@code CraftScheduler.getPendingTasks}: the bridged tasks still scheduled. Never creates the runtime. */
    public static java.util.List<Object> pendingTasks() {
        final BridgeRuntime current = runtime;
        return current == null ? java.util.List.of() : current.pendingHandles();
    }

    /** {@code CraftScheduler.isCurrentlyRunning} for a task the bridge holds. */
    public static boolean isRunning(final int taskId) {
        final BridgeRuntime current = runtime;
        return current != null && current.running(taskId);
    }

    /** Bukkit worker snapshots use the actual governed-lane thread and original plugin/task ID. */
    public static java.util.List<org.bukkit.scheduler.BukkitWorker> activeWorkers() {
        final BridgeRuntime current = runtime;
        if (current == null) return java.util.List.of();
        final var workers = new java.util.ArrayList<org.bukkit.scheduler.BukkitWorker>();
        for (final BridgeRuntime.AsyncWorker worker : current.activeWorkers()) {
            workers.add(new org.bukkit.scheduler.BukkitWorker() {
                @Override public int getTaskId() { return worker.taskId(); }
                @Override public Plugin getOwner() { return (Plugin)worker.owner(); }
                @Override public Thread getThread() { return worker.thread(); }
            });
        }
        return java.util.List.copyOf(workers);
    }

    /** Current load and LIVE limits, without allocating or starting a bridge runtime. */
    public record TaskLoad(int pending, int runningAsync, int pendingLimit, int runningAsyncLimit) {}

    public static TaskLoad taskLoad(final Plugin plugin) {
        final BridgeRuntime current = runtime;
        if (plugin == null || current == null || !current.isBridged(plugin.getName())) return null;
        return new TaskLoad(current.pending(plugin.getName()), current.runningAsync(plugin.getName()),
            maxPendingTasks(), maxRunningAsyncTasks());
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

    /** Resolves an explicit callback location or the fingerprinted SuperiorSkyblock spawn callback. */
    public static dev.iyanz.sourbycraft.execution.region.RegionAnchor targetRegion(final Plugin plugin,
                                                                                final Object callback) {
        if (callback instanceof dev.iyanz.sourbycraft.api.scheduler.RegionTask regionTask) {
            return new dev.iyanz.sourbycraft.execution.region.RegionAnchor(regionTask.world(),
                regionTask.chunkX(), regionTask.chunkZ());
        }
        return SuperiorSpawnTaskOwner.resolve(plugin, callback);
    }

    /** Resolves an explicit entity target ({@code EntityTask}); {@code null} when the callback names none. */
    public static Object targetEntity(final Plugin plugin, final Object callback) {
        return callback instanceof dev.iyanz.sourbycraft.api.scheduler.EntityTask entityTask ? entityTask.entity() : null;
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

    /** GlobalRegionScheduler cancellation also releases the bridge's task indexes. */
    public static void cancelGlobal(final Plugin plugin) {
        final BridgeRuntime current = runtime;
        if (plugin != null && current != null) current.cancelGlobal(plugin.getName());
    }

    /** Closes admission even before the bridge's disable listener has been registered. */
    public static void disable(final Plugin plugin) {
        final BridgeRuntime current = runtime;
        if (plugin != null && current != null) current.disable(plugin.getName());
    }

    /**
     * Paper {@code PaperPluginInstanceManager#enablePlugin} (feature patch 0012), before the
     * plugin's {@code onEnable}: re-opens admission that a previous disable closed, so a plugin
     * enabled again as the same instance can schedule. No-op for native plugins and before the
     * bridge exists; never creates the runtime and never throws into the loader.
     */
    public static void onPluginEnabled(final Plugin plugin) {
        final BridgeRuntime current = runtime;
        if (plugin == null || current == null) return;
        try {
            current.reopen(plugin.getName());
        } catch (final RuntimeException unexpected) {
            SourbyLogger.warn("Aurora Bridge could not reopen admission for " + plugin.getName() + ": " + unexpected);
        }
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
                disable(event.getPlugin());
            }
        }, owner);
    }

    /** Runs routed work on the Folia global region, region, entity and async schedulers. */
    static final class FoliaExecutor implements BridgeRuntime.Executor {
        private static final long MILLIS_PER_TICK = 50L;

        @Override
        public BridgeRuntime.Handle global(final Object owner, final Runnable body, final long delayTicks,
                                           final long periodTicks) {
            final Plugin plugin = (Plugin)owner;
            // The public global scheduler routes legacy requests back through Bukkit. These
            // native entry points preserve global ownership without re-entering the bridge.
            final var scheduler = (io.papermc.paper.threadedregions.scheduler.FoliaGlobalRegionScheduler)
                Bukkit.getGlobalRegionScheduler();
            final var task = periodTicks > 0
                ? scheduler.runAtFixedRateNative(plugin, t -> body.run(), Math.max(1L, delayTicks), periodTicks)
                : scheduler.runDelayedNative(plugin, t -> body.run(), Math.max(1L, delayTicks));
            return task::cancel;
        }

        @Override
        public BridgeRuntime.Handle region(final Object owner, final dev.iyanz.sourbycraft.execution.region.RegionAnchor anchor,
                                           final Runnable body, final long delayTicks, final long periodTicks) {
            final Plugin plugin = (Plugin)owner;
            final org.bukkit.World world = (org.bukkit.World)anchor.world();
            final var scheduler = Bukkit.getRegionScheduler();
            final var task = periodTicks > 0
                ? scheduler.runAtFixedRate(plugin, world, anchor.chunkX(), anchor.chunkZ(), t -> body.run(),
                    Math.max(1L, delayTicks), periodTicks)
                : delayTicks > 0 ? scheduler.runDelayed(plugin, world, anchor.chunkX(), anchor.chunkZ(),
                    t -> body.run(), delayTicks)
                : scheduler.run(plugin, world, anchor.chunkX(), anchor.chunkZ(), t -> body.run());
            return task::cancel;
        }

        @Override
        public BridgeRuntime.Handle entity(final Object owner, final Object target, final Runnable body,
                                           final long delayTicks, final long periodTicks, final Runnable retired) {
            final Plugin plugin = (Plugin)owner;
            final org.bukkit.entity.Entity entity = (org.bukkit.entity.Entity)target;
            final var scheduler = entity.getScheduler();
            final var task = periodTicks > 0
                ? scheduler.runAtFixedRate(plugin, t -> body.run(), retired, Math.max(1L, delayTicks), periodTicks)
                : delayTicks > 0 ? scheduler.runDelayed(plugin, t -> body.run(), retired, delayTicks)
                : scheduler.run(plugin, t -> body.run(), retired);
            if (task == null) {
                // Folia returns null without calling retired when the entity is already removed.
                retired.run();
                return () -> {};
            }
            return task::cancel;
        }

        @Override
        public BridgeRuntime.Handle async(final Object owner, final Runnable body, final long delayTicks,
                                          final long periodTicks) {
            return async(owner, body, delayTicks, periodTicks, () -> {});
        }

        @Override
        public BridgeRuntime.Handle async(final Object owner, final Runnable body, final long delayTicks,
                                          final long periodTicks, final Runnable rejected) {
            final Plugin plugin = (Plugin)owner;
            final var scheduler = Bukkit.getAsyncScheduler();
            // Folia's async scheduler only times the work; the Resource Governor's bounded
            // BRIDGE_IO lane runs it, so legacy async tasks cannot grow into unbounded threads.
            final Runnable governed = () -> {
                try {
                    dev.iyanz.sourbycraft.execution.ResourceGovernor.GLOBAL
                        .lane(dev.iyanz.sourbycraft.execution.ResourceGovernor.Lane.BRIDGE_IO).execute(body);
                } catch (final java.util.concurrent.RejectedExecutionException full) {
                    rejected.run();
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
