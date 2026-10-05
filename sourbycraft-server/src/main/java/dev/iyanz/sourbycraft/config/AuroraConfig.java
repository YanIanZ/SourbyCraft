package dev.iyanz.sourbycraft.config;

import java.util.List;
import java.util.Objects;

/** Typed, immutable settings for implemented Aurora behavior only. Parsing never writes config. */
public record AuroraConfig(Entity entity, Diagnostics diagnostics, Cpu cpu, Bridge bridge, Scheduler scheduler,
                           Network network) {
    public static final String ASYNC_PATH_KEY = "aurora.entity.async-pathfinding";
    public static final String LEGACY_ASYNC_PATH_KEY = "perf.ai.async-pathfinding";
    public static final String LANE_SAMPLING_KEY = "aurora.diagnostics.lane-sampling";
    public static final String CPU_CORES_KEY = "aurora.cpu.cores";
    public static final String BRIDGE_MODE_KEY = "aurora.bridge.mode";
    public static final String BRIDGE_QUARANTINE_KEY = "aurora.bridge.quarantine-after";
    public static final String BRIDGE_SYNC_ROUTE_KEY = "aurora.bridge.sync-route";
    public static final String BRIDGE_IO_THREADS_KEY = "aurora.scheduler.bridge-io-threads";
    public static final String BRIDGE_IO_QUEUE_KEY = "aurora.scheduler.bridge-io-queue";
    public static final String STORAGE_THREADS_KEY = "aurora.scheduler.storage-threads";
    public static final String STORAGE_QUEUE_KEY = "aurora.scheduler.storage-queue";
    public static final String NETWORK_COUNTERS_KEY = "aurora.network.counters";
    public static final AuroraConfig DEFAULT =
        new AuroraConfig(new Entity(false), new Diagnostics(true), new Cpu(Cpu.AUTO), Bridge.DEFAULT,
            Scheduler.DEFAULT, Network.DEFAULT);
    public static final Setting ASYNC_PATH = new Setting(ASYNC_PATH_KEY, Lifecycle.LIVE);
    public static final Setting LANE_SAMPLING = new Setting(LANE_SAMPLING_KEY, Lifecycle.LIVE);
    // The region scheduler is sized during GlobalConfiguration load, long before a reload can
    // reach it, so this cannot honestly be advertised as live.
    public static final Setting CPU_CORES = new Setting(CPU_CORES_KEY, Lifecycle.RESTART_REQUIRED);
    // Plugins are admitted or refused once, at load; a reload cannot un-load or re-load them.
    public static final Setting BRIDGE_MODE = new Setting(BRIDGE_MODE_KEY, Lifecycle.RESTART_REQUIRED);
    public static final Setting BRIDGE_QUARANTINE = new Setting(BRIDGE_QUARANTINE_KEY, Lifecycle.LIVE);
    // Read when each task is scheduled.
    public static final Setting BRIDGE_SYNC_ROUTE = new Setting(BRIDGE_SYNC_ROUTE_KEY, Lifecycle.LIVE);
    // Governed lanes are created once with their budget and never resized.
    public static final Setting SCHEDULER_BUDGETS = new Setting("aurora.scheduler.*", Lifecycle.RESTART_REQUIRED);
    public static final Setting NETWORK_COUNTERS = new Setting(NETWORK_COUNTERS_KEY, Lifecycle.LIVE);

    public AuroraConfig {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(cpu, "cpu");
        Objects.requireNonNull(bridge, "bridge");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(network, "network");
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
     */
    public record Bridge(BridgeMode mode, int quarantineAfter, SyncRoute syncRoute) {
        public static final Bridge DEFAULT = new Bridge(BridgeMode.OFF, 3, SyncRoute.CALLER_REGION);

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
    public record Diagnostics(boolean laneSampling) {}
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
                                          "aurora.cpu", "aurora.bridge", "aurora.scheduler",
                                          "aurora.network")) {
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

        // Each key falls back on its own. A typo in one setting must not silently revert another
        // the operator set deliberately -- only a malformed namespace container, handled above,
        // discards everything, because then nothing underneath it can be trusted.
        return new Parsed(new AuroraConfig(new Entity(asyncPathfinding), new Diagnostics(laneSampling),
            new Cpu(cores), new Bridge(bridgeMode, quarantineAfter, syncRoute),
            new Scheduler(bridgeIoThreads, bridgeIoQueue, storageThreads, storageQueue), new Network(networkCounters)),
            List.copyOf(invalid), deprecated);
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
        if (network.counters() != previous.network.counters()) changed++;
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
        if (!scheduler.equals(previous.scheduler)) {
            restart.add("aurora.scheduler budgets");
        }
        return "Aurora: " + changed + " live change(s) applied; restart required: "
            + (restart.isEmpty() ? "none" : String.join(", ", restart) + ", takes effect on restart")
            + " (implemented Aurora keys only)";
    }
}
