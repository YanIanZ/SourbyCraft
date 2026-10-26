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
 * loads configuration, so the bridge reads its own keys straight from the YAML files on first
 * use, read-only ({@link #earlySettings()}). {@code aurora.bridge.mode} is therefore fixed for
 * the run (RESTART_REQUIRED); {@code aurora.bridge.quarantine-after} follows later reloads (LIVE).</p>
 */
public final class AuroraBridge {

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.yml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");
    private static volatile BridgeRuntime runtime;
    private static volatile int earlyQuarantineAfter = AuroraConfig.Bridge.DEFAULT.quarantineAfter();
    private static volatile AuroraConfig.SyncRoute earlySyncRoute = AuroraConfig.Bridge.DEFAULT.syncRoute();
    private static volatile int earlyMaxPendingTasks = AuroraConfig.Bridge.DEFAULT.maxPendingTasksPerPlugin();
    private static volatile int earlyMaxRunningAsyncTasks = AuroraConfig.Bridge.DEFAULT.maxRunningAsyncTasksPerPlugin();
    /**
     * Whether any plugin has been admitted this run. The event and command context hooks run on hot paths
     * (every event dispatch); while nothing is bridged they return at once and publish nothing.
     */
    private static volatile boolean contextActive;

    private AuroraBridge() {}

    /**
     * The bridge settings as the YAML files held them when first asked (a legacy TOML file only while its YAML
     * file does not exist yet); read once, never creates the runtime.
     */
    private static volatile AuroraConfig.Bridge earlySettings;
    // ThreadChecks class initialization reads these settings while runtime() invokes ThreadChecks
    // under AuroraBridge.class. A separate monitor prevents a class-initialization lock cycle.
    private static final Object EARLY_SETTINGS_LOCK = new Object();

    /**
     * {@code aurora.bridge.*} as configured on disk, read once on first use (before the plugin scan, or earlier
     * when the region-ownership checks ask: {@code guards/ThreadChecks}) from {@code sourbycraft_config/aurora.yml}
     * over {@code sourbycraft_global_config.yml} ({@code readEarly}). Never creates the bridge runtime.
     */
    public static AuroraConfig.Bridge earlySettings() {
        AuroraConfig.Bridge settings = earlySettings;
        if (settings == null) {
            synchronized (EARLY_SETTINGS_LOCK) {
                settings = earlySettings;
                if (settings == null) {
                    settings = readEarly(AURORA_FILE, UNIFIED_FILE);
                    earlySettings = settings;
                }
            }
        }
        return settings;
    }

    static BridgeRuntime runtime() {
        BridgeRuntime current = runtime;
        if (current == null) {
            synchronized (AuroraBridge.class) {
                current = runtime;
                if (current == null) {
                    final AuroraConfig.Bridge settings = earlySettings();
                    dev.iyanz.sourbycraft.guards.ThreadChecks.bootReport(SourbyLogger::warn, SourbyLogger::info);
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
                    current.worldClosedCheck(AuroraBridge::worldUnloading);
                    runtime = current;
                }
            }
        }
        return current;
    }

    /**
     * A world whose unload has begun: CraftServer sets its unload ticket before the world's regions are
     * descheduled, and the ticket is never cleared (an unloaded world is not loaded again as the same
     * object).
     */
    static boolean worldUnloading(final Object world) {
        return world instanceof org.bukkit.craftbukkit.CraftWorld craft && craft.getHandle().canvas$unloadTicket.isPresent();
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
            final Path source = dev.iyanz.sourbycraft.config.ConfigSources.existing(file);
            if (source == null) continue;
            try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                final var config = dev.iyanz.sourbycraft.config.ConfigSources.parse(source, reader);
                for (final String key : List.of(AuroraConfig.BRIDGE_MODE_KEY, AuroraConfig.BRIDGE_QUARANTINE_KEY,
                    AuroraConfig.BRIDGE_SYNC_ROUTE_KEY, AuroraConfig.BRIDGE_MAX_PENDING_KEY,
                    AuroraConfig.BRIDGE_MAX_RUNNING_ASYNC_KEY, AuroraConfig.BRIDGE_THREAD_CHECKS_KEY)) {
                    final Object value = config.get(key);
                    if (value != null) values.put(key, value);
                }
            } catch (final Exception unreadable) {
                bootWarn("Aurora Bridge could not read " + dev.iyanz.sourbycraft.config.ConfigParseErrors.describe(source, unreadable)
                    + "; fix that line or delete the file to reseed the defaults, because the bridge stays off this run");
                return AuroraConfig.Bridge.DEFAULT;
            }
        }
        final AuroraConfig.Parsed parsed = AuroraConfig.parse(new ConfigSnapshot(values));
        for (final String key : parsed.invalidKeys()) {
            bootWarn("Aurora config key '" + key + "' is invalid; the bridge uses its default");
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
        if (admitted) contextActive = true;
        // The provider factory and the API-version check both ask; say it once.
        if (admitted && !known) {
            bootWarn("Aurora Bridge admitted " + pluginName + " (no folia-supported/canvas-supported)."
                + " Its scheduler tasks are routed by ownership; it is not qualified for region threading.");
        }
        return admitted;
    }

    /**
     * Paper {@code FileProviderSource#registerProviders} (feature patch 0024), for a jar whose plugin declares
     * neither {@code folia-supported} nor {@code canvas-supported} and that the bridge did not admit
     * ({@code aurora.bridge.mode = off}): the plugin is skipped with one clear WARN instead of the base's
     * {@code Error loading plugin} ERROR and stack trace, and recorded as a load failure for {@code /plugins}
     * (the plugin-load log capture is installed later in boot and never saw this refusal). Never throws.
     *
     * @param displayName {@code Name vVersion}
     * @param jarName the jar's file name
     */
    public static void refused(final String pluginName, final String displayName, final String jarName) {
        try {
            bootWarn(refusalMessage(displayName, jarName));
            dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics.record(jarName, pluginName, REFUSAL_REASON);
        } catch (final RuntimeException unexpected) {
            // Diagnostics only; the plugin stays refused either way.
        }
    }

    /**
     * Admission and refusal are decided while Paper builds plugin providers, before the JUL-to-Log4j bridge
     * is installed: through {@link SourbyLogger} (JUL) these lines reached the console unformatted
     * ({@code WARNING: ...} after a JUL date line) and never reached logs/latest.log. Log4j directly.
     */
    private static void bootWarn(final String message) {
        org.apache.logging.log4j.LogManager.getLogger("SourbyCraft").warn(message);
    }

    static final String REFUSAL_REASON = "not marked folia-supported or canvas-supported; aurora.bridge.mode = off"
        + " (set \"safe\" in sourbycraft_config/aurora.yml and restart to load it through the Aurora Bridge)";

    static String refusalMessage(final String displayName, final String jarName) {
        return "Not loading " + displayName + " (" + jarName + "): it does not declare folia-supported or"
            + " canvas-supported and aurora.bridge.mode = off. Set aurora.bridge.mode = \"safe\" and restart to try it"
            + " through the Aurora Bridge; a bridged plugin is not qualified for region threading.";
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

    // ---------------------------------------------------------------------------------------------
    // CraftScheduler hooks. Each Paper feature patch site in org.bukkit.craftbukkit.scheduler.CraftScheduler
    // is one call into one of these (Build 50, docs/development/UPGRADING-MINECRAFT.md hook table), so a
    // Paper update conflicts on a call line, not on bridge behaviour. None of them creates the runtime
    // except submitIfBridged for a plugin the bridge already handles.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code CraftScheduler#cancelTask} (Paper feature patches 0003, 0006): cancels the task if the bridge
     * holds it, and always tells the caller to stop. Nothing below can hold a task on a region-threaded
     * server: every task the Bukkit scheduler accepts goes to the bridge, and {@code handle()} refuses the
     * rest. A task the bridge no longer knows has finished or was cancelled already, and cancelling it is a
     * no-op, as on Paper. Falling through would schedule the legacy cancel task, which {@code handle()}
     * refuses.
     *
     * @return always {@code true}
     */
    public static boolean cancelTask(final int taskId) {
        cancel(taskId);
        return true;
    }

    /**
     * {@code CraftScheduler#cancelTasks} (Paper feature patches 0003, 0009): cancels every bridged task of
     * the plugin, and always tells the caller to stop. A plugin the bridge does not handle has no task in the
     * Bukkit scheduler on a region-threaded server ({@code handle()} refuses it), so there is nothing to
     * cancel; falling through would schedule the legacy cancel task, which {@code handle()} refuses, and every
     * native plugin's disable then logged "Unsupported in region threading".
     *
     * @return always {@code true}
     */
    public static boolean cancelTasks(final Plugin plugin) {
        cancelAll(plugin);
        return true;
    }

    /**
     * {@code CraftScheduler#isCurrentlyRunning} (Paper feature patches 0003, 0008): {@code TRUE} while a
     * bridged body runs (a cancelled async body stays running until its last invocation finishes),
     * {@code FALSE} for a task the bridge holds that is not running, {@code null} when the bridge does not
     * know the id (the caller falls through to Paper). Never creates the runtime.
     */
    public static Boolean currentlyRunning(final int taskId) {
        return currentlyRunning(runtime, taskId);
    }

    static Boolean currentlyRunning(final BridgeRuntime current, final int taskId) {
        if (current == null) return null;
        if (current.running(taskId)) return Boolean.TRUE;
        if (current.knows(taskId)) return Boolean.FALSE;
        return null;
    }

    /**
     * {@code CraftScheduler#nextId} (Paper feature patch 0008): an id the bridge still holds, or one whose
     * body is still draining after cancellation, must not be handed out again. Never creates the runtime.
     */
    public static boolean idInUse(final int taskId) {
        final BridgeRuntime current = runtime;
        return current != null && (current.knows(taskId) || current.running(taskId));
    }

    /**
     * {@code CraftScheduler#getActiveWorkers} on the main scheduler (Paper feature patch 0008): the async
     * scheduler's workers plus the bridge's actual workers on the governed bridge I/O lane, each once.
     *
     * @return a new mutable list, as Paper returns
     */
    public static List<org.bukkit.scheduler.BukkitWorker> withBridgedWorkers(
            final List<org.bukkit.scheduler.BukkitWorker> asyncWorkers) {
        final List<org.bukkit.scheduler.BukkitWorker> workers = new java.util.ArrayList<>(asyncWorkers);
        workers.addAll(activeWorkers());
        return workers;
    }

    /**
     * {@code CraftScheduler#getPendingTasks} on the main scheduler (Paper feature patch 0003): the bridged
     * tasks still scheduled, as the scheduler's own task objects. Never creates the runtime.
     */
    public static List<org.bukkit.scheduler.BukkitTask> pendingBukkitTasks() {
        final List<Object> handles = pendingTasks();
        final List<org.bukkit.scheduler.BukkitTask> tasks = new java.util.ArrayList<>(handles.size());
        for (final Object handle : handles) tasks.add((org.bukkit.scheduler.BukkitTask)handle);
        return tasks;
    }

    /**
     * {@code CraftScheduler#handle} (Paper feature patch 0003): schedules a bridged legacy plugin's task
     * through the bridge instead of refusing it.
     *
     * @return {@code true} when the bridge took the task; {@code false} for a plugin the bridge does not
     *     handle (the caller refuses it as before)
     */
    public static boolean submitIfBridged(final Plugin plugin, final BridgeRuntime.Task task, final long delayTicks) {
        if (!handles(plugin)) return false;
        submit(plugin, task, delayTicks);
        return true;
    }

    /**
     * {@code CraftScheduler}'s bridge adapter, {@code run()} (Paper feature patch 0003). {@code CraftAsyncTask#run}
     * swallows and logs exceptions and keeps worker lists the bridge does not use, so the plugin's body runs
     * directly and violations reach the bridge. A task that must run itself ({@code CraftFuture}: its
     * {@code run()} completes the future), or one without a body, runs itself.
     *
     * @param task the scheduler's task object
     * @param runnable its Runnable body, or {@code null}
     * @param consumer its Consumer body, or {@code null}
     * @param runsItself whether the task must run itself
     */
    public static <T extends org.bukkit.scheduler.BukkitTask & Runnable> void runBukkitTask(final T task,
            final Runnable runnable, final java.util.function.Consumer<org.bukkit.scheduler.BukkitTask> consumer,
            final boolean runsItself) {
        if (runsItself || (runnable == null && consumer == null)) {
            task.run();
        } else if (runnable != null) {
            runnable.run();
        } else {
            consumer.accept(task);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Scheduling-context hooks (Paper feature patch 0026): the player a server frame acts for, so a
    // bridged legacy sync task scheduled inside it is routed to that player's owner. Logic in
    // SchedulingContext/EventAnchors; each site is one call. Never throw into the caller.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code PaperEventManager#callEvent}, before the listener loop: publishes the player the event is about
     * (players only, see {@link EventAnchors}) for the listeners' duration. An event about a leaving or
     * not-yet-placed player publishes no anchor at all, hiding the frame it was fired in (a kick inside another
     * player's command); an event about no player keeps that frame (an async body carrying a player's context
     * firing a plugin's own event). Returns the token {@link #exitContext} takes; {@code null} while nothing is
     * bridged, so the hot path costs one volatile read.
     */
    public static Object enterEventContext(final org.bukkit.event.Event event) {
        if (!contextActive) return null;
        try {
            final Object anchor = EventAnchors.anchor(event);
            return anchor == EventAnchors.CLEAR ? SchedulingContext.clear() : SchedulingContext.enter(anchor);
        } catch (final RuntimeException unexpected) {
            return null;
        }
    }

    /** Closes a scope opened by {@link #enterEventContext}; {@code null} is a no-op. */
    public static void exitContext(final Object scope) {
        if (scope instanceof SchedulingContext.Scope opened) opened.close();
    }

    /**
     * {@code BukkitCommandNode.BukkitBrigCommand#run}: executes a Bukkit command with its player sender
     * published as the scheduling context (a console, block or proxied sender publishes nothing). Player
     * commands execute on the player's region, so this adds "follows the player" to the caller-region route
     * and carries the player into the command's async continuations.
     */
    public static void withCommandContext(final org.bukkit.command.CommandSender sender, final Runnable execute) {
        if (!contextActive || !(sender instanceof org.bukkit.entity.Player player)) {
            execute.run();
            return;
        }
        try (SchedulingContext.Scope scope = SchedulingContext.enter(player)) {
            execute.run();
        }
    }

    /** Test seam: whether the context hooks are live (set by the first admission). */
    static boolean contextActive() {
        return contextActive;
    }

    /** Test seam: installs a runtime (not started through the TOML files) until closed. Not for production code. */
    static synchronized AutoCloseable installRuntimeForTesting(final BridgeRuntime installed) {
        resetRuntime();
        runtime = installed;
        contextActive = true;
        return AuroraBridge::resetRuntime;
    }

    /** GlobalRegionScheduler cancellation also releases the bridge's task indexes. */
    public static void cancelGlobal(final Plugin plugin) {
        final BridgeRuntime current = runtime;
        if (plugin != null && current != null) current.cancelGlobal(plugin.getName());
    }

    /**
     * For {@code FoliaAsyncScheduler#cancelTasks}, the counterpart of {@link #cancelGlobal}: a plugin cancelling its
     * Folia async tasks directly also cancels the timers behind its bridged async tasks, so their index entries
     * are released (cancelled, never a violation) instead of counting as pending until disable. Not wired yet:
     * the call site needs a Paper feature patch made from the materialized sources. Never creates the runtime.
     */
    public static void cancelAsync(final Plugin plugin) {
        final BridgeRuntime current = runtime;
        if (plugin != null && current != null) current.cancelAsync(plugin.getName());
    }

    /**
     * Releases bridged tasks anchored in a world whose regions were just drained for unload;
     * see {@link BridgeRuntime#worldUnloaded}. Never creates the runtime.
     */
    public static int worldUnloaded(final Object world) {
        final BridgeRuntime current = runtime;
        return current == null ? 0 : current.worldUnloaded(world);
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

    /**
     * Test seam: forgets the lazily started runtime and the early settings read with it, and does so again
     * when closed. The runtime is process-wide and otherwise stays started for the rest of the JVM, so a
     * test that starts it (rendering {@code /perf plugins}) makes every later "not started" assertion
     * depend on test order. Not for production code.
     */
    public static synchronized AutoCloseable resetRuntimeForTesting() {
        resetRuntime();
        return AuroraBridge::resetRuntime;
    }

    private static synchronized void resetRuntime() {
        runtime = null;
        contextActive = false;
        earlySettings = null;
        earlyQuarantineAfter = AuroraConfig.Bridge.DEFAULT.quarantineAfter();
        earlySyncRoute = AuroraConfig.Bridge.DEFAULT.syncRoute();
        earlyMaxPendingTasks = AuroraConfig.Bridge.DEFAULT.maxPendingTasksPerPlugin();
        earlyMaxRunningAsyncTasks = AuroraConfig.Bridge.DEFAULT.maxRunningAsyncTasksPerPlugin();
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
            // A repeating run whose own body removed the entity: Folia retires the scheduler while this task is
            // running, not queued, so retire() does not call its retired callback, and the reschedule after the
            // body fails and drops the task silently. Checked on the owner thread right after the body, before
            // that reschedule; isPurged() is the scheduler's retired state.
            final Runnable repeatingBody = () -> {
                body.run();
                if (entity instanceof org.bukkit.craftbukkit.entity.CraftEntity craft && craft.isPurged()) retired.run();
            };
            final var task = periodTicks > 0
                ? scheduler.runAtFixedRate(plugin, t -> repeatingBody.run(), retired, Math.max(1L, delayTicks), periodTicks)
                : delayTicks > 0 ? scheduler.runDelayed(plugin, t -> body.run(), retired, delayTicks)
                : scheduler.run(plugin, t -> body.run(), retired);
            if (task == null) {
                // Folia returns null without calling retired when the entity's scheduler is retired, which
                // happens only when the entity has been removed for good. A retired scheduler on an entity
                // that is still valid is an anomaly worth one line (it would route a player's task elsewhere).
                if (entity.isValid()) {
                    SourbyLogger.warn("Aurora Bridge: the entity scheduler of " + entity + " refused a task for "
                        + plugin.getName() + " although the entity is still valid (scheduler retired; thread "
                        + Thread.currentThread().getName() + ")");
                }
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
            return async(owner, body, delayTicks, periodTicks, rejected, () -> true);
        }

        @Override
        public BridgeRuntime.Handle async(final Object owner, final Runnable body, final long delayTicks,
                                          final long periodTicks, final Runnable rejected,
                                          final java.util.function.BooleanSupplier claim) {
            final Plugin plugin = (Plugin)owner;
            final var scheduler = Bukkit.getAsyncScheduler();
            // Folia's async scheduler only times the work; the Resource Governor's bounded
            // BRIDGE_IO lane runs it, so legacy async tasks cannot grow into unbounded threads.
            // The claim runs on the timer thread before the hand-off, like the rejection report.
            final Runnable governed = () -> {
                if (!claim.getAsBoolean()) return;
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
