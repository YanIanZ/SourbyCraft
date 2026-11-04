package dev.iyanz.sourbycraft.config;

import dev.iyanz.sourbycraft.awf.AwfSettings;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * Every key of {@code sourbycraft_config/aurora.yml}: path, default, lifecycle and the comment the
 * seeder writes beside it. The paths did not change in Build 49 (they were already grouped under
 * {@code [aurora.<service>]}); what changed is that the file gets a header and section comments, and
 * that Aurora keys an older deployment still kept in the utility file are moved here once
 * ({@link ConfigMigration}). {@link #UNSEEDED_PATHS} lists the keys read from this file that the seeder
 * does not write.
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
            + "Over the limit, a one-shot callback is cancelled and a repeating task skips that run and stays scheduled; "
            + "both are diagnosed (rate-limited); running bodies drain normally. Rejection, "
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
        "file = a directory beside each region folder. redis = the built-in Redis backend, configured by the "
            + "aurora.awf.redis.* keys, which are not seeded (a uri written here would take precedence over the "
            + "SOURBYCRAFT_AWF_REDIS_URI environment variable). Another name selects a backend registered by "
            + "server-side code before worlds load; if it is not registered, listed worlds fail to load rather than "
            + "falling back.");
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
        "One budget in milliseconds for the AWF waits at shutdown (lock waits, the start of each commit attempt, "
            + "Redis calls, lease release), from the start of shutdown. When it passes no further commit is attempted, "
            + "open Redis connections are cut, and the shutdown is logged as AWF-SHUTDOWN-INCOMPLETE with every "
            + "unconfirmed storage (exit status 70), never as saved. A FILE commit already writing (an fsync on a "
            + "stalled disk) cannot be cut. Also how long an AuroraWorlds save (/awf save, the save before an unload) "
            + "waits for its world's chunk I/O to drain before it fails as not durable. 1-2147483647.");

    // [aurora.awf.multiprofile] — AWF MultiProfile (docs/awf/multiprofile/CONFIGURATION.md)
    public static final List<ConfigKey> AWF_MULTIPROFILE_KEYS = List.of(
        restart("aurora.awf.multiprofile.enabled", false,
            "AWF MultiProfile. false = no profile directory, thread, listener or service registration; a plugin looking up "
                + "AuroraProfiles gets null, as on a server without the feature. Read early, before the configuration loads."),
        restart("aurora.awf.multiprofile.mode", "standalone",
            "standalone = one server. distributed = several servers share profiles through the Aurora Redis Fabric; refused "
                + "at startup unless repository: redis, coordination.provider: redis, fencing-enabled: true and a running "
                + "fabric are configured. Distributed, like any repository: redis setup (nothing limits that one to one "
                + "server), is EXPERIMENTAL (not yet qualified on two nodes; no proxy handoff): a profile write made under a "
                + "session claim is refused atomically in Redis once that claim no longer holds the profile."),
        restart("aurora.awf.multiprofile.default-slots", 3,
            "Profiles a new account may own. 1-64, at most max-slots. Existing accounts keep the slot limit stored with them."),
        restart("aurora.awf.multiprofile.max-slots", 8,
            "Hard ceiling for any account, including one whose stored limit is higher. 1-64."),
        restart("aurora.awf.multiprofile.switching.enabled", true,
            "Allow /profile-style switches. false leaves creation, listing and saving available but refuses every switch."),
        restart("aurora.awf.multiprofile.switching.timeout-ms", 15000,
            "One switch's budget. Past it the switch fails and rolls the player back to the previous profile snapshot. 1000-600000."),
        restart("aurora.awf.multiprofile.switching.require-durable-save", true,
            "true = a switch aborts when the previous profile's snapshot cannot be written durably (so a rollback point exists). "
                + "false = continue without one; recovery then cannot restore the old state exactly."),
        restart("aurora.awf.multiprofile.isolation.inventory", true,
            "The 36 hotbar and storage slots belong to the profile (item components preserved through Paper's byte "
                + "serialisation); armor and off-hand are isolation.armor."),
        restart("aurora.awf.multiprofile.isolation.armor", true,
            "Armor and off-hand belong to the profile. false = they stay with the player across switches."),
        restart("aurora.awf.multiprofile.isolation.ender-chest", true,
            "Ender chest contents belong to the profile."),
        restart("aurora.awf.multiprofile.isolation.experience", true,
            "Level and experience progress belong to the profile."),
        restart("aurora.awf.multiprofile.isolation.health", true,
            "Health belongs to the profile; applied as min(stored, current max health)."),
        restart("aurora.awf.multiprofile.isolation.food", true,
            "Food level and saturation belong to the profile."),
        restart("aurora.awf.multiprofile.isolation.potion-effects", true,
            "Active potion effects belong to the profile, infinite ones included. An effect the profile cannot hold "
                + "(amplifier above 255) or an unknown effect key is skipped with a warning, never fatal."),
        restart("aurora.awf.multiprofile.isolation.location", true,
            "The respawn point and last location belong to the profile: a switch sets the profile's respawn point as a "
                + "forced one (it then also works on a free spot after its bed or anchor is gone, and an anchor uses no "
                + "charge) or clears "
                + "it, and moves the player to its last location, else its respawn point, else the spawn of its "
                + "PRIMARY bound world (also where a new profile goes). A join outside the active profile's bound worlds, "
                + "while one of them was unloaded, returns the player to the profile's last location in them, or to its "
                + "PRIMARY world spawn when it stores none. Only AWF world names "
                + "(lower-case [a-z0-9_-]) can be destinations. false = the player stays where they are."),
        restart("aurora.awf.multiprofile.worlds.idle-unload-seconds", 300,
            "Save and unload a world bound to a profile after this long with no use (checked every 30 s). A world with a "
                + "player inside counts as used; only AWF worlds are unloaded, never the main world. 0 disables idle unloading."),
        restart("aurora.awf.multiprofile.coordination.provider", "local",
            "local = process-local ownership; redis = the Aurora Redis Fabric session fabric. redis is required by mode: "
                + "distributed and by repository: redis."),
        restart("aurora.awf.multiprofile.coordination.fencing-enabled", true,
            "Validate the ownership fence immediately before a durable profile commit. false is UNSAFE across nodes and is "
                + "rejected in distributed mode and with repository: redis."),
        restart("aurora.awf.multiprofile.coordination.single-active-session", true,
            "Refuse a second live session for a profile owned elsewhere (the joining player is rejected, not the holder)."),
        restart("aurora.awf.multiprofile.coordination.handoff-wait-ms", 0,
            "Build 55.1. A join whose session claim or active-profile claim is refused only because another live session "
                + "still holds it (a proxy server switch: the previous server has not committed the quit save and released "
                + "the claim yet) retries the claim with bounded backoff, off the tick threads, for up to this long before "
                + "it is refused as before. The retry never takes a claim that is still live. 0 = one attempt (Build 51-55 "
                + "behaviour); 5000 recommended behind Velocity; max 15000. Counted in /perf multiprofile."),
        restart("aurora.awf.multiprofile.directory", "awf/profiles",
            "Relative directory of the file repository (accounts, profiles, snapshots, transition journals). Created only "
                + "when enabled. With repository: redis it is only read: while Redis is empty and this directory holds "
                + "profiles, profile writes are refused until /awf multiprofile migrate-repository copies them."),
        restart("aurora.awf.multiprofile.repository", "file",
            "Where profiles, snapshots and journals live. file = the directory above, one server only (its lock refuses a "
                + "second server). redis = the Aurora Redis Fabric (STORAGE pool, keys <prefix>fabric:mp:*): each write is "
                + "one Lua script that atomically checks the writer's session claim (when the write carries one) and the "
                + "record revision (when the record has one) before it writes. redis needs "
                + "coordination.provider: redis, fencing-enabled: true, a running fabric and a Redis 7.0+ primary (no "
                + "cluster); durability is Redis's (see redis.require-aof)."),
        restart("aurora.awf.multiprofile.redis.require-aof", true,
            "repository: redis only. true = refuse to start when Redis reports appendonly off (a Redis restart would lose "
                + "every profile written since its last RDB snapshot). Even with AOF, appendfsync everysec can lose about "
                + "1 s of acknowledged writes on a Redis crash, and a replica promoted on failover can lose acknowledged "
                + "writes; one primary is the supported topology. false = start anyway."),
        restart("aurora.awf.multiprofile.redis.wait-for-aof", true,
            "repository: redis only. true = snapshot, account, profile and binding writes, and the journal write before a "
                + "target state is applied, wait for WAITAOF (Redis 7.2+, up to half of aurora.awf.redis.timeout-ms); a "
                + "write not confirmed in time is still applied, logged and counted in /awf multiprofile status. With "
                + "appendfsync everysec each such write can take up to about 1 s longer. Unsupported by Redis: logged "
                + "once, then acknowledged from memory."),
        restart("aurora.awf.multiprofile.types.allowed", List.of("classic", "ironman", "stranded", "bingo"),
            "Policy types (classic, ironman, stranded, bingo) a profile may be created with; a list or a comma-separated "
                + "string. A request naming another type is refused (TYPE_NOT_ALLOWED). Not the free consumer namespace "
                + "(ProfileRequest.type), which this list does not restrict, and not a plugin-registered type (Build 54, "
                + "creatable per its definition). Since Build 54 each type's rules come from the profile type registry "
                + "(docs/awf/multiprofile/PROFILE-TYPES.md): the engine enforces the actions it owns (bank, co-op, salvage); "
                + "trade, auction and bazaar rules are for the consumer plugin to enforce. An unknown or empty list is "
                + "ignored with a WARN."),
        restart("aurora.awf.multiprofile.types.default", "classic",
            "The policy type of a profile created without one. Must be in types.allowed; if it is not, the first allowed "
                + "type is used and a WARN names this key."),
        restart("aurora.awf.multiprofile.names.policy", "cute",
            "How a profile is named. cute = the server assigns a fruit or vegetable name from its pool when the profile "
                + "is created: unique per account (ignoring case), immutable, with a numeric suffix when the pool is "
                + "exhausted for the account. A create request may ask for a specific pool name; other text is "
                + "refused (NAME_INVALID, or NAME_TAKEN when the account uses it). cute is the only policy."),
        restart("aurora.awf.multiprofile.migration.mode", "off",
            "What happens to a profile store written before Build 51 (record format 1). off = nothing; the store stays "
                + "format 1. dry-run = read the store and report what a migration would do; writes nothing. apply = copy the "
                + "store to migration.backup-dir first, then convert it to format 2. Conversion is one way: a format-2 "
                + "store is refused by builds older than Build 51, and rolling back means restoring the backup into an "
                + "isolated store (there is no in-place downgrade). apply without a valid backup-dir falls back to off "
                + "with a WARN. Quote the value in YAML (an unquoted off is the boolean false; it is read as off)."),
        restart("aurora.awf.multiprofile.migration.backup-dir", "",
            "Where migration.mode: apply writes the pre-migration backup: a path relative to the server directory, or "
                + "absolute (a separate disk is the point of a backup). Empty = not configured. No .. segment; it must not "
                + "be inside or contain the store directory. Not used by off or dry-run."),
        restart("aurora.awf.multiprofile.coop.enabled", false,
            "Co-op membership (Build 52) and play (Build 53): convert a solo profile to co-op, invite, accept, decline, "
                + "cancel, leave, kick vote, shared label; since Build 53 a member can switch into the co-op and play it "
                + "with their own state. false = every co-op operation is refused with COOP_DISABLED and nothing co-op is "
                + "written. Needs aurora.awf.multiprofile.enabled and a store in format 2; a format-1 store refuses "
                + "with MIGRATION_REQUIRED (see migration.mode). Member play also needs authoritative login (login.authoritative "
                + "auto, which co-op enables, or true) and the node that holds the co-op's bound world (WRONG_NODE elsewhere; no "
                + "cross-node routing). Does not "
                + "transfer ownership. Build 54 adds the shared and personal bank, collections and salvage delivery "
                + "(bank.* keys). Conversion is one way."),
        restart("aurora.awf.multiprofile.coop.max-members", 5,
            "Members of one co-op, owner included. 2 to 8. Applies to new invitations and accepts; a co-op already "
                + "above a lowered cap keeps its members. An invalid value is ignored with a WARN (5)."),
        restart("aurora.awf.multiprofile.coop.invite-expiry-minutes", 5,
            "How long a co-op invitation stays valid, 1 to 1440 minutes. Expiry is judged at commit time against the "
                + "repository clock, not only when listed."),
        restart("aurora.awf.multiprofile.coop.kick-vote.expiry-minutes", 10,
            "How long a kick vote stays open, 1 to 1440 minutes. Approval must be unanimous among the members other "
                + "than the target (the owner included); the starter's approval is counted."),
        restart("aurora.awf.multiprofile.coop.kick-vote.inactive-days", 35,
            "In a two-member co-op, how many days the other member must have been inactive before a kick vote may "
                + "start against them (TARGET_NOT_INACTIVE until then), 1 to 3650. Build 52 records a member's last "
                + "activity at join time and on membership changes only, not from gameplay."),
        restart("aurora.awf.multiprofile.coop.allowed-types", List.of("classic"),
            "Policy types (classic, ironman, stranded, bingo) whose solo profile may be converted to co-op; a list or a "
                + "comma-separated string. Checked at conversion only. A profile of another type is refused "
                + "(TYPE_NOT_ALLOWED). Since Build 54 the conversion must also be allowed by the type's registry definition "
                + "(coop.convert, coopAllowed; ACTION_FORBIDDEN_BY_TYPE otherwise). The engine does not enforce trade, auction "
                + "or bazaar rules, so widening this list gives those types co-op with only the engine-owned rules applied. "
                + "An unknown or empty list is ignored with a WARN."),
        restart("aurora.awf.multiprofile.shutdown.capture-deadline-ms", 10000,
            "Build 53. At shutdown MultiProfile captures and saves the online players before the server removes them, "
                + "then closes. This is how long shutdown waits for those captures and saves, 1000 to 60000 ms. "
                + "When it passes, a player whose save has not finished keeps the last committed snapshot and any "
                + "pending intent for recovery at their next join, and shutdown continues; it is logged per player. "
                + "A longer value delays process exit by up to that long. An invalid value is ignored with a WARN (10000)."),
        restart("aurora.awf.multiprofile.login.authoritative", "auto",
            "Build 53. auto | true | false. auto (default) = authoritative exactly when coop.enabled is true, so a server "
                + "without co-op joins exactly as Build 52 did. true = on join the committed state of the player's active "
                + "profile (the member head in a co-op) is restored instead of whatever vanilla playerdata loaded, every "
                + "vanilla player save (autosave) and the quit publish a profile snapshot, a head that cannot be read or "
                + "restored refuses the join (HEAD_UNREADABLE and other codes), and after a crash or the upgrade from Build 52 "
                + "a vanilla playerdata newer than the profile's snapshot is adopted into the active profile once "
                + "(docs/awf/multiprofile/PROFILE-LIFECYCLE.md). false = the Build 52 behaviour: a join keeps the vanilla "
                + "playerdata; co-op members cannot switch into a co-op while it does not apply. An invalid value is ignored "
                + "with a WARN (auto)."),
        restart("aurora.awf.multiprofile.adapters.required", List.of(),
            "Build 53. Profile state adapter ids (a list or a comma-separated string, [a-z0-9_.:-]{1,64}, at most 32) "
                + "that are treated as REQUIRED whatever the adapter's descriptor declares: their capture must be durable "
                + "before a switch target becomes active, their apply is verified, a failure refuses the switch "
                + "(ADAPTER_REQUIRED_FAILED), and an id that holds data for the profile but is not registered on this "
                + "node refuses it (ADAPTER_MISSING). It can only raise strictness; an adapter that declares REQUIRED "
                + "stays REQUIRED when not listed. A malformed entry is dropped with a WARN; the well-formed entries stay."),
        restart("aurora.awf.multiprofile.adapters.preserve-unregistered", List.of(),
            "Build 55.1. Adapter ids (same form, at most 32) whose stored REQUIRED data passes through this node unchanged "
                + "while no plugin registers them here: not applied, not captured, never dropped, no ADAPTER_MISSING. For "
                + "a node that by design runs without the plugin that owns the data (a lobby without the island plugin's "
                + "economy adapter). A registered id is not affected."),
        restart("aurora.awf.multiprofile.types.registry.allow-override-builtins", true,
            "Build 54. Whether a plugin may register its own definition for a built-in profile type id (classic, "
                + "ironman, stranded, bingo) through the ProfileTypeRegistry, replacing the built-in rules. false = such a "
                + "registration is refused (TYPE_NOT_ALLOWED) and a stored override is ignored with a WARN, so the built-in "
                + "definition applies. Registered types with other ids are unaffected. An invalid value is ignored with a "
                + "WARN (true)."),
        restart("aurora.awf.multiprofile.types.unknown-type-policy", "deny",
            "Build 54. deny | allow. What a profile whose type has no known definition (not built in, not registered by "
                + "a plugin on this server, no readable stored definition) may do. deny (default, fail-safe) = every "
                + "action is refused, the engine's bank, co-op and salvage actions included (ACTION_FORBIDDEN_BY_TYPE), "
                + "with one WARN naming the type, until its plugin registers it. allow = every action is allowed, as for "
                + "classic, which lifts whatever the lost definition denied. An invalid value is ignored with a WARN (deny)."),
        restart("aurora.awf.multiprofile.bank.currency.provider", "aurora",
            "Build 54. Provider id of the co-op bank currency, [a-z0-9_.-]{1,64}. A bank record keeps the currency it was first "
                + "written with; once a record exists, a different configured currency refuses that bank's changes "
                + "(INVALID_REQUEST) until the setting names the stored currency again. The server moves no purse money "
                + "(owner decision OD-4). Invalid = aurora + WARN."),
        restart("aurora.awf.multiprofile.bank.currency.id", "coins",
            "Build 54. Currency id of the co-op bank, [a-z0-9_.-]{1,64}; same rules as bank.currency.provider. Invalid = coins + WARN."),
        restart("aurora.awf.multiprofile.bank.currency.scale", 0,
            "Build 54. Decimal places of one minor unit, 0-9 (0 = whole coins). Amounts in the API are long minor units; "
                + "/coop bank parses decimals at this scale. Invalid = 0 + WARN."),
        restart("aurora.awf.multiprofile.bank.shared-capacity", 0,
            "Build 54. Cap of the shared bank in minor units, 0 = no cap (still bounded by the long range). Applies to bank "
                + "records created after start; an existing record keeps the capacity it was created with. A deposit past the "
                + "cap is refused (INVALID_REQUEST). Invalid = 0 + WARN."),
        restart("aurora.awf.multiprofile.bank.personal-cap-percent", 20,
            "Build 54. Cap of each member's personal bank as a percentage of the record's shared capacity, 0-100 (Hypixel "
                + "parity 20). 0, or a shared capacity of 0, means no personal cap. Invalid = 20 + WARN."),
        restart("aurora.awf.multiprofile.bank.interest-percent", 10,
            "Build 54. Interest one interest posting credits, 0-100 (Hypixel parity 10), to the shared balance and every "
                + "personal bank, each capped at its capacity. The server schedules no interest: the owner posts it "
                + "(/awf multiprofile coop bank interest, or a plugin through AuroraProfiles.applyInterest), and every posting "
                + "credits again, no sooner than bank.interest-interval-hours after the previous one. 0 = no interest. Invalid = 10 + WARN."),
        restart("aurora.awf.multiprofile.bank.interest-interval-hours", 24,
            "Build 54. Least number of hours between two interest postings of one co-op, judged on the store clock (the "
                + "record remembers its last posting); a sooner posting is refused (INVALID_REQUEST, naming the wait). 0 = no "
                + "minimum, at most 8784. Invalid = 24 + WARN."),
        restart("aurora.awf.multiprofile.switching.cooldown-seconds", 0,
            "Build 55. Seconds after a committed profile switch during which the player's next switch is refused "
                + "(SWITCH_COOLDOWN, naming the wait). 0 (default) = no cooldown, the Build 54 behaviour; Hypixel parity is 90; "
                + "at most 86400. Only a committed switch starts it: a refused, vetoed or failed switch does not. Kept in memory "
                + "on this node (a restart clears it; /awf multiprofile cooldown <player> reset clears one). Invalid = 0 + WARN."),
        restart("aurora.awf.multiprofile.audit.enabled", true,
            "Build 55. The MultiProfile audit log: immutable entries recorded after the durable outcome of each switch, create, "
                + "delete, lock, archive, co-op membership change, bank posting and salvage delivery (and each switch refusal), "
                + "kept in memory (newest 4096, /awf multiprofile audit <profile>, AuroraProfiles.audit) and appended to daily "
                + "files <directory>/audit/<date>.log on the storage lane. Recording never changes an outcome; a failed write is "
                + "counted (/perf multiprofile). false = nothing is recorded and audit() fails DISABLED. Invalid = true + WARN."),
        restart("aurora.awf.multiprofile.audit.retention-days", 30,
            "Build 55. Days a daily audit file is kept, 1-3660; older files are deleted when a new day's file opens. "
                + "Invalid = 30 + WARN."),
        restart("aurora.awf.multiprofile.io.partitions", 1,
            "Build 55 (PM-091). Partitions of the profile I/O queue, 1-64. 1 (default) = one profile step at a time, FIFO over "
                + "all accounts, exactly as Build 54. Above 1, steps that name only their own account and profiles (switch, "
                + "save, login, session) may run at once when they fall in different partitions, each holding one storage lane "
                + "slot, while every account's own steps stay FIFO; store-wide steps (create, delete, co-op, bank, salvage, "
                + "recovery) are barriers. Relief only shows with aurora.scheduler.storage-threads above 1. Unqualified above 1 until the "
                + "owner's soak. Invalid = 1 + WARN."));

    // [aurora.awf.redis.fabric] — Aurora Redis Fabric (docs/awf/multiprofile/REDIS-FABRIC.md)
    public static final List<ConfigKey> AWF_REDIS_FABRIC_KEYS = List.of(
        restart("aurora.awf.redis.fabric.enabled", false,
            "Aurora Redis Fabric: workload-isolated Redis pools and services beyond world storage. false = no socket, no "
                + "thread, no key. Reuses aurora.awf.redis.uri, key-prefix, timeout-ms and TLS/ACL credentials; no secret is "
                + "duplicated here. Read early."),
        restart("aurora.awf.redis.fabric.services.sessions", true,
            "Profile session ownership with a never-resetting epoch (the fence)."),
        restart("aurora.awf.redis.fabric.services.coordination", true,
            "Fenced ownership claims (profiles, worlds) for server code that calls them; /awf redis ownership reads "
                + "them. Nothing in this build takes one or checks one before a write: MultiProfile ownership is a "
                + "session claim (services.sessions), and a repository: redis write made under one checks it inside "
                + "Redis."),
        restart("aurora.awf.redis.fabric.services.events", false,
            "Redis Streams internal events: consumer groups, acks, pending recovery, retry budget, dead-letter stream. "
                + "Off by default because this build ships no consumer: an unconsumed stream reaches its retention "
                + "limit and then refuses every publish. Enable only together with a consumer."),
        restart("aurora.awf.redis.fabric.services.cache", false,
            "Bounded metadata cache with TTL and version-based invalidation; never a source of truth. Off by default: "
                + "this build ships no consumer that would populate it."),
        restart("aurora.awf.redis.fabric.services.routing", true,
            "Node directory and ownership lookup for proxies and game plugins. No transport is opened."),
        restart("aurora.awf.redis.fabric.services.telemetry", true,
            "Aggregate counters behind /awf redis status; no per-player or per-profile labels."),
        restart("aurora.awf.redis.fabric.pools.storage", 8,
            "Connections for the AWF world store. When the fabric runs, the redis backend shares this pool instead of opening "
                + "its own; aurora.awf.redis.pool-size applies only when the fabric is off. 1-64."),
        restart("aurora.awf.redis.fabric.pools.coordination", 4,
            "Connections for session and ownership scripts. 1-64."),
        restart("aurora.awf.redis.fabric.pools.events", 2,
            "Connections for stream producers and blocking consumers. 1-64."),
        restart("aurora.awf.redis.fabric.pools.control", 2,
            "Connections for health probes, node directory and cache version reads. 1-64."),
        restart("aurora.awf.redis.fabric.cache.max-entries", 10000, "Cache entries per node. 1-1000000."),
        restart("aurora.awf.redis.fabric.cache.ttl-seconds", 60,
            "Cache TTL; bounds how long a missed invalidation can be served. 1-86400."),
        restart("aurora.awf.redis.fabric.sessions.heartbeat-seconds", 10,
            "How often a node renews a session claim. 1-3600; must be below sessions.ttl-seconds."),
        restart("aurora.awf.redis.fabric.sessions.ttl-seconds", 45,
            "Claim lifetime without a heartbeat. Expiry is not permission to write: a takeover increments the epoch and a "
                + "stale writer fails validation. 5-86400."),
        restart("aurora.awf.redis.fabric.events.retention", 10000,
            "Approximate stream length (MAXLEN ~). Reaching it refuses new publishes instead of growing without bound. 100-10000000."),
        restart("aurora.awf.redis.fabric.events.batch-size", 32, "Events per read; 1-500."),
        restart("aurora.awf.redis.fabric.events.max-deliveries", 5,
            "Deliveries before an unacknowledged event is copied to the dead-letter stream and acknowledged. 1-100."),
        restart("aurora.awf.redis.fabric.events.read-block-ms", 500,
            "Server-side block for one consumer read; bounds how long a consumer worker parks. 1-5000."),
        restart("aurora.awf.redis.fabric.coordination.claim-seconds", 45,
            "Lease for a coordination claim (profiles, worlds). Another node may take over after it; the fence still rises. 5-86400."));

    /** AbyssLink's keys; part of {@link #KEYS} so the YAML seeder writes them, comments included. */
    public static final List<ConfigKey> ABYSSLINK_KEYS = List.of(
        restart("aurora.abysslink.enabled", false, "Optional native Leviathan control client; failure never blocks startup."),
        restart("aurora.abysslink.host", "localhost", "Leviathan TCP listener host."),
        restart("aurora.abysslink.port", 25580, "Leviathan TCP listener port."),
        restart("aurora.abysslink.node-id", "sourbycraft", "Unique backend node bound to the client certificate by Leviathan."),
        restart("aurora.abysslink.tls", true, "Verified TLS is mandatory; false is rejected."),
        restart("aurora.abysslink.authenticated-identity", "localhost", "Expected DNS/IP certificate SAN; HTTPS endpoint identification is enforced."),
        restart("aurora.abysslink.trust-certificate", "", "PEM CA path, or empty for JVM trust roots."),
        restart("aurora.abysslink.client-certificate", "", "Backend PEM certificate chain, required when enabled."),
        restart("aurora.abysslink.client-key", "", "Backend PEM private-key path; protect with filesystem permissions."),
        restart("aurora.abysslink.reconnect-min-ms", 500, "Minimum retry delay, with bounded exponential jitter."),
        restart("aurora.abysslink.reconnect-max-ms", 30000, "Maximum retry delay."),
        restart("aurora.abysslink.heartbeat-ms", 5000, "Expected proxy heartbeat interval; backend echoes only. Match the proxy cadence; liveness expires after three intervals plus timeout."),
        restart("aurora.abysslink.timeout-ms", 750, "Bounded handshake and ping timeout, 100-2000 milliseconds."),
        restart("aurora.abysslink.ping-cache-ms", 250, "Short ping/miss cache; zero disables caching."),
        restart("aurora.abysslink.max-pending-requests", 32, "Hard cap for ping requests and cache entries, 1-256."),
        restart("aurora.abysslink.telemetry-ms", 10000, "Health sampling interval; unchanged reports suppressed, load reduces cadence."),
        restart("aurora.abysslink.threat-reporting", true, "Bounded aggregate guard signals; no player addresses or arbitrary commands."),
        restart("aurora.abysslink.max-sample-age-ms", 2000, "Reject older/future proxy samples; clocks must be synchronized."));

    /** Every key, in file order, without AbyssLink. */
    private static final List<ConfigKey> BASE_KEYS = List.of(
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

    /** Every key, in file order: AbyssLink's service keys follow the Aurora services it belongs to. */
    public static final List<ConfigKey> KEYS =
        java.util.stream.Stream.concat(BASE_KEYS.stream(),
            java.util.stream.Stream.concat(AWF_MULTIPROFILE_KEYS.stream(),
                java.util.stream.Stream.concat(AWF_REDIS_FABRIC_KEYS.stream(), ABYSSLINK_KEYS.stream()))).toList();

    /**
     * Keys read from this file that the seeder does not write: the AWF Redis backend's connection settings
     * ({@link RedisSettings}, used when {@code backend = redis}). A seeded {@code uri} would take precedence over the
     * {@code SOURBYCRAFT_AWF_REDIS_URI} environment variable that keeps a password out of the file, so none of them
     * is written. All RESTART_REQUIRED: the backend is registered once, before worlds load.
     */
    public static final List<String> UNSEEDED_PATHS = List.of(RedisSettings.URI_KEY, RedisSettings.PREFIX_KEY,
        RedisSettings.POOL_KEY, RedisSettings.TIMEOUT_KEY, RedisSettings.LEASE_KEY, RedisSettings.AOF_KEY);

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
        new UtilityKeys.Section("aurora.awf", "Aurora World Fabric (docs/architecture/aurora-world-fabric.md). All keys RESTART_REQUIRED; empty lists leave worlds on region files."),
        new UtilityKeys.Section("aurora.awf.multiprofile", "AWF MultiProfile: durable, isolated player profiles (docs/awf/multiprofile/ARCHITECTURE.md). Off by default."),
        new UtilityKeys.Section("aurora.awf.redis.fabric", "Aurora Redis Fabric: sessions, coordination, events, cache, routing (docs/awf/multiprofile/REDIS-FABRIC.md). Off by default."));

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

    /** Whether a flattened path belongs in {@code aurora.yml}: the {@code aurora.} namespace or a renamed Aurora key. */
    public static boolean owns(final String path) {
        if (path.startsWith("aurora.")) return true;
        for (final ConfigKey key : KEYS) {
            if (key.legacyPaths().contains(path)) return true;
        }
        return false;
    }

    /** Where a flattened path lands in {@code aurora.yml}: itself, or the current name of a renamed key. */
    public static String currentPath(final String path) {
        for (final ConfigKey key : KEYS) {
            if (key.legacyPaths().contains(path)) return key.path();
        }
        return path;
    }
}
