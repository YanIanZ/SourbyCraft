package dev.iyanz.sourbycraft.config;

import java.util.List;
import java.util.Objects;

/** Typed, immutable settings for implemented Aurora behavior only. Parsing never writes config. */
public record AuroraConfig(Entity entity, Diagnostics diagnostics, Cpu cpu, Bridge bridge, Scheduler scheduler,
                           Network network, World world) {
    public static final String ASYNC_PATH_KEY = "aurora.entity.async-pathfinding";
    public static final String LEGACY_ASYNC_PATH_KEY = "perf.ai.async-pathfinding";
    public static final String LANE_SAMPLING_KEY = "aurora.diagnostics.lane-sampling";
    public static final String CHUNK_GENERATION_METRICS_KEY = "aurora.diagnostics.chunk-generation-metrics";
    public static final String STALL_RECORDER_ENABLED_KEY = "aurora.diagnostics.stall-recorder.enabled";
    public static final String STALL_RECORDER_AFTER_KEY = "aurora.diagnostics.stall-recorder.after-seconds";
    public static final String STALL_RECORDER_REPEAT_KEY = "aurora.diagnostics.stall-recorder.repeat-seconds";
    public static final String STALL_RECORDER_MAX_DUMPS_KEY = "aurora.diagnostics.stall-recorder.max-dumps-per-stall";
    public static final String STALL_RECORDER_KEEP_FILES_KEY = "aurora.diagnostics.stall-recorder.keep-files";
    public static final String CPU_CORES_KEY = "aurora.cpu.cores";
    public static final String BRIDGE_MODE_KEY = "aurora.bridge.mode";
    public static final String BRIDGE_QUARANTINE_KEY = "aurora.bridge.quarantine-after";
    public static final String BRIDGE_SYNC_ROUTE_KEY = "aurora.bridge.sync-route";
    public static final String BRIDGE_MAX_PENDING_KEY = "aurora.bridge.max-pending-tasks-per-plugin";
    public static final String BRIDGE_MAX_RUNNING_ASYNC_KEY = "aurora.bridge.max-running-async-tasks-per-plugin";
    public static final String BRIDGE_IO_THREADS_KEY = "aurora.scheduler.bridge-io-threads";
    public static final String BRIDGE_IO_QUEUE_KEY = "aurora.scheduler.bridge-io-queue";
    public static final String STORAGE_THREADS_KEY = "aurora.scheduler.storage-threads";
    public static final String STORAGE_QUEUE_KEY = "aurora.scheduler.storage-queue";
    public static final String NETWORK_COUNTERS_KEY = "aurora.network.counters";
    public static final String COMMAND_BLOCKS_KEY = "aurora.world.command-blocks";
    public static final String TICK_FUNCTIONS_KEY = "aurora.world.tick-functions";
    public static final AuroraConfig DEFAULT =
        new AuroraConfig(new Entity(false), new Diagnostics(true), new Cpu(Cpu.AUTO), Bridge.DEFAULT,
            Scheduler.DEFAULT, Network.DEFAULT, World.DEFAULT);
    public static final Setting ASYNC_PATH = new Setting(ASYNC_PATH_KEY, Lifecycle.LIVE);
    public static final Setting LANE_SAMPLING = new Setting(LANE_SAMPLING_KEY, Lifecycle.LIVE);
    // ChunkGenerationMetrics.ENABLED is a static final read once at class init, which the chunk
    // system triggers before configuration loads; a reload cannot reach it.
    public static final Setting CHUNK_GENERATION_METRICS =
        new Setting(CHUNK_GENERATION_METRICS_KEY, Lifecycle.RESTART_REQUIRED);
    // The recorder thread and its shutdown hook are created once, by SourbyCraftBootstrap, from the settings
    // loaded at that moment; a reload republishes AuroraConfig but never reaches the running recorder.
    public static final Setting STALL_RECORDER = new Setting("aurora.diagnostics.stall-recorder.*", Lifecycle.RESTART_REQUIRED);
    // The region scheduler is sized during GlobalConfiguration load, long before a reload can
    // reach it, so this cannot honestly be advertised as live.
    public static final Setting CPU_CORES = new Setting(CPU_CORES_KEY, Lifecycle.RESTART_REQUIRED);
    // Plugins are admitted or refused once, at load; a reload cannot un-load or re-load them.
    public static final Setting BRIDGE_MODE = new Setting(BRIDGE_MODE_KEY, Lifecycle.RESTART_REQUIRED);
    public static final Setting BRIDGE_QUARANTINE = new Setting(BRIDGE_QUARANTINE_KEY, Lifecycle.LIVE);
    // Read when each task is scheduled.
    public static final Setting BRIDGE_SYNC_ROUTE = new Setting(BRIDGE_SYNC_ROUTE_KEY, Lifecycle.LIVE);
    public static final Setting BRIDGE_MAX_PENDING = new Setting(BRIDGE_MAX_PENDING_KEY, Lifecycle.LIVE);
    public static final Setting BRIDGE_MAX_RUNNING_ASYNC = new Setting(BRIDGE_MAX_RUNNING_ASYNC_KEY, Lifecycle.LIVE);
    // Governed lanes are created once with their budget and never resized.
    public static final Setting SCHEDULER_BUDGETS = new Setting("aurora.scheduler.*", Lifecycle.RESTART_REQUIRED);
    public static final Setting NETWORK_COUNTERS = new Setting(NETWORK_COUNTERS_KEY, Lifecycle.LIVE);
    // Read on every command-block tick, interaction and edit packet. A repeating block whose tick
    // was dropped while the switch was off stays idle until something re-arms it (see World).
    public static final Setting COMMAND_BLOCKS = new Setting(COMMAND_BLOCKS_KEY, Lifecycle.LIVE);
    // Read on every global tick. Switching it on never re-runs #minecraft:load.
    public static final Setting TICK_FUNCTIONS = new Setting(TICK_FUNCTIONS_KEY, Lifecycle.LIVE);

    public AuroraConfig {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(cpu, "cpu");
        Objects.requireNonNull(bridge, "bridge");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(world, "world");
    }

    /** Settings without a world section, which then takes its defaults. */
    public AuroraConfig(final Entity entity, final Diagnostics diagnostics, final Cpu cpu, final Bridge bridge,
                        final Scheduler scheduler, final Network network) {
        this(entity, diagnostics, cpu, bridge, scheduler, network, World.DEFAULT);
    }

    /** Settings without bridge, scheduler or network sections, which then take their defaults. */
    public AuroraConfig(final Entity entity, final Diagnostics diagnostics, final Cpu cpu) {
        this(entity, diagnostics, cpu, Bridge.DEFAULT);
    }

    /** Settings without scheduler or network sections, which then take their defaults. */
    public AuroraConfig(final Entity entity, final Diagnostics diagnostics, final Cpu cpu, final Bridge bridge) {
        this(entity, diagnostics, cpu, bridge, Scheduler.DEFAULT, Network.DEFAULT);
    }

    /**
     * Resource Governor lane budgets ({@code docs/architecture/aurora-resource-governor.md}).
     * {@code 0} threads means the lane's documented default. RESTART_REQUIRED: a lane is created
     * once, on first use, and never resized.
     */
    public record Scheduler(int bridgeIoThreads, int bridgeIoQueue, int storageThreads, int storageQueue) {
        public static final int AUTO = 0;
        public static final Scheduler DEFAULT = new Scheduler(AUTO, 256, 1, 64);

        public Scheduler {
            if (bridgeIoThreads < 0 || storageThreads < 0 || bridgeIoQueue < 1 || storageQueue < 1) {
                throw new IllegalArgumentException("thread counts must be >= 0 and queues >= 1");
            }
        }

        /** Bridge I/O threads on this machine: the setting, or max(2, processors / 4). */
        public int resolvedBridgeIoThreads() {
            return this.bridgeIoThreads > 0 ? this.bridgeIoThreads
                : Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
        }

        public int resolvedStorageThreads() {
            return this.storageThreads > 0 ? this.storageThreads : 1;
        }
    }

    /**
     * World behaviour SourbyCraft owns instead of inheriting from the region-threading base.
     *
     * <p>{@code commandBlocks}: the base hard-disabled command blocks and command-block minecarts.
     * {@code true} runs them under SourbyCraft's region rules ({@code dev.iyanz.sourbycraft.commandblock},
     * {@code docs/architecture/command-blocks.md}): execute only on the region that owns the block
     * or minecart, entity selectors see only that region's entities, block targets outside it fail
     * with a message. Off by default until there is evidence beyond functional runs. LIVE, with one
     * caveat: a repeating block whose tick was dropped while off stays idle until re-armed (redstone
     * change, re-placement, GUI save or chunk reload).</p>
     *
     * <p>{@code tickFunctions}: run the datapack function tag {@code #minecraft:tick} once per server
     * tick on the global tick ({@code dev.iyanz.sourbycraft.world.DatapackFunctions},
     * {@code docs/architecture/datapack-functions.md}). Off by default: the base never ran it, and a
     * tick tag runs every tick with console rights. {@code #minecraft:load} does not depend on it.
     * LIVE (read every global tick).</p>
     */
    public record World(boolean commandBlocks, boolean tickFunctions) {
        public static final World DEFAULT = new World(false, false);

        /** Only the command-block switch; tick functions off. */
        public World(final boolean commandBlocks) {
            this(commandBlocks, false);
        }
    }

    /** Network measurement settings. {@code counters} stops the per-packet increments (LIVE). */
    public record Network(boolean counters) {
        public static final Network DEFAULT = new Network(true);
    }

    /**
     * Aurora Compatibility Bridge settings ({@code docs/architecture/aurora-plugin-bridge.md}).
     *
     * <p>{@link BridgeMode#OFF} is the default: the region-threading base keeps refusing plugins
     * that do not declare {@code folia-supported}/{@code canvas-supported}, exactly as before.
     * {@link BridgeMode#SAFE} admits them and runs their Bukkit scheduler tasks through the
     * bridge's routing rules. Off by default because correctness outranks compatibility in the
     * safety order: a legacy plugin is unqualified code on a region-threaded server until an
     * operator decides otherwise.</p>
     *
     * @param mode whether legacy plugins are admitted
     * @param quarantineAfter fatal violations after which a bridged plugin is quarantined
     * @param syncRoute where a legacy "sync" task runs
     * @param maxPendingTasksPerPlugin pending task limit; 0 preserves unlimited admission
     * @param maxRunningAsyncTasksPerPlugin running async callback limit; 0 preserves overlap
     */
    public record Bridge(BridgeMode mode, int quarantineAfter, SyncRoute syncRoute,
                         int maxPendingTasksPerPlugin, int maxRunningAsyncTasksPerPlugin) {
        public static final Bridge DEFAULT = new Bridge(BridgeMode.OFF, 3, SyncRoute.CALLER_REGION, 0, 0);

        /** Existing callers retain the unlimited task/async admission contract. */
        public Bridge(final BridgeMode mode, final int quarantineAfter, final SyncRoute syncRoute) {
            this(mode, quarantineAfter, syncRoute, 0, 0);
        }

        /** With the default sync route. */
        public Bridge(final BridgeMode mode, final int quarantineAfter) {
            this(mode, quarantineAfter, SyncRoute.CALLER_REGION);
        }

        public Bridge {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(syncRoute, "syncRoute");
            if (quarantineAfter < 1) {
                throw new IllegalArgumentException("quarantineAfter must be at least 1: " + quarantineAfter);
            }
            if (maxPendingTasksPerPlugin < 0 || maxPendingTasksPerPlugin > 100_000
                || maxRunningAsyncTasksPerPlugin < 0 || maxRunningAsyncTasksPerPlugin > 100_000) {
                throw new IllegalArgumentException("bridge task limits must be in 0..100000");
            }
        }
    }

    /**
     * Where a bridged plugin's Bukkit "sync" task runs.
     *
     * <p>Legacy code scheduled from a command or event usually goes on to touch the player or
     * blocks that caused it, which live in the caller's region. {@link #CALLER_REGION} runs the
     * task on the region that was ticking when it was scheduled, anchored on a chunk that region
     * owned; a task scheduled from anywhere else (the global region, async code, startup) still
     * goes to the global region. {@link #GLOBAL} sends every sync task to the global region, where
     * any world access is refused. Either way the engine's ownership checks decide what a task may
     * touch; the route only decides where it starts.</p>
     */
    public enum SyncRoute {
        CALLER_REGION,
        GLOBAL
    }

    /** Whether the bridge admits plugins that do not declare region-threading support. */
    public enum BridgeMode {
        /** Refuse them, as the base does. */
        OFF,
        /** Admit them; route scheduler work by ownership and reject what cannot be routed safely. */
        SAFE
    }

    public record Entity(boolean asyncPathfinding) {}

    /**
     * How many processors Aurora may use.
     *
     * <p>{@code cores = 8} means eight processors in total -- cores and hardware threads alike,
     * counted the way the JVM counts them, so a container CPU quota is respected rather than the
     * physical socket. It is a budget, not a reservation: a region ticks on exactly one thread,
     * so threads past the number of disconnected active regions simply idle.</p>
     *
     * <p>{@link #AUTO} (0) uses every available processor. An explicit
     * {@code threaded-regions.threads} in {@code paper-global.yml} still wins, because an
     * operator naming the region thread count directly is being more specific than a
     * machine-wide budget.</p>
     */
    public record Cpu(int cores) {
        /** Decide from the machine. */
        public static final int AUTO = 0;

        public Cpu {
            if (cores < 0) {
                throw new IllegalArgumentException("cores must not be negative: " + cores);
            }
        }

        /** The processor count this resolves to on the machine running it. */
        public int resolved() {
            final int available = Runtime.getRuntime().availableProcessors();
            return cores == AUTO ? available : Math.min(cores, available);
        }
    }

    /**
     * Settings for measuring the server.
     *
     * <p>{@code laneSampling} attributes per-thread CPU to execution lanes once a second. It
     * defaults on because it is what answers "where did the machine's time go", and it is cheap
     * under load — but it is not free: on an idle server the telemetry lane costs more than the
     * region lane, so a host running many idle worlds has a reason to turn it off.</p>
     */
    public record Diagnostics(boolean laneSampling, boolean chunkGenerationMetrics, StallRecorder stallRecorder) {
        public Diagnostics {
            Objects.requireNonNull(stallRecorder, "stallRecorder");
        }

        /** Without the stall-recorder section, which then takes its defaults. */
        public Diagnostics(final boolean laneSampling, final boolean chunkGenerationMetrics) {
            this(laneSampling, chunkGenerationMetrics, StallRecorder.DEFAULT);
        }

        /** Without the chunk-generation switch, which then stays off (its default). */
        public Diagnostics(final boolean laneSampling) {
            this(laneSampling, false);
        }
    }

    /**
     * The in-JVM stall recorder ({@code dev.iyanz.sourbycraft.diagnostics.StallRecorder}): a daemon thread
     * that, when the global tick or an active region has completed no tick for {@code afterSeconds}, writes
     * thread dumps with lock owners and memory figures to {@code logs/stalls/} so a hosted server (no shell,
     * no {@code jcmd}) leaves evidence even when the process is later killed. It reads only volatile tick
     * markers and runs on its own thread. RESTART_REQUIRED: the thread and its shutdown hook are created once
     * at boot.
     *
     * @param enabled {@code false} starts no thread and registers no hook
     * @param afterSeconds seconds without a completed tick before the first dump (1..3600)
     * @param repeatSeconds seconds between dumps while the same stall lasts (1..3600)
     * @param maxDumpsPerStall dumps written per stall, plus one final dump if the JVM shuts down during it (1..100)
     * @param keepFiles newest {@code stall-*.txt} files kept in the directory (1..1000)
     */
    public record StallRecorder(boolean enabled, int afterSeconds, int repeatSeconds, int maxDumpsPerStall,
                                int keepFiles) {
        public static final StallRecorder DEFAULT = new StallRecorder(true, 10, 10, 6, 20);

        public StallRecorder {
            if (afterSeconds < 1 || afterSeconds > 3600 || repeatSeconds < 1 || repeatSeconds > 3600
                || maxDumpsPerStall < 1 || maxDumpsPerStall > 100 || keepFiles < 1 || keepFiles > 1000) {
                throw new IllegalArgumentException("stall recorder bounds: after/repeat 1..3600, dumps 1..100, files 1..1000");
            }
        }
    }
    public enum Lifecycle { LIVE, RESTART_REQUIRED, IMMUTABLE_FOR_RUN }
    public record Setting(String key, Lifecycle lifecycle) {}

    public record Parsed(AuroraConfig config, List<String> invalidKeys, List<String> deprecatedKeys) {
        public Parsed {
            Objects.requireNonNull(config, "config");
            invalidKeys = List.copyOf(invalidKeys);
            deprecatedKeys = List.copyOf(deprecatedKeys);
        }
    }

    public static Parsed parse(final ConfigSnapshot snapshot) {
        final var values = snapshot.values();
        // Malformed namespace containers must not expose a legacy true underneath them.
        for (final String parent : List.of("aurora", "aurora.entity", "aurora.diagnostics",
                                          "aurora.diagnostics.stall-recorder", "aurora.cpu", "aurora.bridge", "aurora.scheduler",
                                          "aurora.network", "aurora.world")) {
            if (values.containsKey(parent)) return new Parsed(DEFAULT, List.of(parent), List.of());
        }
        final boolean modern = values.containsKey(ASYNC_PATH_KEY);
        final String pathKey = modern ? ASYNC_PATH_KEY : LEGACY_ASYNC_PATH_KEY;
        final Object pathValue = values.get(pathKey);
        final List<String> deprecated = !modern && pathValue != null ? List.of(pathKey) : List.of();

        final List<String> invalid = new java.util.ArrayList<>();
        boolean asyncPathfinding = DEFAULT.entity().asyncPathfinding();
        if (pathValue != null) {
            if (pathValue instanceof Boolean enabled) {
                asyncPathfinding = enabled;
            } else {
                invalid.add(pathKey);
            }
        }

        // An unreadable setting falls back to the default rather than to off: silently disabling
        // the thing that explains where CPU went is worse than ignoring a typo.
        boolean laneSampling = DEFAULT.diagnostics().laneSampling();
        final Object laneValue = values.get(LANE_SAMPLING_KEY);
        if (laneValue != null) {
            if (laneValue instanceof Boolean enabled) {
                laneSampling = enabled;
            } else {
                invalid.add(LANE_SAMPLING_KEY);
            }
        }

        // Off on a typo: it is an opt-in measurement with a per-stage cost, so an unreadable value
        // must not switch it on.
        boolean chunkGenerationMetrics = DEFAULT.diagnostics().chunkGenerationMetrics();
        final Object chunkValue = values.get(CHUNK_GENERATION_METRICS_KEY);
        if (chunkValue != null) {
            if (chunkValue instanceof Boolean enabled) {
                chunkGenerationMetrics = enabled;
            } else {
                invalid.add(CHUNK_GENERATION_METRICS_KEY);
            }
        }

        // The recorder falls back to its defaults on a typo: it is evidence-gathering that costs one
        // idle daemon thread, so an unreadable value must not switch it off, and each number is
        // bounded so a typo cannot ask for a dump every millisecond.
        boolean stallEnabled = StallRecorder.DEFAULT.enabled();
        final Object stallEnabledValue = values.get(STALL_RECORDER_ENABLED_KEY);
        if (stallEnabledValue != null) {
            if (stallEnabledValue instanceof Boolean enabled) {
                stallEnabled = enabled;
            } else {
                invalid.add(STALL_RECORDER_ENABLED_KEY);
            }
        }
        final int stallAfter = boundedInt(values, STALL_RECORDER_AFTER_KEY, StallRecorder.DEFAULT.afterSeconds(), 1, 3600, invalid);
        final int stallRepeat = boundedInt(values, STALL_RECORDER_REPEAT_KEY, StallRecorder.DEFAULT.repeatSeconds(), 1, 3600, invalid);
        final int stallMaxDumps = boundedInt(values, STALL_RECORDER_MAX_DUMPS_KEY, StallRecorder.DEFAULT.maxDumpsPerStall(), 1, 100, invalid);
        final int stallKeepFiles = boundedInt(values, STALL_RECORDER_KEEP_FILES_KEY, StallRecorder.DEFAULT.keepFiles(), 1, 1000, invalid);

        // A negative or non-integer budget is a typo, not an instruction. AUTO is the documented
        // default and the honest fallback: guessing a number here would silently cap a machine.
        int cores = DEFAULT.cpu().cores();
        final Object coresValue = values.get(CPU_CORES_KEY);
        if (coresValue != null) {
            if (coresValue instanceof Number number && number.intValue() >= 0
                && number.doubleValue() == Math.floor(number.doubleValue())) {
                cores = number.intValue();
            } else {
                invalid.add(CPU_CORES_KEY);
            }
        }

        BridgeMode bridgeMode = DEFAULT.bridge().mode();
        final Object modeValue = values.get(BRIDGE_MODE_KEY);
        if (modeValue != null) {
            if (modeValue instanceof String text && (text.equalsIgnoreCase("off") || text.equalsIgnoreCase("safe"))) {
                bridgeMode = BridgeMode.valueOf(text.toUpperCase(java.util.Locale.ROOT));
            } else {
                // Unknown mode falls back to OFF: admitting unqualified plugins on a typo is the
                // unsafe direction.
                invalid.add(BRIDGE_MODE_KEY);
            }
        }
        int quarantineAfter = DEFAULT.bridge().quarantineAfter();
        final Object quarantineValue = values.get(BRIDGE_QUARANTINE_KEY);
        if (quarantineValue != null) {
            if (quarantineValue instanceof Number number && number.intValue() >= 1
                && number.doubleValue() == Math.floor(number.doubleValue())) {
                quarantineAfter = number.intValue();
            } else {
                invalid.add(BRIDGE_QUARANTINE_KEY);
            }
        }
        SyncRoute syncRoute = DEFAULT.bridge().syncRoute();
        final Object routeValue = values.get(BRIDGE_SYNC_ROUTE_KEY);
        if (routeValue != null) {
            if (routeValue instanceof String text
                && (text.equalsIgnoreCase("caller-region") || text.equalsIgnoreCase("global"))) {
                syncRoute = text.equalsIgnoreCase("global") ? SyncRoute.GLOBAL : SyncRoute.CALLER_REGION;
            } else {
                invalid.add(BRIDGE_SYNC_ROUTE_KEY);
            }
        }

        final int maxPendingTasks = intSetting(values, BRIDGE_MAX_PENDING_KEY, 0, 0, invalid);
        final int maxRunningAsyncTasks = intSetting(values, BRIDGE_MAX_RUNNING_ASYNC_KEY, 0, 0, invalid);
        final int bridgeIoThreads = intSetting(values, BRIDGE_IO_THREADS_KEY, Scheduler.DEFAULT.bridgeIoThreads(), 0, invalid);
        final int bridgeIoQueue = intSetting(values, BRIDGE_IO_QUEUE_KEY, Scheduler.DEFAULT.bridgeIoQueue(), 1, invalid);
        final int storageThreads = intSetting(values, STORAGE_THREADS_KEY, Scheduler.DEFAULT.storageThreads(), 0, invalid);
        final int storageQueue = intSetting(values, STORAGE_QUEUE_KEY, Scheduler.DEFAULT.storageQueue(), 1, invalid);
        boolean networkCounters = Network.DEFAULT.counters();
        final Object countersValue = values.get(NETWORK_COUNTERS_KEY);
        if (countersValue != null) {
            if (countersValue instanceof Boolean enabled) {
                networkCounters = enabled;
            } else {
                invalid.add(NETWORK_COUNTERS_KEY);
            }
        }

        // Off on a typo: running command blocks is the direction that needs the operator's intent.
        boolean commandBlocks = World.DEFAULT.commandBlocks();
        final Object commandBlocksValue = values.get(COMMAND_BLOCKS_KEY);
        if (commandBlocksValue != null) {
            if (commandBlocksValue instanceof Boolean enabled) {
                commandBlocks = enabled;
            } else {
                invalid.add(COMMAND_BLOCKS_KEY);
            }
        }

        // Off on a typo: a tick tag runs every tick with console rights, which needs the operator's intent.
        boolean tickFunctions = World.DEFAULT.tickFunctions();
        final Object tickFunctionsValue = values.get(TICK_FUNCTIONS_KEY);
        if (tickFunctionsValue != null) {
            if (tickFunctionsValue instanceof Boolean enabled) {
                tickFunctions = enabled;
            } else {
                invalid.add(TICK_FUNCTIONS_KEY);
            }
        }

        // Each key falls back on its own. A typo in one setting must not silently revert another
        // the operator set deliberately -- only a malformed namespace container, handled above,
        // discards everything, because then nothing underneath it can be trusted.
        return new Parsed(new AuroraConfig(new Entity(asyncPathfinding), new Diagnostics(laneSampling, chunkGenerationMetrics,
                new StallRecorder(stallEnabled, stallAfter, stallRepeat, stallMaxDumps, stallKeepFiles)),
            new Cpu(cores), new Bridge(bridgeMode, quarantineAfter, syncRoute, maxPendingTasks, maxRunningAsyncTasks),
            new Scheduler(bridgeIoThreads, bridgeIoQueue, storageThreads, storageQueue), new Network(networkCounters),
            new World(commandBlocks, tickFunctions)),
            List.copyOf(invalid), deprecated);
    }

    /** A whole number within {@code min..max}, else the default and an invalid-key report. */
    private static int boundedInt(final java.util.Map<String, Object> values, final String key, final int fallback,
                                  final int min, final int max, final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number && number.doubleValue() == Math.floor(number.doubleValue())
            && number.longValue() >= min && number.longValue() <= max) {
            return number.intValue();
        }
        invalid.add(key);
        return fallback;
    }

    /** A whole number at or above {@code min}, else the default and an invalid-key report. */
    private static int intSetting(final java.util.Map<String, Object> values, final String key, final int fallback,
                                  final int min, final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number && number.doubleValue() == Math.floor(number.doubleValue())
            && number.longValue() >= min && number.longValue() <= 100_000) {
            return number.intValue();
        }
        invalid.add(key);
        return fallback;
    }

    /** Counts changed LIVE settings only; restart requirements need a separate boot baseline. */
    public int liveChangesComparedTo(final AuroraConfig previous) {
        int changed = 0;
        if (entity.asyncPathfinding() != previous.entity.asyncPathfinding()) changed++;
        if (diagnostics.laneSampling() != previous.diagnostics.laneSampling()) changed++;
        if (bridge.quarantineAfter() != previous.bridge.quarantineAfter()) changed++;
        if (bridge.syncRoute() != previous.bridge.syncRoute()) changed++;
        if (bridge.maxPendingTasksPerPlugin() != previous.bridge.maxPendingTasksPerPlugin()) changed++;
        if (bridge.maxRunningAsyncTasksPerPlugin() != previous.bridge.maxRunningAsyncTasksPerPlugin()) changed++;
        if (network.counters() != previous.network.counters()) changed++;
        if (world.commandBlocks() != previous.world.commandBlocks()) changed++;
        if (world.tickFunctions() != previous.world.tickFunctions()) changed++;
        return changed;
    }

    /** Change summary relative to the supplied snapshot, not an active-runtime status report. */
    public String reloadSummary(final AuroraConfig previous) {
        int changed = liveChangesComparedTo(previous);
        // Reported separately, never counted as applied. The region scheduler is sized during
        // GlobalConfiguration's load, so a reload cannot reach it; saying "restart required:
        // none" after an operator edited the CPU budget would be telling them it took effect.
        final List<String> restart = new java.util.ArrayList<>();
        if (cpu.cores() != previous.cpu.cores()) {
            restart.add(CPU_CORES_KEY + " (" + previous.cpu.cores() + " -> " + cpu.cores() + ")");
        }
        if (bridge.mode() != previous.bridge.mode()) {
            restart.add(BRIDGE_MODE_KEY + " (" + previous.bridge.mode() + " -> " + bridge.mode() + ")");
        }
        if (diagnostics.chunkGenerationMetrics() != previous.diagnostics.chunkGenerationMetrics()) {
            restart.add(CHUNK_GENERATION_METRICS_KEY + " (" + previous.diagnostics.chunkGenerationMetrics()
                + " -> " + diagnostics.chunkGenerationMetrics() + ")");
        }
        if (!diagnostics.stallRecorder().equals(previous.diagnostics.stallRecorder())) {
            restart.add("aurora.diagnostics.stall-recorder.*");
        }
        if (!scheduler.equals(previous.scheduler)) {
            restart.add("aurora.scheduler budgets");
        }
        return "Aurora: " + changed + " live change(s) applied; restart required: "
            + (restart.isEmpty() ? "none" : String.join(", ", restart) + ", takes effect on restart")
            + " (implemented Aurora keys only)";
    }
}
