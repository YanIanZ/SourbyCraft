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
        loaded = new LoadedConfig(utility, parsed.config());
        for (final String key : parsed.deprecatedKeys()) {
            SourbyLogger.warn("Deprecated config key '" + key + "'; use '"
                + AuroraConfig.ASYNC_PATH_KEY + "'. Operator file was not modified.");
        }
        for (final String key : parsed.invalidKeys()) {
            SourbyLogger.warn("Aurora config key '" + key
                + "' is invalid; expected boolean setting under TOML tables. Runtime fallback: false. "
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
            // Equal values are not a trap: both files agree, so editing either one is harmless.
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
    }

    /**
     * Re-read the unified TOML from disk and re-apply it live ({@code /sourbycraft reload}).
     * Returns a short human summary.
     */
    public static synchronized String reload() {
        CommentedFileConfig f = FILE;
        if (f == null) return "reload FAILED: unified config not available";
        try {
            f.load();
        } catch (Throwable t) {
            return "reload FAILED: could not re-read the config file: " + t.getMessage();
        }
        // Aurora's own file has to be re-read too. auroraFile() returns a cached handle and only
        // load()s it on creation, so without this the one file the split exists to give Aurora
        // is the one file /sourbycraft reload ignores: an operator edits aurora.toml, reloads,
        // is told it reloaded, and keeps the value from boot.
        final CommentedFileConfig auroraFile = auroraFile();
        if (auroraFile != null) {
            try {
                auroraFile.load();
            } catch (final Throwable t) {
                // Keep going with the last good Aurora values rather than failing the whole
                // reload: the unified file's settings below are still worth applying.
                SourbyLogger.error("could not re-read " + AURORA_PATH
                    + "; Aurora settings keep the values loaded at startup", t);
            }
        }
        final AuroraConfig previous = aurora();
        final AuroraConfig.Parsed parsed;
        try {
            parsed = loadSnapshot(f, auroraFile);
            applyLiveConfig(true);
        } catch (Throwable t) {
            SourbyLogger.error("config reload apply failed", t);
            return "reload FAILED during apply: " + t.getMessage();
        }
        SourbyLogger.info("config reloaded from disk (/sourbycraft reload)");
        return "reloaded — " + aurora().reloadSummary(previous)
            + "; invalid Aurora keys: " + parsed.invalidKeys() + ". Messages, /maxp persistence, auto-updater settings applied "
            + "live, plus the Canvas server/world configs (canvas-server.yml / canvas-worlds.yml). "
            + "Options cached at construction (and a scheduled auto-update interval) only take effect "
            + "on the next restart.";
    }

    private static void applyLiveConfig(final boolean reloadEngine) {
        try {
            dev.iyanz.sourbycraft.update.AutoUpdateSettings.loadFromToml();
        } catch (Throwable t) {
            SourbyLogger.error("AutoUpdateSettings.loadFromToml failed; using defaults", t);
        }

        // Legacy automatic memory tuning is retired. Preserve operator files and explain the change.
        if (cfgBool("perf.smart-swap.enabled", false) || cfgBool("swap.auto-create.enabled", false)) {
            SourbyLogger.warn("SmartSwap and automatic swap creation are retired in build 44. "
                + "JVM memory and OS swap remain operator-owned; legacy config keys were not modified.");
        }

        // The engine's own configuration, folded into /sourbycraft reload so an operator has one
        // command. Which engine that is stays behind the bridge: nothing here names it, so
        // replacing the implementation is a new bridge rather than an edit to this path.
        if (!reloadEngine) return;
        for (final String failure : ENGINE_CONFIG.reload()) {
            SourbyLogger.error(ENGINE_CONFIG.name() + " " + failure
                + "; keeping the previous values", null);
        }
    }

    /** The engine configuration SourbyCraft's reload also re-reads. */
    private static final dev.iyanz.sourbycraft.config.upstream.UpstreamConfigBridge ENGINE_CONFIG =
        new dev.iyanz.sourbycraft.config.upstream.CanvasConfigBridge();

    /** Aurora's file, or {@code null} when it cannot be opened -- then only the unified file is read. */
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
            "Processors Aurora may use in total, cores and hardware threads alike (RESTART). "
            + "0 = every available processor. Counted as the JVM counts them, so a container CPU "
            + "quota is respected rather than the physical socket. A budget, not a reservation: a "
            + "region ticks on one thread, so threads past the number of separate active regions "
            + "idle. An explicit threaded-regions.threads in paper-global.yml still wins.");
        seed(f, changed, AuroraConfig.LANE_SAMPLING_KEY, true,
            "Aurora execution-lane CPU attribution (LIVE), behind /perf lanes. Walks every thread once a "
            + "second: negligible beside a loaded server, and on an idle one the telemetry lane costs more "
            + "than the region lane. false stops the sampling; the lanes view then reports it as disabled.");
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

        seed(f, changed, "branding.gc-advisor.enabled", true,
            "Enable the startup GC/JVM-flags advisory log.");

        seed(f, changed, "sourbycraft.max-players", 0,
            "Server max-player slot count set by /maxp. Re-applied at boot so it wins over server.properties. 0 = use server.properties.");
        seed(f, changed, "sourbycraft.maxplayers.bypass-enabled", false,
            "Let sourbycraft.maxplayers.bypass holders (+ ops) join a full server. Default false: OFF keeps"
            + " the fast config-phase join path; enabling registers a PlayerLoginEvent listener that"
            + " disables that fast path and slows every join.");

        seed(f, changed, "viaversion.auto-provision", true,
            "Whether ViaVersion/ViaBackwards (auto-provisioned by SourbyBootstrap on first boot) are also "
            + "kept up to date by the auto-updater's cadence. false = manage Via yourself.");

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
