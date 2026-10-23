package dev.iyanz.sourbycraft.config;

import dev.iyanz.sourbycraft.awf.AwfSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * Every key of {@code sourbycraft_config/aurora.yml}: path, default, lifecycle and the comment the
 * seeder writes beside it. The paths did not change in Build 49 (they were already grouped under
 * {@code [aurora.<service>]}); what changed is that the file gets a header and section comments, and
 * that Aurora keys an older deployment still kept in the utility TOML are moved here once
 * ({@link ConfigMigration}).
 *
 * <p>The defaults are the typed ones: {@link AuroraConfig#DEFAULT} and {@link AwfSettings#DEFAULT}.
 * {@code AuroraKeysTest} parses a file seeded from this table and asserts it equals them, so the
 * table cannot drift from the parsers.</p>
 */
public final class AuroraKeys {

    private AuroraKeys() {}

    /** The file this table describes, relative to the server directory. */
    public static final String FILE = "sourbycraft_config/aurora.yml";

    private static ConfigKey live(final String path, final Object defaultValue, final String comment, final String... legacy) {
        return ConfigKey.of(path, defaultValue, AuroraConfig.Lifecycle.LIVE, comment, legacy);
    }

    private static ConfigKey restart(final String path, final Object defaultValue, final String comment) {
        return ConfigKey.of(path, defaultValue, AuroraConfig.Lifecycle.RESTART_REQUIRED, comment);
    }

    // [aurora.entity]
    public static final ConfigKey ASYNC_PATHFINDING = live(AuroraConfig.ASYNC_PATH_KEY, AuroraConfig.DEFAULT.entity().asyncPathfinding(),
        "Aurora async pathfinding. Experimental and default-off; requires region/snapshot qualification. Disabling stops "
            + "new navigations from choosing async solves; admitted work finishes through the owning-entity handoff.",
        AuroraConfig.LEGACY_ASYNC_PATH_KEY);

    // [aurora.diagnostics]
    public static final ConfigKey LANE_SAMPLING = live(AuroraConfig.LANE_SAMPLING_KEY, AuroraConfig.DEFAULT.diagnostics().laneSampling(),
        "Execution-lane CPU attribution behind /perf lanes: walks every thread once a second. The cost depends on the "
            + "workload; false stops the sampling and the lanes view reports it as disabled.");
    public static final ConfigKey CHUNK_GENERATION_METRICS = restart(AuroraConfig.CHUNK_GENERATION_METRICS_KEY,
        AuroraConfig.DEFAULT.diagnostics().chunkGenerationMetrics(),
        "Generic chunk-generation stage timing behind /perf chunks. Read once at class init, before the configuration "
            + "loads; -Dsourbycraft.chunk-generation-metrics.enabled=true also turns it on. Times every non-empty stage, "
            + "so it is off by default.");

    // [aurora.diagnostics.stall-recorder]
    public static final ConfigKey STALL_RECORDER_ENABLED = restart(AuroraConfig.STALL_RECORDER_ENABLED_KEY,
        AuroraConfig.StallRecorder.DEFAULT.enabled(),
        "Stall recorder (the five keys of this table share the lifecycle: the thread and its shutdown hook are created "
            + "once at boot). When the global tick or a region has completed no tick for after-seconds, a daemon thread "
            + "writes thread dumps (with lock owners) and memory figures (heap, RSS, container cgroup) to "
            + "logs/stalls/stall-<time>.txt, so a server without a shell leaves evidence. false starts no thread and "
            + "registers no hook. docs/development/STALL-RECORDER.md.");
    public static final ConfigKey STALL_RECORDER_AFTER = restart(AuroraConfig.STALL_RECORDER_AFTER_KEY,
        AuroraConfig.StallRecorder.DEFAULT.afterSeconds(), "Seconds without a completed tick before the first dump. 1-3600.");
    public static final ConfigKey STALL_RECORDER_REPEAT = restart(AuroraConfig.STALL_RECORDER_REPEAT_KEY,
        AuroraConfig.StallRecorder.DEFAULT.repeatSeconds(), "Seconds between dumps while the same stall lasts. 1-3600.");
    public static final ConfigKey STALL_RECORDER_MAX_DUMPS = restart(AuroraConfig.STALL_RECORDER_MAX_DUMPS_KEY,
        AuroraConfig.StallRecorder.DEFAULT.maxDumpsPerStall(),
        "Dumps written per stall. 1-100. One more is written if the JVM shuts down during the stall.");
    public static final ConfigKey STALL_RECORDER_KEEP_FILES = restart(AuroraConfig.STALL_RECORDER_KEEP_FILES_KEY,
        AuroraConfig.StallRecorder.DEFAULT.keepFiles(), "Newest stall files kept in logs/stalls/. 1-1000; older ones are deleted.");

    // [aurora.cpu]
    public static final ConfigKey CPU_CORES = restart(AuroraConfig.CPU_CORES_KEY, AuroraConfig.DEFAULT.cpu().cores(),
        "Processors Aurora may use in total, cores and hardware threads alike. 0 = every available processor. Counted "
            + "as the JVM counts them, so a container CPU quota is respected rather than the physical socket. A budget, "
            + "not a reservation: a region ticks on one thread, so threads past the number of separate active regions "
            + "idle. An explicit threaded-regions.threads in config/paper-global.yml still wins. Read before the "
            + "configuration loads (region scheduler sizing).");

    // [aurora.bridge]
    public static final ConfigKey BRIDGE_MODE = restart(AuroraConfig.BRIDGE_MODE_KEY, "off",
        "Aurora Compatibility Bridge. off = plugins without folia-supported/canvas-supported are refused, as the base "
            + "does. safe = they load, their Bukkit scheduler tasks are routed through the bridge (sync -> sync-route, "
            + "async -> the governed bridge I/O lane) and failures are recorded. Read before the plugin scan. Legacy "
            + "plugins remain unqualified code on a region-threaded server.");
    public static final ConfigKey BRIDGE_QUARANTINE = live(AuroraConfig.BRIDGE_QUARANTINE_KEY, AuroraConfig.Bridge.DEFAULT.quarantineAfter(),
        "Fatal bridge violations after which a bridged plugin is quarantined: its bridged tasks are cancelled and new ones "
            + "rejected. At least 1.");
    public static final ConfigKey BRIDGE_SYNC_ROUTE = live(AuroraConfig.BRIDGE_SYNC_ROUTE_KEY, "caller-region",
        "Where a bridged plugin's Bukkit sync task runs. caller-region = on the region that was ticking when it was "
            + "scheduled (from a command or event), else the global region. global = always the global region, where "
            + "world access is refused. Ownership checks apply either way.");
    public static final ConfigKey BRIDGE_THREAD_CHECKS = restart(AuroraConfig.BRIDGE_THREAD_CHECKS_KEY,
        AuroraConfig.Bridge.DEFAULT.threadChecks().configValue(),
        "What a failed region-ownership check does while mode = safe (with the bridge off the checks are always "
            + "enforced). relaxed = never throws, for every caller (LightingLuminol parity; owner decision 2026-10-22 "
            + "\"Tiru penuh LightingLuminol\"); bridged = relaxed only inside a bridged plugin's task body; enforced = "
            + "throws, as before. relaxed and bridged are UNSAFE: data races, chunk corruption persisted to disk, item "
            + "duplication and deadlocks are possible, and the server is not qualified. Read once when the checks first "
            + "run; -Daurora.bridge.thread-checks= overrides this file.");
    public static final ConfigKey BRIDGE_MAX_PENDING = live(AuroraConfig.BRIDGE_MAX_PENDING_KEY,
        AuroraConfig.Bridge.DEFAULT.maxPendingTasksPerPlugin(),
        "Maximum pending bridged tasks per plugin. 0 = unlimited, otherwise 1..100000. New submissions above the limit "
            + "are cancelled and diagnosed; existing tasks drain normally.");
    public static final ConfigKey BRIDGE_MAX_RUNNING_ASYNC = live(AuroraConfig.BRIDGE_MAX_RUNNING_ASYNC_KEY,
        AuroraConfig.Bridge.DEFAULT.maxRunningAsyncTasksPerPlugin(),
        "Maximum simultaneously running bridged async callbacks per plugin. 0 = unlimited, otherwise 1..100000. "
            + "Over-limit callbacks cancel their task/timer and are diagnosed; running bodies drain normally. Rejection, "
            + "not a queue or a fairness guarantee.");

    // [aurora.scheduler]
    public static final ConfigKey BRIDGE_IO_THREADS = restart(AuroraConfig.BRIDGE_IO_THREADS_KEY, AuroraConfig.Scheduler.DEFAULT.bridgeIoThreads(),
        "Resource Governor: threads for bridged plugins' async tasks. 0 = max(2, processors / 4). A lane is created once "
            + "and never resized.");
    public static final ConfigKey BRIDGE_IO_QUEUE = restart(AuroraConfig.BRIDGE_IO_QUEUE_KEY, AuroraConfig.Scheduler.DEFAULT.bridgeIoQueue(),
        "Resource Governor: queued bridged async tasks before new ones are rejected. At least 1.");
    public static final ConfigKey STORAGE_THREADS = restart(AuroraConfig.STORAGE_THREADS_KEY, AuroraConfig.Scheduler.DEFAULT.storageThreads(),
        "Resource Governor: threads for Aurora World Fabric commits. 0 = 1.");
    public static final ConfigKey STORAGE_QUEUE = restart(AuroraConfig.STORAGE_QUEUE_KEY, AuroraConfig.Scheduler.DEFAULT.storageQueue(),
        "Resource Governor: queued AWF commits before new ones are rejected. At least 1.");

    // [aurora.network]
    public static final ConfigKey NETWORK_COUNTERS = live(AuroraConfig.NETWORK_COUNTERS_KEY, AuroraConfig.Network.DEFAULT.counters(),
        "Count wire bytes and packets per direction for /perf network. The per-packet cost is not measured; false stops "
            + "counting.");

    // [aurora.world]
    public static final ConfigKey COMMAND_BLOCKS = live(AuroraConfig.COMMAND_BLOCKS_KEY, AuroraConfig.World.DEFAULT.commandBlocks(),
        "true runs command blocks and command-block minecarts under SourbyCraft's region rules "
            + "(docs/architecture/command-blocks.md); the vanilla gamerule command_blocks_work must also be true. false "
            + "keeps the inherited hard-disable. A repeating block that stopped while this was false needs re-arming "
            + "(redstone change, re-placement, GUI save or chunk reload).");
    public static final ConfigKey TICK_FUNCTIONS = live(AuroraConfig.TICK_FUNCTIONS_KEY, AuroraConfig.World.DEFAULT.tickFunctions(),
        "true runs the datapack tag #minecraft:tick once per server tick on the global tick "
            + "(docs/architecture/datapack-functions.md). #minecraft:load runs once per boot either way.");

    // [aurora.awf]
    public static final ConfigKey AWF_WORLDS = restart(AwfSettings.WORLDS_KEY, List.of(),
        "Aurora World Fabric: world folder names whose chunk, entity and POI data AWF stores. Empty = none. Existing "
            + "region files stay the read-only base; chunks written afterwards go to <folder>.awf beside each region "
            + "folder. Writes are durable only after a commit (every commit-interval-seconds, on save-all flush and on "
            + "shutdown): a crash loses the last interval. Once a store exists it stays in use even if the world is "
            + "removed from this list; use export to leave AWF. Read when the engine opens the world.");
    public static final ConfigKey AWF_EXPORT = restart(AwfSettings.EXPORT_KEY, List.of(),
        "World folder names to move back to region files at the next load: every chunk and deletion in the store is "
            + "written to .mca, then the store is renamed to <folder>.awf.exported-<time> (kept, not deleted). A world "
            + "must not also be in worlds.");
    public static final ConfigKey AWF_BACKEND = restart(AwfSettings.BACKEND_KEY, AwfSettings.DEFAULT.backend(),
        "file = a directory beside each region folder, the only backend shipped. Another name selects a backend "
            + "registered by server-side code before worlds load; if it is not registered, listed worlds fail to load "
            + "rather than falling back.");
    public static final ConfigKey AWF_PERSISTENCE = restart(AwfSettings.PERSISTENCE_KEY, "incremental",
        "Commit mode: incremental (new objects only), checkpoint (also re-verifies every referenced object), full "
            + "(rewrites every object).");
    public static final ConfigKey AWF_COMMIT_INTERVAL = restart(AwfSettings.COMMIT_INTERVAL_KEY, AwfSettings.DEFAULT.commitIntervalSeconds(),
        "Seconds a written chunk may wait in memory before a commit starts. 1-86400.");
    public static final ConfigKey AWF_RESIDENT_CHUNKS = restart(AwfSettings.RESIDENT_CHUNKS_KEY, AwfSettings.DEFAULT.residentChunks(),
        "Committed chunks kept in memory per storage. Unsaved chunks are never dropped by this limit.");
    public static final ConfigKey AWF_RETAINED_GENERATIONS = restart(AwfSettings.RETAINED_GENERATIONS_KEY,
        AwfSettings.DEFAULT.retainedGenerations(), "Committed generations kept for recovery.");
    public static final ConfigKey AWF_COMMIT_ATTEMPTS = restart(AwfSettings.COMMIT_ATTEMPTS_KEY, AwfSettings.DEFAULT.commitAttempts(),
        "Attempts per commit before it fails and its chunks stay in memory for the next.");
    public static final ConfigKey AWF_DELTA_GENERATIONS = restart(AwfSettings.DELTA_GENERATIONS_KEY, AwfSettings.DEFAULT.deltaGenerations(),
        "FILE backend: write each commit as a delta of the chunk index chained to the previous generation, compacted "
            + "to a full index periodically. false = every commit writes a full index, which builds before AWF-7 (and "
            + "the auto-updater's rollback jar) can read. Once deltas have been written, rolling back to such a build "
            + "needs false here and a restart (a writable open then compacts every store the server opens), or "
            + "/awf compact all, which also prints full: yes/no per store. Such a build then skips the retained delta "
            + "generations and deletes the objects only they reference: those rollback points are lost for good, so back "
            + "up the .awf folders first if they matter.");
    public static final ConfigKey AWF_COMMIT_DIRTY_BYTES = restart(AwfSettings.COMMIT_DIRTY_BYTES_KEY, AwfSettings.DEFAULT.commitDirtyBytes(),
        "Start a commit as soon as a storage's unsaved chunks hold at least this many bytes, without waiting for "
            + "commit-interval-seconds. 0 = off (interval only). A trigger, not a hard cap: the check runs once a second "
            + "and one commit per storage runs at a time. 0-2147483647.");
    public static final ConfigKey AWF_SHUTDOWN_DEADLINE = restart(AwfSettings.SHUTDOWN_DEADLINE_KEY,
        AwfSettings.DEFAULT_SHUTDOWN_DEADLINE_MILLIS,
        "One budget in milliseconds for every AWF wait at shutdown (flushes, final commits, lock waits, backend calls, "
            + "lease release), from the start of shutdown. When it passes no further commit is attempted, open Redis "
            + "connections are cut, and the shutdown is logged as AWF-SHUTDOWN-INCOMPLETE with every unconfirmed "
            + "storage (exit status 70), never as saved. 1-2147483647.");

    /** Every key, in file order. */
    public static final List<ConfigKey> KEYS = List.of(
        ASYNC_PATHFINDING,
        LANE_SAMPLING, CHUNK_GENERATION_METRICS,
        STALL_RECORDER_ENABLED, STALL_RECORDER_AFTER, STALL_RECORDER_REPEAT, STALL_RECORDER_MAX_DUMPS, STALL_RECORDER_KEEP_FILES,
        CPU_CORES,
        BRIDGE_MODE, BRIDGE_QUARANTINE, BRIDGE_SYNC_ROUTE, BRIDGE_THREAD_CHECKS, BRIDGE_MAX_PENDING, BRIDGE_MAX_RUNNING_ASYNC,
        BRIDGE_IO_THREADS, BRIDGE_IO_QUEUE, STORAGE_THREADS, STORAGE_QUEUE,
        NETWORK_COUNTERS,
        COMMAND_BLOCKS, TICK_FUNCTIONS,
        AWF_WORLDS, AWF_EXPORT, AWF_BACKEND, AWF_PERSISTENCE, AWF_COMMIT_INTERVAL, AWF_RESIDENT_CHUNKS,
        AWF_RETAINED_GENERATIONS, AWF_COMMIT_ATTEMPTS, AWF_DELTA_GENERATIONS, AWF_COMMIT_DIRTY_BYTES, AWF_SHUTDOWN_DEADLINE);

    /** The file header, written as the comment of the {@code [aurora]} table. */
    public static final String HEADER = String.join("\n",
        "Aurora services (sourbycraft_config/aurora.yml) - Build 49 layout.",
        "",
        "Settings of the services SourbyCraft runs around the region engine: entity work, diagnostics, the CPU",
        "budget, the plugin compatibility bridge, Resource Governor lane budgets, network counters, world",
        "behaviour and Aurora World Fabric (AWF). Every key states its lifecycle and default: LIVE =",
        "/sourbycraft reload applies it; RESTART_REQUIRED = read once at startup. Some keys are read straight",
        "from this file before the configuration loads (cpu, bridge mode, AWF, chunk-generation metrics).",
        "",
        "Not in this file: the region engine (config/aurora-server.yml, config/aurora-worlds.yml), console,",
        "players, messages, plugin provisioning and the updater (sourbycraft_config/sourbycraft_global_config.yml),",
        "network limits (sourbycraft-security.yml). Reference: docs/AURORA-CONFIG.md.",
        "",
        "Build 49 moved any Aurora key still kept in sourbycraft_global_config.yml into this file once.",
        "");

    /** Table comments, in file order. */
    public static final List<UtilityKeys.Section> SECTIONS = List.of(
        new UtilityKeys.Section("aurora", HEADER),
        new UtilityKeys.Section("aurora.entity", "Entity work."),
        new UtilityKeys.Section("aurora.diagnostics", "Measuring the server (/perf)."),
        new UtilityKeys.Section("aurora.diagnostics.stall-recorder", "In-JVM stall recorder: evidence for a hung tick on hosts with no shell."),
        new UtilityKeys.Section("aurora.cpu", "Processor budget. Precedence: threaded-regions.threads in paper-global.yml, then cores, then every processor."),
        new UtilityKeys.Section("aurora.bridge", "Aurora Compatibility Bridge for plugins without region-threading support (docs/architecture/aurora-plugin-bridge.md)."),
        new UtilityKeys.Section("aurora.scheduler", "Resource Governor lane budgets (docs/architecture/aurora-resource-governor.md). 0 threads = the lane's documented default."),
        new UtilityKeys.Section("aurora.network", "Network measurement."),
        new UtilityKeys.Section("aurora.world", "World behaviour SourbyCraft owns instead of inheriting from the region-threading base."),
        new UtilityKeys.Section("aurora.awf", "Aurora World Fabric (docs/architecture/aurora-world-fabric.md). All keys RESTART_REQUIRED; empty lists leave worlds on region files."));

    /** The current paths, in file order. */
    public static List<String> paths() {
        final List<String> paths = new ArrayList<>(KEYS.size());
        for (final ConfigKey key : KEYS) paths.add(key.path());
        return List.copyOf(paths);
    }

    /** The key at {@code path}, or {@code null}. */
    public static ConfigKey byPath(final String path) {
        for (final ConfigKey key : KEYS) {
            if (key.path().equals(path)) return key;
        }
        return null;
    }

    /** Whether a flattened path belongs in {@code aurora.toml}: the {@code aurora.} namespace or a renamed Aurora key. */
    public static boolean owns(final String path) {
        if (path.startsWith("aurora.")) return true;
        for (final ConfigKey key : KEYS) {
            if (key.legacyPaths().contains(path)) return true;
        }
        return false;
    }

    /** Where a flattened path lands in {@code aurora.toml}: itself, or the current name of a renamed key. */
    public static String currentPath(final String path) {
        for (final ConfigKey key : KEYS) {
            if (key.legacyPaths().contains(path)) return key.path();
        }
        return path;
    }
}
