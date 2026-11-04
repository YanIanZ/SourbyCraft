package dev.iyanz.sourbycraft;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.AuroraKeys;
import dev.iyanz.sourbycraft.config.ConfigFormatMigration;
import dev.iyanz.sourbycraft.config.ConfigKey;
import dev.iyanz.sourbycraft.config.ConfigMigration;
import dev.iyanz.sourbycraft.config.ConfigParseErrors;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;
import dev.iyanz.sourbycraft.config.ConfigSources;
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
 * {@code sourbycraft_config/sourbycraft_global_config.yml} ({@link UtilityKeys} is the table of
 * every key), read through the typed {@link #cfgBool}/{@link #cfgInt}/{@link #cfgGet}/{@link #cfgStringList}
 * accessors below. Aurora's are in {@code sourbycraft_config/aurora.yml} ({@link AuroraKeys}): the
 * engine and the server are different things, and an operator tuning the engine should not have to
 * read past join messages to find it.</p>
 *
 * <p>The files are YAML ({@code config.yaml.YamlFormat}: SnakeYAML values, comments kept). An
 * existing {@code *.toml} is converted once at boot by {@link ConfigFormatMigration} and renamed
 * {@code *.toml.migrated}; a {@code *.yml} always wins (a {@code *.toml} left beside it is named in a
 * WARN), and an unparseable old file is left alone and named with file + near-line. Build 49 relaid the
 * utility file ({@code console}, {@code players}, {@code messages}, {@code plugins.*}, {@code updater}).
 * {@link ConfigMigration} rewrites an older file once at boot, after copying it to {@code *.migrated}.
 * Since Build 50 an old key name that rewrite could not remove is not read, and neither is a key no
 * table documents: each gets one WARN per load. The Aurora file is still layered <em>over</em> the
 * unified one for Aurora keys, so a deployment whose migration could not run (read-only directory) keeps
 * the value it had; where both files set the same Aurora key, aurora.yml wins and
 * {@link #shadowedAuroraKeys} reports the copy that decides nothing.</p>
 *
 * <p>Both files use their own {@code nightconfig} {@link CommentedFileConfig} with a format chosen by
 * extension, resolved here directly, with no upstream config-manager dependency.</p>
 *
 * <p><b>History.</b> An earlier version of this class was roughly a thousand lines, because it
 * also seeded and bridged a self-tuning performance engine, an anti-xray reveal layer, and a
 * proxy-forwarding and hardening-advisor security layer. Those were dropped rather than ported:
 * their config trees, {@code seed()} calls and live-apply bridges went with them. Since build
 * 44 this publishes immutable snapshots at explicit load boundaries and read-only performance
 * diagnostics. The retired memory-tuning keys are removed from the file by the Build 49 migration.</p>
 */
public final class SourbyCraftConfig {

    private static final Path CONFIG_PATH = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");
    /** The pre-YAML file, converted once at boot and kept as {@code *.toml.migrated}. */
    private static final Path LEGACY_CONFIG_PATH = Path.of("sourbycraft_config", "sourbycraft_global_config.toml");
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
    private static final Path AURORA_PATH = Path.of("sourbycraft_config", "aurora.yml");
    private static final Path LEGACY_AURORA_PATH = Path.of("sourbycraft_config", "aurora.toml");
    /** What {@link #file()} and {@link #auroraFile()} actually opened (the legacy TOML in a read-only directory). */
    private static volatile Path activeUtilityPath = CONFIG_PATH;
    private static volatile Path activeAuroraPath = AURORA_PATH;

    private static volatile CommentedFileConfig FILE;
    private static volatile CommentedFileConfig AURORA_FILE;
    private static boolean newFile;
    private static boolean newAuroraFile;
    /** Set while {@link #init} rewrites an old-layout file, so the seeded defaults are written into it too. */
    private static boolean migratedUtilityFile;
    /**
     * One published load: the utility snapshot (old key names left out), the parsed Aurora settings, and the
     * flattened Aurora layer they were parsed from (the utility file under the Aurora file), which the restart
     * report compares key by key.
     */
    private record LoadedConfig(ConfigSnapshot utility, AuroraConfig aurora, ConfigSnapshot auroraLayer) {}
    private static final ConfigSnapshot EMPTY = new ConfigSnapshot(java.util.Map.of());
    private static volatile LoadedConfig loaded = new LoadedConfig(EMPTY, AuroraConfig.DEFAULT, EMPTY);

    private static volatile LoadedConfig bootConfig;

    public record ReloadReport(boolean successful, List<String> lines) {
        public ReloadReport { lines = List.copyOf(lines); }
    }

    /** Restart changes remain pending across repeated reloads until a new process starts. */
    public static List<String> pendingRestartKeys() {
        final LoadedConfig boot = bootConfig;
        if (boot == null) return List.of();
        final LoadedConfig now = loaded;
        return restartChanges(boot.utility(), boot.aurora(), boot.auroraLayer(),
            now.utility(), now.aurora(), now.auroraLayer());
    }

    /**
     * The utility keys whose change is only noticed by a new process: every RESTART_REQUIRED key of
     * {@link UtilityKeys}, plus the MOTD (picked once at boot; its key belongs to SourbyMessages).
     */
    private static final List<String> RESTART_TRACKED_UTILITY_KEYS = restartTrackedUtilityKeys();
    /**
     * The RESTART_REQUIRED Aurora keys compared on their value in the flattened Aurora layer: every such key of
     * {@link AuroraKeys} and the unseeded Redis backend keys, except those {@link #restartChanges} compares on the
     * parsed settings.
     */
    private static final List<String> RESTART_TRACKED_AURORA_KEYS = restartTrackedAuroraKeys();

    private static List<String> restartTrackedUtilityKeys() {
        final List<String> keys = new java.util.ArrayList<>();
        for (final ConfigKey key : UtilityKeys.KEYS) {
            if (key.lifecycle() == AuroraConfig.Lifecycle.RESTART_REQUIRED) keys.add(key.path());
        }
        keys.add("messages.motd");
        return List.copyOf(keys);
    }

    private static List<String> restartTrackedAuroraKeys() {
        final List<String> keys = new java.util.ArrayList<>();
        for (final ConfigKey key : AuroraKeys.KEYS) {
            if (key.lifecycle() == AuroraConfig.Lifecycle.RESTART_REQUIRED) keys.add(key.path());
        }
        keys.addAll(AuroraKeys.UNSEEDED_PATHS);
        keys.removeIf(SourbyCraftConfig::comparedOnParsedSettings);
        return List.copyOf(keys);
    }

    /**
     * The keys {@link #restartChanges} compares on the parsed {@link AuroraConfig}: there a typo that falls back to
     * the default value is not reported as a change.
     */
    private static boolean comparedOnParsedSettings(final String key) {
        return key.equals(AuroraConfig.CPU_CORES_KEY) || key.equals(AuroraConfig.BRIDGE_MODE_KEY)
            || key.equals(AuroraConfig.BRIDGE_THREAD_CHECKS_KEY) || key.startsWith("aurora.scheduler.")
            || key.startsWith("aurora.diagnostics.stall-recorder.");
    }

    /**
     * The restart-only changes on the parsed Aurora settings and the utility snapshots alone, without the
     * flattened Aurora layers (so neither chunk-generation metrics nor any AWF, MultiProfile, Redis or
     * AbyssLink key is compared).
     */
    static List<String> restartChanges(ConfigSnapshot before, AuroraConfig beforeAurora,
                                       ConfigSnapshot after, AuroraConfig afterAurora) {
        return restartChanges(before, beforeAurora, EMPTY, after, afterAurora, EMPTY);
    }

    /**
     * The RESTART_REQUIRED keys that differ between two loads, sorted. The CPU budget, bridge mode and thread
     * checks, scheduler budgets and stall recorder are compared on their parsed values; every other
     * RESTART_REQUIRED Aurora key (chunk-generation metrics, AWF, MultiProfile, the Redis Fabric and backend,
     * AbyssLink) on its value in the flattened Aurora layer; the utility keys on the utility snapshot.
     */
    static List<String> restartChanges(final ConfigSnapshot before, final AuroraConfig beforeAurora,
                                       final ConfigSnapshot beforeLayer, final ConfigSnapshot after,
                                       final AuroraConfig afterAurora, final ConfigSnapshot afterLayer) {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        if (!beforeAurora.cpu().equals(afterAurora.cpu())) keys.add(AuroraConfig.CPU_CORES_KEY);
        if (beforeAurora.bridge().mode() != afterAurora.bridge().mode()) keys.add(AuroraConfig.BRIDGE_MODE_KEY);
        if (beforeAurora.bridge().threadChecks() != afterAurora.bridge().threadChecks()) keys.add(AuroraConfig.BRIDGE_THREAD_CHECKS_KEY);
        if (!beforeAurora.scheduler().equals(afterAurora.scheduler())) keys.add("aurora.scheduler.*");
        if (!beforeAurora.diagnostics().stallRecorder().equals(afterAurora.diagnostics().stallRecorder())) {
            keys.add("aurora.diagnostics.stall-recorder.*");
        }
        for (final String key : RESTART_TRACKED_AURORA_KEYS) {
            if (!java.util.Objects.equals(beforeLayer.values().get(key), afterLayer.values().get(key))) keys.add(key);
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
     * @param file       the unified utility file, or {@code null} when it could not be opened (its keys then
     *                   take their defaults; the Aurora file still applies)
     * @param auroraFile Aurora's own file, or {@code null} when there is none to read
     */
    private static AuroraConfig.Parsed loadSnapshot(final CommentedFileConfig file,
                                                    final CommentedFileConfig auroraFile) {
        // Since Build 50 an old key name is not read. The boot migration renames it when it can write
        // the file; one it could not is left out of the snapshot, and the operator hears about it once per load.
        final ConfigMigration.Stripped stripped =
            ConfigMigration.stripLegacyNames(file == null ? EMPTY : ConfigSnapshot.copyOf(file));
        final ConfigSnapshot utility = stripped.values();
        for (final String old : stripped.ignored()) {
            // No "is the current key set?" here: seedDefaults has already put every current key in memory.
            SourbyLogger.warn("Config key '" + old + "' in " + activeUtilityPath + " is ignored since "
                + ConfigMigration.LEGACY_READS_REMOVED + "; only its current name '" + UtilityKeys.legacyToCurrent().get(old)
                + "' is read (its default applies when the file does not set it). Rename the key; the configuration "
                + "migration does this at boot when it can write the file.");
        }
        // Legacy first, Aurora's own file over it: a server that has never seen aurora.yml keeps
        // the value it already had, and one that has takes the new file's.
        final ConfigSnapshot aurora = auroraFile == null ? null : ConfigSnapshot.copyOf(auroraFile);
        warnUnknownKeys(utility, aurora);
        if (aurora != null) {
            warnShadowedAuroraKeys(utility, aurora);
        }
        final ConfigSnapshot auroraLayer = aurora == null ? utility : ConfigSnapshot.layered(utility, aurora);
        final AuroraConfig.Parsed parsed = AuroraConfig.parse(auroraLayer);
        // Apply before publication: a failed runtime activation must not report success.
        dev.iyanz.sourbycraft.perf.AsyncPathProcessor.setEnabled(parsed.config().entity().asyncPathfinding());
        dev.iyanz.sourbycraft.execution.LaneCpuSampler.setEnabled(parsed.config().diagnostics().laneSampling());
        dev.iyanz.sourbycraft.execution.ResourceGovernor.GLOBAL.configure(parsed.config().scheduler());
        dev.iyanz.sourbycraft.perf.NetworkCounters.GLOBAL.setEnabled(parsed.config().network().counters());
        dev.iyanz.sourbycraft.commandblock.CommandBlockPolicy.setEnabled(parsed.config().world().commandBlocks());
        dev.iyanz.sourbycraft.world.DatapackFunctions.setTickFunctions(parsed.config().world().tickFunctions());
        loaded = new LoadedConfig(utility, parsed.config(), auroraLayer);
        for (final String key : parsed.deprecatedKeys()) {
            SourbyLogger.warn("Config key '" + key + "' is ignored since " + ConfigMigration.LEGACY_READS_REMOVED
                + "; set '" + AuroraKeys.currentPath(key) + "' in " + activeAuroraPath + " instead (the configuration "
                + "migration moves it at boot when it can write the files). Runtime fallback: that key's default. "
                + "Operator file was not modified.");
        }
        for (final String key : parsed.invalidKeys()) {
            SourbyLogger.warn("Aurora config key '" + key
                + "' is invalid for its type (see the comment beside it in " + activeAuroraPath + "). Runtime fallback: "
                + "that key's default. "
                + "Operator file was not modified.");
        }
        return parsed;
    }

    /**
     * One WARN per key, per load, that no table documents: a misspelled or misplaced key is otherwise dropped
     * without a word while its default runs. Old key names are reported separately (above, and through
     * {@link AuroraConfig.Parsed#deprecatedKeys}).
     *
     * @param utility the utility snapshot without the old utility names
     * @param aurora  the Aurora file's snapshot, or {@code null}
     */
    private static void warnUnknownKeys(final ConfigSnapshot utility, final ConfigSnapshot aurora) {
        final java.util.Set<String> auroraKnown = new java.util.HashSet<>(AuroraKeys.paths());
        auroraKnown.addAll(AuroraKeys.UNSEEDED_PATHS);
        for (final ConfigKey key : AuroraKeys.KEYS) auroraKnown.addAll(key.legacyPaths());
        // The utility file is read for its own keys and, under the Aurora file, for Aurora keys.
        final java.util.Set<String> utilityKnown = new java.util.HashSet<>(auroraKnown);
        utilityKnown.addAll(UtilityKeys.paths());
        for (final String message : dev.iyanz.sourbycraft.lang.SourbyMessages.keys()) {
            utilityKnown.add(dev.iyanz.sourbycraft.lang.SourbyMessages.SECTION + "." + message);
        }
        for (final String key : unknownKeys(utility, utilityKnown)) {
            SourbyLogger.warn("Config key '" + key + "' in " + activeUtilityPath + " is not a setting SourbyCraft "
                + "reads (misspelled or misplaced?) and is ignored. Keys: docs/AURORA-CONFIG.md.");
        }
        if (aurora == null) return;
        for (final String key : unknownKeys(aurora, auroraKnown)) {
            SourbyLogger.warn("Config key '" + key + "' in " + activeAuroraPath + " is not a setting SourbyCraft "
                + "reads (misspelled or misplaced?) and is ignored. Keys: docs/AURORA-CONFIG.md.");
        }
    }

    /**
     * The keys of a flattened snapshot that are not in {@code known}, sorted. A key that names a table of a known
     * key (a value where that table belongs) is left to the parser that owns the table.
     */
    static List<String> unknownKeys(final ConfigSnapshot snapshot, final java.util.Set<String> known) {
        final java.util.Set<String> tables = new java.util.HashSet<>();
        for (final String path : known) {
            for (int dot = path.indexOf('.'); dot > 0; dot = path.indexOf('.', dot + 1)) tables.add(path.substring(0, dot));
        }
        final java.util.Set<String> unknown = new java.util.TreeSet<>();
        for (final String key : snapshot.values().keySet()) {
            if (!known.contains(key) && !tables.contains(key)) unknown.add(key);
        }
        return List.copyOf(unknown);
    }

    /**
     * Reports an Aurora key that two files both set, where only one of them decides anything.
     *
     * <p>Giving Aurora its own file did not remove the keys already sitting in the unified one.
     * The Build 49 migration moves them, but a deployment whose file could not be rewritten still
     * holds the same key twice; aurora.yml wins, and an operator who edits the copy they happened
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
                + activeUtilityPath + " (" + utility.values().get(key) + ") and " + activeAuroraPath
                + " (" + aurora.values().get(key) + "); " + activeAuroraPath.getFileName()
                + " wins. Remove the key from " + activeUtilityPath.getFileName()
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
     * Load (creating on first boot) the utility file, bring an older layout to the Build 49 one
     * (once, with a {@code *.migrated} copy), seed every operator-facing key that is still absent,
     * and push the loaded values into the small set of consumers that cache them in static fields
     * ({@link dev.iyanz.sourbycraft.update.AutoUpdateSettings}). Idempotent-ish: safe to call once
     * at boot; use {@link #reload()} afterwards.
     *
     * <p>A utility file that cannot be opened ({@link #file()} logs it with file and line) costs only its
     * own keys, which take their defaults: aurora.yml, the security limits and the updater settings are
     * still applied, and {@code /sourbycraft reload} opens the file again once it is fixed.</p>
     */
    public static synchronized void init() {
        final CommentedFileConfig f = file();
        if (f != null) {
            try {
                migrateLayout(f, auroraFile());
            } catch (Throwable t) {
                SourbyLogger.error("configuration layout migration failed; the files were not changed, so old "
                    + "key names in them are ignored (one WARN each)", t);
            }
        }
        try {
            if (f != null) seedDefaults(f);
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
        final ConfigMigration.Report report = ConfigMigration.apply(plan, f, activeUtilityPath, aurora, activeAuroraPath);
        migratedUtilityFile = plan.touchesUtility();
        SourbyLogger.info(report.summary());
    }

    /**
     * Re-read both files from disk and re-apply them live ({@code /sourbycraft reload}).
     * Returns a short human summary.
     */
    public static synchronized String reload() {
        ReloadReport report = reloadDetailed();
        return (report.successful() ? "reloaded — " : "reload FAILED/PARTIAL — ") + String.join("; ", report.lines());
    }

    /**
     * {@code /sourbycraft reload}: re-reads the utility and Aurora files (opening a utility file that could
     * not be opened at boot), the security limits and the engine files, then publishes one snapshot.
     * Blocking file I/O on the calling thread.
     */
    public static synchronized ReloadReport reloadDetailed() {
        // file() reopens a utility file that boot could not open, and logs why when it still cannot.
        final boolean utilityWasOpen = FILE != null;
        final CommentedFileConfig f = file();
        if (f == null) return new ReloadReport(false, List.of("Could not open " + utilityPath
            + " (the log names the file and line); no new config snapshot published."));
        List<String> errors = new java.util.ArrayList<>();
        try {
            f.load();
        } catch (Throwable t) {
            return new ReloadReport(false, List.of("Could not read " + ConfigParseErrors.describe(activeUtilityPath, t)));
        }
        // A reload never writes defaults back to an existing file, including after a failed initial save.
        newFile = false;
        migratedUtilityFile = false;
        final boolean auroraWasOpen = AURORA_FILE != null;
        final CommentedFileConfig engineFile = auroraFile();
        if (engineFile == null) errors.add("Aurora file unavailable; using the utility layer and defaults.");
        else try {
            engineFile.load();
        } catch (Throwable t) {
            return new ReloadReport(false, List.of("Could not read " + ConfigParseErrors.describe(activeAuroraPath, t),
                "No new config snapshot published; runtime retains its previously loaded settings."));
        }
        // A .toml left beside a .yml is never read; said on every reload, as at boot. A file this reload had to
        // reopen was already named by ConfigFormatMigration.choose().
        if (utilityWasOpen && utilityPath.equals(CONFIG_PATH)) {
            ConfigFormatMigration.warnIgnoredLegacy(CONFIG_PATH, LEGACY_CONFIG_PATH);
        }
        if (auroraWasOpen) ConfigFormatMigration.warnIgnoredLegacy(AURORA_PATH, LEGACY_AURORA_PATH);
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
        lines.add("Engine options in config/aurora-server.yml and config/aurora-worlds.yml that are read once at startup are not enumerated here.");
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
        final Path path = ConfigFormatMigration.choose(AURORA_PATH, LEGACY_AURORA_PATH);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            newAuroraFile = !ConfigSources.anyExists(AURORA_PATH);
            final CommentedFileConfig f = CommentedFileConfig.builder(path, ConfigSources.formatFor(path))
                .sync() // save() completes before reporting persistence or allowing reload
                .preserveInsertionOrder() // the file keeps the operator's (or the seeder's) order
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .build();
            f.load();
            activeAuroraPath = path;
            AURORA_FILE = f;
        } catch (Throwable t) {
            SourbyLogger.error("could not open " + ConfigParseErrors.describe(path, t)
                + "; Aurora settings are read from the unified file and its defaults", t);
        }
        return AURORA_FILE;
    }

    private static synchronized CommentedFileConfig file() {
        if (FILE != null) return FILE;
        // The test seam may point anywhere; production converts the old TOML once, then opens the YAML.
        final Path path = utilityPath.equals(CONFIG_PATH)
            ? ConfigFormatMigration.choose(CONFIG_PATH, LEGACY_CONFIG_PATH)
            : utilityPath;
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            newFile = !ConfigSources.anyExists(path);
            CommentedFileConfig f = CommentedFileConfig.builder(path, ConfigSources.formatFor(path))
                .sync() // save() completes before reporting persistence or allowing reload
                .preserveInsertionOrder() // the file keeps the operator's (or the seeder's) order
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .build();
            f.load();
            activeUtilityPath = path;
            FILE = f;
        } catch (Throwable t) {
            SourbyLogger.error("could not open " + ConfigParseErrors.describe(path, t)
                + "; every read falls back to its default", t);
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
     * opens {@code path}. The Aurora layer and the boot snapshot are left alone (a {@link #setAndSave}
     * under the seam may update the boot snapshot). Closing the returned handle restores everything it
     * replaced, including the previous file handle and boot snapshot.</p>
     *
     * @param utility the snapshot {@link #cfgGet} and its siblings should read
     * @param path    the utility file {@link #setAndSave} should write
     * @return a handle that restores the previous state; use it in try-with-resources
     */
    static synchronized AutoCloseable installForTesting(final ConfigSnapshot utility, final Path path) {
        final LoadedConfig previousLoaded = loaded;
        final LoadedConfig previousBoot = bootConfig;
        final CommentedFileConfig previousFile = FILE;
        final Path previousPath = utilityPath;
        final boolean previousNewFile = newFile;
        utilityPath = path;
        FILE = null;
        newFile = false;
        loaded = new LoadedConfig(utility, previousLoaded.aurora(), previousLoaded.auroraLayer());
        return () -> {
            synchronized (SourbyCraftConfig.class) {
                final CommentedFileConfig opened = FILE;
                if (opened != null && opened != previousFile) {
                    opened.close();
                }
                loaded = previousLoaded;
                bootConfig = previousBoot;
                FILE = previousFile;
                utilityPath = previousPath;
                newFile = previousNewFile;
            }
        };
    }

    // -------------------------------------------------------------------------------- typed reads

    /** Read a value from the utility file's published snapshot by dotted path. Falls back to {@code defaultValue}. */
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
     *
     * <p>The file is re-read first, so an edit the operator saved since the last load or reload is kept and
     * only what is on disk plus this key is written (defaults seeded in memory are never written back); a
     * file that cannot be read is not written at all. Only this key changes in the published snapshot:
     * other edits on disk still wait for {@code /sourbycraft reload}. The caller applies the value live
     * ({@code /maxp} sets the slot count before persisting it), so the boot snapshot takes it too and
     * {@link #pendingRestartKeys} does not list the key as waiting for a restart. Blocking file I/O: a
     * caller on a region or the global tick thread hands this to an I/O executor.</p>
     */
    public static synchronized boolean setAndSave(String dottedPath, Object value, String commentIfNew) {
        CommentedFileConfig f = file();
        if (f == null) return false;
        try {
            f.load();
        } catch (Throwable t) {
            SourbyLogger.warn("could not persist " + dottedPath + ": " + ConfigParseErrors.describe(activeUtilityPath, t)
                + "; the file was not written");
            return false;
        }
        final Object written;
        try {
            f.set(dottedPath, value);
            if (commentIfNew != null && f.getComment(dottedPath) == null) {
                f.setComment(dottedPath, commentIfNew);
            }
            f.save();
            written = ConfigSnapshot.copyOf(f).values().get(dottedPath);
        } catch (Throwable t) {
            SourbyLogger.warn("could not persist " + dottedPath + ": " + t.getMessage());
            return false;
        }
        final LoadedConfig current = loaded;
        loaded = new LoadedConfig(withValue(current.utility(), dottedPath, written), current.aurora(), current.auroraLayer());
        final LoadedConfig boot = bootConfig;
        if (boot != null) {
            bootConfig = new LoadedConfig(withValue(boot.utility(), dottedPath, written), boot.aurora(), boot.auroraLayer());
        }
        return true;
    }

    /** {@code snapshot} with {@code key} set to {@code value}, or without it when {@code value} is {@code null}. */
    private static ConfigSnapshot withValue(final ConfigSnapshot snapshot, final String key, final Object value) {
        final java.util.Map<String, Object> values = new java.util.LinkedHashMap<>(snapshot.values());
        if (value == null) values.remove(key);
        else values.put(key, value);
        return new ConfigSnapshot(values);
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
