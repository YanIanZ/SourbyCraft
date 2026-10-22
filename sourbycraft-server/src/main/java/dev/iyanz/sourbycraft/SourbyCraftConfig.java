package dev.iyanz.sourbycraft;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.AuroraKeys;
import dev.iyanz.sourbycraft.config.ConfigKey;
import dev.iyanz.sourbycraft.config.ConfigMigration;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import dev.iyanz.sourbycraft.config.UtilityKeys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SourbyCraft's configuration registry, and the boundary between its settings and Aurora's.
 *
 * <p>Operator-facing settings live in two files, not one. SourbyCraft's own -- the console, player
 * slots, varied messages, plugin provisioning and the auto-updater -- are in
 * {@code sourbycraft_config/sourbycraft_global_config.toml} ({@link UtilityKeys} is the table of
 * every key), read through the typed {@link #cfgBool}/{@link #cfgInt}/{@link #cfgGet}/{@link #cfgStringList}
 * accessors below. Aurora's are in {@code sourbycraft_config/aurora.toml} ({@link AuroraKeys}): the
 * engine and the server are different things, and an operator tuning the engine should not have to
 * read past join messages to find it.</p>
 *
 * <p>Build 49 relaid the utility file ({@code [console]}, {@code [players]}, {@code [messages]},
 * {@code [plugins.*]}, {@code [updater]}). {@link ConfigMigration} rewrites an older file once at
 * boot, after copying it to {@code *.migrated}, and resolves old key names on every read until Build
 * 50 (one WARN per old key per load). The Aurora file is still layered <em>over</em> the unified one
 * for Aurora keys, so a deployment whose migration could not run (read-only directory) keeps the
 * value it had; where both files set the same Aurora key, aurora.toml wins and
 * {@link #shadowedAuroraKeys} reports the copy that decides nothing.</p>
 *
 * <p>Both files use their own {@code nightconfig} {@link CommentedFileConfig}, resolved here
 * directly, with no upstream config-manager dependency.</p>
 *
 * <p><b>History.</b> An earlier version of this class was roughly a thousand lines, because it
 * also seeded and bridged a self-tuning performance engine, an anti-xray reveal layer, and a
 * proxy-forwarding and hardening-advisor security layer. Those were dropped rather than ported:
 * their config trees, {@code seed()} calls and live-apply bridges went with them. Since build
 * 44 this publishes immutable snapshots at explicit load boundaries and read-only performance
 * diagnostics. The retired memory-tuning keys are removed from the file by the Build 49 migration.</p>
 */
public final class SourbyCraftConfig {

    private static final Path CONFIG_PATH = Path.of("sourbycraft_config", "sourbycraft_global_config.toml");
    /**
     * The file {@link #file()} opens. Always {@link #CONFIG_PATH} in production; only the test seam
     * ({@link #installForTesting}) ever points it elsewhere.
     */
    private static volatile Path utilityPath = CONFIG_PATH;
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
    /** Set while {@link #init} rewrites an old-layout file, so the seeded defaults are written into it too. */
    private static boolean migratedUtilityFile;
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

    /** The utility keys whose change is only noticed by a new process; compared against the boot snapshot. */
    private static final List<String> RESTART_TRACKED_UTILITY_KEYS = List.of(
        UtilityKeys.CONSOLE_STYLE.path(), UtilityKeys.GC_ADVISOR.path(),
        UtilityKeys.MAX_PLAYERS.path(), UtilityKeys.FULL_SERVER_BYPASS.path(),
        UtilityKeys.VIAVERSION_AUTO_PROVISION.path(), UtilityKeys.PROTOCOLLIB_AUTO_PROVISION.path(),
        "messages.motd",
        UtilityKeys.UPDATER_ENABLED.path(), UtilityKeys.UPDATER_CHECK_TIMES.path(),
        UtilityKeys.UPDATER_CHECK_INTERVAL_MINUTES.path());

    static List<String> restartChanges(ConfigSnapshot before, AuroraConfig beforeAurora,
                                       ConfigSnapshot after, AuroraConfig afterAurora) {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        if (!beforeAurora.cpu().equals(afterAurora.cpu())) keys.add(AuroraConfig.CPU_CORES_KEY);
        if (beforeAurora.bridge().mode() != afterAurora.bridge().mode()) keys.add(AuroraConfig.BRIDGE_MODE_KEY);
        if (!beforeAurora.scheduler().equals(afterAurora.scheduler())) keys.add("aurora.scheduler.*");
        if (!beforeAurora.diagnostics().stallRecorder().equals(afterAurora.diagnostics().stallRecorder())) {
            keys.add("aurora.diagnostics.stall-recorder.*");
        }
        for (String key : RESTART_TRACKED_UTILITY_KEYS) {
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
        // Old key names resolve to the current ones on every read (until Build 50), whether or not
        // the file on disk has been rewritten; the operator hears about each one once per load.
        final ConfigMigration.Normalized normalized = ConfigMigration.normalize(ConfigSnapshot.copyOf(file));
        for (final String old : normalized.deprecatedUsed()) {
            SourbyLogger.warn("Deprecated config key '" + old + "' in " + UtilityKeys.FILE + " is read as '"
                + UtilityKeys.legacyToCurrent().get(old) + "' until " + ConfigMigration.DEPRECATION_END
                + "; rename it (the Build 49 migration does this when it can write the file).");
        }
        for (final String old : normalized.staleIgnored()) {
            SourbyLogger.warn("Config key '" + old + "' in " + UtilityKeys.FILE + " is ignored: its current name '"
                + UtilityKeys.legacyToCurrent().get(old) + "' is set too and wins. Remove the old key.");
        }
        final ConfigSnapshot utility = normalized.values();
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
        dev.iyanz.sourbycraft.commandblock.CommandBlockPolicy.setEnabled(parsed.config().world().commandBlocks());
        dev.iyanz.sourbycraft.world.DatapackFunctions.setTickFunctions(parsed.config().world().tickFunctions());
        loaded = new LoadedConfig(utility, parsed.config());
        for (final String key : parsed.deprecatedKeys()) {
            SourbyLogger.warn("Deprecated config key '" + key + "' is read as '" + AuroraConfig.ASYNC_PATH_KEY
                + "' until " + ConfigMigration.DEPRECATION_END + "; move it to " + AuroraKeys.FILE
                + " under that name. Operator file was not modified.");
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
     * The Build 49 migration moves them, but a deployment whose file could not be rewritten still
     * holds the same key twice; aurora.toml wins, and an operator who edits the copy they happened
     * to open watches nothing happen -- with no error to explain why, because neither value is
     * wrong on its own.</p>
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
     * Load (creating on first boot) the unified TOML, bring an older layout to the Build 49 one
     * (once, with a {@code *.migrated} copy), seed every operator-facing key that is still absent,
     * and push the loaded values into the small set of consumers that cache them in static fields
     * ({@link dev.iyanz.sourbycraft.update.AutoUpdateSettings}). Idempotent-ish: safe to call once
     * at boot; use {@link #reload()} afterwards.
     */
    public static synchronized void init() {
        CommentedFileConfig f = file();
        if (f == null) return;
        try {
            migrateLayout(f, auroraFile());
        } catch (Throwable t) {
            SourbyLogger.error("configuration layout migration failed; the files were not changed and the old "
                + "key names are still read (one WARN each)", t);
        }
        try {
            seedDefaults(f);
            seedAurora();          // Aurora's own file, seeded beside it rather than inside it.
        } catch (Throwable t) {
            SourbyLogger.error("seedDefaults failed; SourbyCraft will use hardcoded defaults", t);
        }
        migratedUtilityFile = false;
        loadSnapshot(f, auroraFile());
        applyLiveConfig(false);
        bootConfig = loaded;
    }

    /**
     * The one-time Build 49 relayout of the files on disk ({@link ConfigMigration}). A new (empty)
     * utility file has nothing to migrate.
     */
    private static void migrateLayout(final CommentedFileConfig f, final CommentedFileConfig aurora) throws java.io.IOException {
        if (newFile) return;
        final ConfigMigration.Plan plan = ConfigMigration.plan(f, aurora);
        if (plan.isEmpty()) return;
        final ConfigMigration.Report report = ConfigMigration.apply(plan, f, utilityPath, aurora, AURORA_PATH);
        migratedUtilityFile = plan.touchesUtility();
        SourbyLogger.info(report.summary());
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
        migratedUtilityFile = false;
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
        lines.add("AWF settings and construction-cached engine options require restart; this report does not enumerate every engine key.");
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

        try {
            dev.iyanz.sourbycraft.security.SecurityGuards.INSTANCE.load(java.nio.file.Path.of("sourbycraft-security.yml"));
        } catch (Exception failure) {
            SourbyLogger.error("Security limits could not be applied; keeping previous values", failure);
            failures.add("Security limits could not be applied: " + failure.getMessage());
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
     * Writes Aurora's defaults into its own file, once, when that file is new: every key of
     * {@link AuroraKeys} with its documentation, under a header and section comments.
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
        for (final ConfigKey key : AuroraKeys.KEYS) {
            seed(f, changed, key.path(), copyOfDefault(key.defaultValue()), key.documentation());
        }
        for (final UtilityKeys.Section section : AuroraKeys.SECTIONS) {
            if (f.getComment(section.path()) == null) f.setComment(section.path(), section.comment());
        }
        if (changed[0]) {
            f.save();
            newAuroraFile = false;
            SourbyLogger.info("seeded Aurora defaults into " + AuroraKeys.FILE);
        }
    }

    /** nightconfig keeps the list object it is handed; a shared immutable default must not be it. */
    private static Object copyOfDefault(final Object value) {
        return value instanceof List<?> list ? new java.util.ArrayList<>(list) : value;
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
                .preserveInsertionOrder() // the file keeps the operator's (or the seeder's) order
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
        final Path path = utilityPath;
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            newFile = !Files.exists(path);
            CommentedFileConfig f = CommentedFileConfig.builder(path)
                .sync() // save() completes before reporting persistence or allowing reload
                .preserveInsertionOrder() // the file keeps the operator's (or the seeder's) order
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

    // ------------------------------------------------------------------------------- test seam

    /**
     * Test seam: publishes {@code utility} as the utility snapshot and points the utility file at
     * {@code path}, so tests neither swap the private {@code loaded} field by reflection nor
     * write {@code sourbycraft_config/} in the working directory. Package-private; tests in other
     * packages reach it through the {@code SourbyCraftConfigTesting} helper in the test tree.
     * Production code never calls it, so every production path keeps {@link #CONFIG_PATH}.
     *
     * <p>The utility file handle is dropped, so the next {@link #setAndSave} (or {@link #init})
     * opens {@code path}. The Aurora layer and the boot snapshot are left alone. Closing the
     * returned handle restores everything it replaced, including the previous file handle.</p>
     *
     * @param utility the snapshot {@link #cfgGet} and its siblings should read
     * @param path    the utility TOML file {@link #setAndSave} should write
     * @return a handle that restores the previous state; use it in try-with-resources
     */
    static synchronized AutoCloseable installForTesting(final ConfigSnapshot utility, final Path path) {
        final LoadedConfig previousLoaded = loaded;
        final CommentedFileConfig previousFile = FILE;
        final Path previousPath = utilityPath;
        final boolean previousNewFile = newFile;
        utilityPath = path;
        FILE = null;
        newFile = false;
        loaded = new LoadedConfig(utility, previousLoaded.aurora());
        return () -> {
            synchronized (SourbyCraftConfig.class) {
                final CommentedFileConfig opened = FILE;
                if (opened != null && opened != previousFile) {
                    opened.close();
                }
                loaded = previousLoaded;
                FILE = previousFile;
                utilityPath = previousPath;
                newFile = previousNewFile;
            }
        };
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
            loaded = new LoadedConfig(ConfigMigration.normalize(ConfigSnapshot.copyOf(f)).values(), loaded.aurora());
            return true;
        } catch (Throwable t) {
            SourbyLogger.warn("could not persist " + dottedPath + ": " + t.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------------------------ seeding

    /**
     * Seed every operator-facing SourbyCraft key ({@link UtilityKeys}, then the message lists),
     * when absent, with its documentation, plus the header and section comments. Aurora's own keys
     * are seeded separately into its own file by {@code seedAurora()}, and never here (never
     * clobbers an operator edit). Saves once at the end when the file is new or was just migrated;
     * an existing file is seeded in memory only.
     */
    private static void seedDefaults(CommentedFileConfig f) {
        boolean[] changed = {false};
        // File order of the sections: console, players, messages, plugins, updater.
        boolean messagesSeeded = false;
        for (final ConfigKey key : UtilityKeys.KEYS) {
            if (!messagesSeeded && !key.table().equals("console") && !key.table().equals("players")) {
                dev.iyanz.sourbycraft.lang.SourbyMessages.seedDefaults(f, changed);
                messagesSeeded = true;
            }
            seed(f, changed, key.path(), copyOfDefault(key.defaultValue()), key.documentation());
        }
        if (!messagesSeeded) dev.iyanz.sourbycraft.lang.SourbyMessages.seedDefaults(f, changed);
        if (newFile || migratedUtilityFile) {
            for (final UtilityKeys.Section section : UtilityKeys.SECTIONS) {
                if (f.getComment(section.path()) == null) {
                    f.setComment(section.path(), section.comment());
                    changed[0] = true;
                }
            }
        }

        if (changed[0] && (newFile || migratedUtilityFile)) {
            try {
                f.save();
                newFile = false;
                SourbyLogger.info("seeded defaults into " + UtilityKeys.FILE);
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
