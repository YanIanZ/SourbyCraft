package dev.iyanz.sourbycraft;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SourbyCraft's configuration registry, and the boundary between its settings and Aurora's.
 *
 * <p>Operator-facing settings live in two files, not one. SourbyCraft's own -- varied messages,
 * the auto-updater, {@code /maxp} persistence and bypass, the GC-advisor toggle -- are in
 * {@code sourbycraft_config/sourbycraft_global_config.toml}, read through the typed
 * {@link #cfgBool}/{@link #cfgInt}/{@link #cfgGet}/{@link #cfgStringList} accessors below.
 * Aurora's are in {@code sourbycraft_config/aurora.toml}: the engine and the server are
 * different things, and an operator tuning the engine should not have to read past join
 * messages to find it.</p>
 *
 * <p>The Aurora file is layered <em>over</em> the unified one, so a deployment that predates the
 * split keeps the value it already had. Where both files set the same Aurora key, aurora.toml
 * wins and {@link #shadowedAuroraKeys} reports the copy that decides nothing -- neither file is
 * ever rewritten, because both belong to the operator (PRD 5).</p>
 *
 * <p>Both files use their own {@code nightconfig} {@link CommentedFileConfig}, resolved here
 * directly, with no upstream config-manager dependency.</p>
 *
 * <p><b>History.</b> An earlier version of this class was roughly a thousand lines, because it
 * also seeded and bridged a self-tuning performance engine, an anti-xray reveal layer, and a
 * proxy-forwarding and hardening-advisor security layer. Those were dropped rather than ported:
 * their config trees, {@code seed()} calls and live-apply bridges went with them. Since build
 * 44 this publishes immutable snapshots at explicit load boundaries and read-only performance
 * diagnostics. Automatic memory tuning is retired; its keys may still sit in operator files and
 * are ignored.</p>
 */
public final class SourbyCraftConfig {

    private static final Path CONFIG_PATH = Path.of("sourbycraft_config", "sourbycraft_global_config.toml");
    /**
     * Aurora's own file. The engine and SourbyCraft are different things -- that is the whole
     * point of the Aurora/SourbyCraft split -- and an operator tuning the engine should not have to
     * read past join messages to find it. The unified file is still read underneath, so a
     * deployment that predates this keeps working.
     */
    private static final Path AURORA_PATH = Path.of("sourbycraft_config", "aurora.toml");

    private static volatile CommentedFileConfig FILE;
    private static volatile CommentedFileConfig AURORA_FILE;
    private static boolean newFile;
    private static boolean newAuroraFile;
    private record LoadedConfig(ConfigSnapshot utility, AuroraConfig aurora) {}
    private static volatile LoadedConfig loaded = new LoadedConfig(
        new ConfigSnapshot(java.util.Map.of()), AuroraConfig.DEFAULT);

    private static volatile LoadedConfig bootConfig;

    public record ReloadReport(boolean successful, List<String> lines) {
        public ReloadReport { lines = List.copyOf(lines); }
    }

    /** Restart changes remain pending across repeated reloads until a new process starts. */
    public static List<String> pendingRestartKeys() {
        LoadedConfig boot = bootConfig;
        if (boot == null) return List.of();
        return restartChanges(boot.utility(), boot.aurora(), loaded.utility(), loaded.aurora());
    }

    static List<String> restartChanges(ConfigSnapshot before, AuroraConfig beforeAurora,
                                       ConfigSnapshot after, AuroraConfig afterAurora) {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        if (!beforeAurora.cpu().equals(afterAurora.cpu())) keys.add(AuroraConfig.CPU_CORES_KEY);
        if (beforeAurora.bridge().mode() != afterAurora.bridge().mode()) keys.add(AuroraConfig.BRIDGE_MODE_KEY);
        if (!beforeAurora.scheduler().equals(afterAurora.scheduler())) keys.add("aurora.scheduler.*");
        for (String key : List.of("ui.console-style", "branding.gc-advisor.enabled",
                "sourbycraft.max-players", "sourbycraft.maxplayers.bypass-enabled",
                "viaversion.auto-provision", "protocollib.auto-provision", "messages.motd", "misc.auto_update.check_interval_minutes")) {
            if (!java.util.Objects.equals(before.values().get(key), after.values().get(key))) keys.add(key);
        }
        return List.copyOf(keys);
    }

    /** Effective immutable Aurora settings, published only at explicit load boundaries. */
    public static AuroraConfig aurora() { return loaded.aurora(); }

    /**
     * Publishes both layers.
     *
     * <p>Both files are parameters rather than globals. Reaching for the Aurora path from in here
     * made this method depend on a file its caller had not named, which is how a test handed a
     * temporary file quietly picked up the real one.</p>
     *
     * @param file       the unified utility file
     * @param auroraFile Aurora's own file, or {@code null} when there is none to read
     */
    private static AuroraConfig.Parsed loadSnapshot(final CommentedFileConfig file,
                                                    final CommentedFileConfig auroraFile) {
        final ConfigSnapshot utility = ConfigSnapshot.copyOf(file);
        // Legacy first, Aurora's own file over it: a server that has never seen aurora.toml keeps
        // the value it already had, and one that has takes the new file's.
        final ConfigSnapshot aurora = auroraFile == null ? null : ConfigSnapshot.copyOf(auroraFile);
        if (aurora != null) {
            warnShadowedAuroraKeys(utility, aurora);
        }
        final AuroraConfig.Parsed parsed = AuroraConfig.parse(aurora == null ? utility
            : ConfigSnapshot.layered(utility, aurora));
        // Apply before publication: a failed runtime activation must not report success.
        dev.iyanz.sourbycraft.perf.AsyncPathProcessor.setEnabled(parsed.config().entity().asyncPathfinding());
        dev.iyanz.sourbycraft.execution.LaneCpuSampler.setEnabled(parsed.config().diagnostics().laneSampling());
        dev.iyanz.sourbycraft.execution.ResourceGovernor.GLOBAL.configure(parsed.config().scheduler());
        dev.iyanz.sourbycraft.perf.NetworkCounters.GLOBAL.setEnabled(parsed.config().network().counters());
        loaded = new LoadedConfig(utility, parsed.config());
        for (final String key : parsed.deprecatedKeys()) {
            SourbyLogger.warn("Deprecated config key '" + key + "'; use '"
                + AuroraConfig.ASYNC_PATH_KEY + "'. Operator file was not modified.");
        }
        for (final String key : parsed.invalidKeys()) {
            SourbyLogger.warn("Aurora config key '" + key
                + "' is invalid for its type (see the comment beside it in aurora.toml). Runtime fallback: "
                + "that key's default. "
                + "Operator file was not modified.");
        }
        return parsed;
    }

    /**
     * Reports an Aurora key that two files both set, where only one of them decides anything.
     *
     * <p>Giving Aurora its own file did not remove the keys already sitting in the unified one.
     * A deployment that has been through the split holds the same key twice, aurora.toml wins,
     * and an operator who edits the copy they happened to open watches nothing happen -- with no
     * error to explain why, because neither value is wrong on its own.</p>
     *
     * <p>Both files belong to the operator, so this says which one is being ignored rather than
     * deleting a line they wrote.</p>
     */
    private static void warnShadowedAuroraKeys(final ConfigSnapshot utility,
                                               final ConfigSnapshot aurora) {
        for (final String key : shadowedAuroraKeys(utility, aurora)) {
            SourbyLogger.warn("Config key '" + key + "' is set in both "
                + CONFIG_PATH + " (" + utility.values().get(key) + ") and " + AURORA_PATH
                + " (" + aurora.values().get(key) + "); " + AURORA_PATH.getFileName()
                + " wins. Remove the key from " + CONFIG_PATH.getFileName()
                + " so the value you edit is the value that runs. Neither file was modified.");
        }
    }

    /**
     * The Aurora keys both files set to different values, in file order.
     *
     * @param utility the unified file's snapshot
     * @param aurora  the Aurora file's snapshot, which wins
     * @return keys whose value in {@code utility} decides nothing
     */
    static List<String> shadowedAuroraKeys(final ConfigSnapshot utility,
                                           final ConfigSnapshot aurora) {
        final List<String> shadowed = new java.util.ArrayList<>();
        for (final var entry : aurora.values().entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith("aurora.")) {
                continue;
            }
            final Object other = utility.values().get(key);
            // Only report differing copies; Aurora still takes precedence when both values agree.
            if (other != null && !other.equals(entry.getValue())) {
                shadowed.add(key);
            }
        }
        return List.copyOf(shadowed);
    }

    private SourbyCraftConfig() {}

    // --------------------------------------------------------------------------------- lifecycle

    /**
     * Load (creating on first boot) the unified TOML, seed every operator-facing key that is
     * still absent, and push the loaded values into the small set of consumers that cache them in
     * static fields ({@link dev.iyanz.sourbycraft.update.AutoUpdateSettings}). Idempotent-ish:
     * safe to call once at boot; use {@link #reload()} afterwards.
     */
    public static synchronized void init() {
        CommentedFileConfig f = file();
        if (f == null) return;
        try {
            seedDefaults(f);
            seedAurora();          // Aurora's own file, seeded beside it rather than inside it.
        } catch (Throwable t) {
            SourbyLogger.error("seedDefaults failed; SourbyCraft will use hardcoded defaults", t);
        }
        loadSnapshot(f, auroraFile());
        applyLiveConfig(false);
        bootConfig = loaded;
    }

    /**
     * Re-read the unified TOML from disk and re-apply it live ({@code /sourbycraft reload}).
     * Returns a short human summary.
     */
    public static synchronized String reload() {
        ReloadReport report = reloadDetailed();
        return (report.successful() ? "reloaded — " : "reload FAILED/PARTIAL — ") + String.join("; ", report.lines());
    }

    public static synchronized ReloadReport reloadDetailed() {
        CommentedFileConfig f = FILE;
        if (f == null) return new ReloadReport(false, List.of("Utility config is not available."));
        List<String> errors = new java.util.ArrayList<>();
        try {
            f.load();
        } catch (Throwable t) {
            return new ReloadReport(false, List.of("Could not read " + CONFIG_PATH + ": " + t.getMessage()));
        }
        // A reload never writes defaults back to an existing file, including after a failed initial save.
        newFile = false;
        final CommentedFileConfig engineFile = auroraFile();
        if (engineFile == null) errors.add("Aurora file unavailable; using the utility layer and defaults.");
        else try {
            engineFile.load();
        } catch (Throwable t) {
            return new ReloadReport(false, List.of("Could not read " + AURORA_PATH + ": " + t.getMessage(),
                "No new config snapshot published; runtime retains its previously loaded settings."));
        }
        // Missing utility defaults are seeded in memory exactly as at boot; existing disk bytes stay unchanged.
        seedDefaults(f);
        AuroraConfig previous = aurora();
        AuroraConfig.Parsed parsed;
        try {
            parsed = loadSnapshot(f, engineFile);
            errors.addAll(applyLiveConfig(true));
        } catch (Throwable t) {
            SourbyLogger.error("config reload apply failed", t);
            return new ReloadReport(false, List.of("Config apply failed; some consumers may already have changed: " + t.getMessage()));
        }
        List<String> lines = new java.util.ArrayList<>();
        lines.add("Aurora LIVE: " + aurora().liveChangesComparedTo(previous) + " change(s) applied.");
        lines.add("LIVE: messages read on use, plugin page size and supported Aurora toggles.");
        lines.add("Updater fields re-read; an already scheduled interval requires restart.");
        lines.add("RESTART_REQUIRED (changes since boot): " + (pendingRestartKeys().isEmpty() ? "none in tracked keys" : String.join(", ", pendingRestartKeys())));
        lines.add("AWF settings and construction-cached engine options require restart; this report does not enumerate all legacy engine keys.");
        if (!parsed.invalidKeys().isEmpty()) errors.add("Invalid Aurora keys (default fallback): " + parsed.invalidKeys());
        lines.addAll(errors);
        SourbyLogger.info("config reload " + (errors.isEmpty() ? "completed" : "partial; inspect reported errors"));
        return new ReloadReport(errors.isEmpty(), lines);
    }

    private static List<String> applyLiveConfig(final boolean reloadEngine) {
        List<String> failures = new java.util.ArrayList<>();
        try {
            dev.iyanz.sourbycraft.update.AutoUpdateSettings.loadFromToml();
        } catch (Throwable t) {
            SourbyLogger.error("AutoUpdateSettings.loadFromToml failed", t);
            failures.add("Auto-updater settings could not be applied: " + t.getMessage());
        }

        // Legacy automatic memory tuning is retired. Preserve operator files and explain the change.
        if (cfgBool("perf.smart-swap.enabled", false) || cfgBool("swap.auto-create.enabled", false)) {
            SourbyLogger.warn("SmartSwap and automatic swap creation are retired in build 44. "
                + "JVM memory and OS swap remain operator-owned; legacy config keys were not modified.");
        }

        // The engine's own configuration, folded into /sourbycraft reload so an operator has one
        // command. Which engine that is stays behind the bridge: nothing here names it, so
        // replacing the implementation is a new bridge rather than an edit to this path.
        if (!reloadEngine) return List.copyOf(failures);
        for (final String failure : ENGINE_CONFIG.reload()) {
            failures.add(ENGINE_CONFIG.name() + " " + failure);
            SourbyLogger.error(ENGINE_CONFIG.name() + " " + failure
                + "; keeping the previous values", null);
        }
        return List.copyOf(failures);
    }

    /** The engine configuration SourbyCraft's reload also re-reads. */
    private static final dev.iyanz.sourbycraft.config.upstream.UpstreamConfigBridge ENGINE_CONFIG =
        new dev.iyanz.sourbycraft.config.upstream.CanvasConfigBridge();

    /**
     * Writes Aurora's defaults into its own file, once, when that file is new.
     *
     * <p>Only a newly created file is seeded. Writing into an existing one would mask the fallback
     * to the unified file, turning an operator's old setting into a default without them touching
     * anything.</p>
     */
    private static void seedAurora() {
        final CommentedFileConfig f = auroraFile();
        if (f == null || !newAuroraFile) {
            return;
        }
        final boolean[] changed = {false};
        seed(f, changed, AuroraConfig.ASYNC_PATH_KEY, false,
            "Aurora async pathfinding (LIVE). Experimental and default-off; requires region/snapshot qualification.");
        seed(f, changed, AuroraConfig.CPU_CORES_KEY, 0,
            "Processors Aurora may use in total, cores and hardware threads alike (RESTART_REQUIRED). "
            + "0 = every available processor. Counted as the JVM counts them, so a container CPU "
            + "quota is respected rather than the physical socket. A budget, not a reservation: a "
            + "region ticks on one thread, so threads past the number of separate active regions "
            + "idle. An explicit threaded-regions.threads in paper-global.yml still wins.");
        seed(f, changed, AuroraConfig.LANE_SAMPLING_KEY, true,
            "Aurora execution-lane CPU attribution (LIVE), behind /perf lanes. Walks every thread once a "
            + "second. Sampling has a cost that depends on the workload. false stops the sampling; the lanes view then reports it as disabled.");
        seed(f, changed, AuroraConfig.CHUNK_GENERATION_METRICS_KEY, false,
            "Generic chunk-generation stage timing behind /perf chunks (RESTART_REQUIRED). Read once at "
            + "startup; the JVM property -Dsourbycraft.chunk-generation-metrics.enabled=true also turns it on. "
            + "Times every non-empty generation stage, so it is off by default.");
        seed(f, changed, AuroraConfig.BRIDGE_MODE_KEY, "off",
            "Aurora Compatibility Bridge (RESTART_REQUIRED). off = plugins without folia-supported/canvas-supported "
            + "are refused, as before. safe = they load, their Bukkit scheduler tasks are routed through "
            + "the bridge (sync -> configured ownership route, async -> governed bridge I/O lane) and failures are recorded. "
            + "Legacy plugins remain unqualified code on a region-threaded server.");
        seed(f, changed, AuroraConfig.BRIDGE_QUARANTINE_KEY, 3,
            "Fatal bridge violations after which a bridged plugin is quarantined: its bridged tasks are "
            + "cancelled and new ones rejected (LIVE). At least 1.");
        seed(f, changed, AuroraConfig.BRIDGE_SYNC_ROUTE_KEY, "caller-region",
            "Where a bridged plugin's Bukkit sync task runs (LIVE). caller-region = on the region that was "
            + "ticking when it was scheduled (from a command or event), else the global region. global = always "
            + "the global region, where world access is refused. Ownership checks apply either way.");
        seed(f, changed, AuroraConfig.BRIDGE_MAX_PENDING_KEY, 0,
            "Maximum pending bridged tasks per plugin (LIVE). 0 = unlimited, otherwise 1..100000. "
            + "New submissions above the limit are cancelled and diagnosed; existing tasks drain normally.");
        seed(f, changed, AuroraConfig.BRIDGE_MAX_RUNNING_ASYNC_KEY, 0,
            "Maximum simultaneously running bridged async callbacks per plugin (LIVE). 0 = unlimited, "
            + "otherwise 1..100000. Over-limit callbacks cancel their task/timer and are diagnosed; "
            + "running bodies drain normally. This is rejection, not a queue or a fairness guarantee.");
        seed(f, changed, AuroraConfig.BRIDGE_IO_THREADS_KEY, 0,
            "Resource Governor: threads for bridged plugins' async tasks (RESTART_REQUIRED). 0 = max(2, processors / 4).");
        seed(f, changed, AuroraConfig.BRIDGE_IO_QUEUE_KEY, 256,
            "Resource Governor: queued bridged async tasks before new ones are rejected (RESTART_REQUIRED). At least 1.");
        seed(f, changed, AuroraConfig.STORAGE_THREADS_KEY, 1,
            "Resource Governor: threads for Aurora World Fabric commits (RESTART_REQUIRED). 0 = 1.");
        seed(f, changed, AuroraConfig.STORAGE_QUEUE_KEY, 64,
            "Resource Governor: queued AWF commits before new ones are rejected (RESTART_REQUIRED). At least 1.");
        seed(f, changed, AuroraConfig.NETWORK_COUNTERS_KEY, true,
            "Count wire bytes and packets per direction for /perf network (LIVE). The per-packet cost is "
            + "not measured; false stops counting.");
        seed(f, changed, dev.iyanz.sourbycraft.awf.AwfSettings.WORLDS_KEY, new java.util.ArrayList<String>(),
            "Aurora World Fabric (RESTART_REQUIRED). World folder names whose chunk, entity and POI data AWF stores. "
            + "Empty = none. Existing region files stay the read-only base; chunks written afterwards go "
            + "to <folder>.awf beside each region folder. Writes are durable only after a commit (every "
            + "commit-interval-seconds, on save-all flush and on shutdown): a crash loses the last interval. "
            + "Once a store exists it stays in use even if the world is removed from this list; use export "
            + "to leave AWF.");
        seed(f, changed, dev.iyanz.sourbycraft.awf.AwfSettings.EXPORT_KEY, new java.util.ArrayList<String>(),
            "Aurora World Fabric export (RESTART_REQUIRED). World folder names to move back to region files at the next "
            + "load: every chunk and deletion in the store is written to .mca, then the store is renamed to "
            + "<folder>.awf.exported-<time> (kept, not deleted). A world must not also be in worlds.");
        seed(f, changed, dev.iyanz.sourbycraft.awf.AwfSettings.BACKEND_KEY, "file",
            "AWF backend (RESTART_REQUIRED). file = a directory beside each region folder, the only one shipped. Another "
            + "name selects a backend registered by server-side code before worlds load; if it is not registered, "
            + "listed worlds fail to load rather than falling back.");
        seed(f, changed, dev.iyanz.sourbycraft.awf.AwfSettings.PERSISTENCE_KEY, "incremental",
            "AWF commit mode (RESTART_REQUIRED): incremental (new objects only), checkpoint (also re-verifies every "
            + "referenced object), full (rewrites every object).");
        seed(f, changed, dev.iyanz.sourbycraft.awf.AwfSettings.COMMIT_INTERVAL_KEY, 30,
            "AWF: seconds a written chunk may wait in memory before a commit starts (RESTART_REQUIRED). 1-86400.");
        seed(f, changed, dev.iyanz.sourbycraft.awf.AwfSettings.RESIDENT_CHUNKS_KEY, 1024,
            "AWF: committed chunks kept in memory per storage (RESTART_REQUIRED). Unsaved chunks are never dropped.");
        seed(f, changed, dev.iyanz.sourbycraft.awf.AwfSettings.RETAINED_GENERATIONS_KEY, 3,
            "AWF: committed generations kept for recovery (RESTART_REQUIRED).");
        seed(f, changed, dev.iyanz.sourbycraft.awf.AwfSettings.COMMIT_ATTEMPTS_KEY, 3,
            "AWF: attempts per commit before it fails and its chunks stay in memory for the next (RESTART_REQUIRED).");
        if (changed[0]) {
            f.save();
            SourbyLogger.info("seeded Aurora engine defaults into sourbycraft_config/aurora.toml");
        }
    }

    private static synchronized CommentedFileConfig auroraFile() {
        if (AURORA_FILE != null) return AURORA_FILE;
        try {
            if (AURORA_PATH.getParent() != null) {
                Files.createDirectories(AURORA_PATH.getParent());
            }
            newAuroraFile = !Files.exists(AURORA_PATH);
            final CommentedFileConfig f = CommentedFileConfig.builder(AURORA_PATH)
                .sync() // save() completes before reporting persistence or allowing reload
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .build();
            f.load();
            AURORA_FILE = f;
        } catch (Throwable t) {
            SourbyLogger.error("could not open sourbycraft_config/aurora.toml; Aurora settings "
                + "will be read from the unified file and its defaults", t);
        }
        return AURORA_FILE;
    }

    private static synchronized CommentedFileConfig file() {
        if (FILE != null) return FILE;
        try {
            if (CONFIG_PATH.getParent() != null) {
                Files.createDirectories(CONFIG_PATH.getParent());
            }
            newFile = !Files.exists(CONFIG_PATH);
            CommentedFileConfig f = CommentedFileConfig.builder(CONFIG_PATH)
                .sync() // save() completes before reporting persistence or allowing reload
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .build();
            f.load();
            FILE = f;
        } catch (Throwable t) {
            SourbyLogger.error("could not open sourbycraft_config/sourbycraft_global_config.toml; "
                + "every read will fall back to its default", t);
        }
        return FILE;
    }

    // -------------------------------------------------------------------------------- typed reads

    /** Read a value from the unified TOML by dotted path. Falls back to {@code defaultValue}. */
    @SuppressWarnings("unchecked")
    public static <T> T cfgGet(String dottedPath, T defaultValue) {
        Object v = lookup(dottedPath);
        if (v != null) {
            if (defaultValue == null || defaultValue.getClass().isInstance(v)) {
                return (T) v;
            }
            warnOnce(dottedPath, v, defaultValue.getClass().getSimpleName());
        }
        return defaultValue;
    }

    /** Read a boolean by dotted path. Falls back to {@code defaultValue} when absent or not a boolean. */
    public static boolean cfgBool(String dottedPath, boolean defaultValue) {
        Object v = lookup(dottedPath);
        if (v instanceof Boolean b) return b;
        if (v != null) warnOnce(dottedPath, v, "boolean");
        return defaultValue;
    }

    /** Read an int by dotted path. Accepts any {@link Number}; falls back to {@code defaultValue} otherwise. */
    public static int cfgInt(String dottedPath, int defaultValue) {
        Object v = lookup(dottedPath);
        if (v instanceof Number n) return n.intValue();
        if (v != null) warnOnce(dottedPath, v, "int");
        return defaultValue;
    }

    /** Read a double by dotted path. Accepts any {@link Number}; falls back to {@code defaultValue} otherwise. */
    public static double cfgDouble(String dottedPath, double defaultValue) {
        Object v = lookup(dottedPath);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) warnOnce(dottedPath, v, "double");
        return defaultValue;
    }

    /** Reads a list of strings; empty when the key is missing or not a list. */
    public static java.util.List<String> cfgStringList(String dottedPath) {
        Object v = lookup(dottedPath);
        if (!(v instanceof java.util.List<?> raw)) {
            if (v != null) warnOnce(dottedPath, v, "List<String>");
            return java.util.List.of();
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>(raw.size());
        int idx = 0;
        for (Object item : raw) {
            if (item instanceof String s) out.add(s);
            else warnOnce(dottedPath + "[" + idx + "]", item, "String");
            idx++;
        }
        return java.util.Collections.unmodifiableList(out);
    }

    private static Object lookup(String dottedPath) {
        return loaded.utility().values().get(dottedPath);
    }

    // ------------------------------------------------------------------------------- typed writes

    /**
     * Set + persist a single key (e.g. {@code /maxp <n>}). Never throws. Returns {@code true} on a
     * successful write+save.
     */
    public static synchronized boolean setAndSave(String dottedPath, Object value, String commentIfNew) {
        CommentedFileConfig f = file();
        if (f == null) return false;
        try {
            f.set(dottedPath, value);
            if (commentIfNew != null && f.getComment(dottedPath) == null) {
                f.setComment(dottedPath, commentIfNew);
            }
            f.save();
            loaded = new LoadedConfig(ConfigSnapshot.copyOf(f), loaded.aurora());
            return true;
        } catch (Throwable t) {
            SourbyLogger.warn("could not persist " + dottedPath + ": " + t.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------------------------ seeding

    /**
     * Seed every operator-facing SourbyCraft key, when absent. Aurora's own keys are seeded
     * separately into its own file by {@code seedAurora()}, and never here
     * (never clobbers an operator edit). Saves once at the end if anything changed.
     */
    private static void seedDefaults(CommentedFileConfig f) {
        boolean[] changed = {false};
        seed(f, changed, "ui.console-style", "auto",
            "RESTART_REQUIRED. Startup console: auto (rich on interactive terminals), plain (no ANSI), rich (Unicode + color). -Dsourbycraft.console overrides this; NO_COLOR disables color.");
        seed(f, changed, "ui.plugins-page-size", 12,
            "LIVE after /sourbycraft reload. Rows per /plugins or /pl page; clamped to 1-40.");

        seed(f, changed, "branding.gc-advisor.enabled", true,
            "RESTART_REQUIRED. Enable the startup GC/JVM-flags advisory log.");

        seed(f, changed, "sourbycraft.max-players", 0,
            "RESTART_REQUIRED when editing this file; /maxp applies its own change immediately. Server slot count. Re-applied at boot so it wins over server.properties. 0 = use server.properties.");
        seed(f, changed, "sourbycraft.maxplayers.bypass-enabled", false,
            "RESTART_REQUIRED. Let sourbycraft.maxplayers.bypass holders (+ ops) join a full server. Default false: OFF keeps"
            + " the fast config-phase join path; enabling registers a PlayerLoginEvent listener that"
            + " disables that fast path and slows every join.");

        seed(f, changed, "viaversion.auto-provision", true,
            "RESTART_REQUIRED. Provision verified ViaVersion/ViaBackwards jars and default configs before plugin loading. false = manage Via yourself.");
        seed(f, changed, "protocollib.auto-provision", true,
            "RESTART_REQUIRED. Install the pinned official ProtocolLib plugin before the plugin scan. Existing operator jars/configs are preserved. false disables managed installation; private native Intave skips this external plugin to keep its packet backend isolated.");

        dev.iyanz.sourbycraft.lang.SourbyMessages.seedDefaults(f, changed);
        dev.iyanz.sourbycraft.update.AutoUpdateSettings.seedDefaults(f, changed);

        if (changed[0] && newFile) {
            try {
                f.save();
                newFile = false;
                SourbyLogger.info("seeded defaults into sourbycraft_config/sourbycraft_global_config.toml");
            } catch (Throwable t) {
                SourbyLogger.warn("could not save unified config after seeding: " + t.getMessage());
            }
        }
    }

    /** Shared seed helper — writes {@code path}=<def> + an optional comment only when absent. */
    public static void seed(CommentedFileConfig f, boolean[] changed, String path, Object def, String comment) {
        if (!f.contains(path)) {
            f.add(path, def);
            if (comment != null) f.setComment(path, comment);
            changed[0] = true;
        }
    }

    // ------------------------------------------------------------------------------- diagnostics

    // Once-per-startup WARN dedupe so a single malformed key doesn't spam the log.
    private static final java.util.Set<String> WARNED_KEYS = ConcurrentHashMap.newKeySet();

    private static void warnOnce(String path, Object actual, String expected) {
        if (WARNED_KEYS.add(path)) {
            SourbyLogger.warn("config key '" + path + "' invalid type '" + actual.getClass().getSimpleName()
                + "', expected " + expected + " — using default");
        }
    }
}
