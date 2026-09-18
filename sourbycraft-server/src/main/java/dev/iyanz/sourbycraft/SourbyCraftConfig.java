package dev.iyanz.sourbycraft;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.ConfigSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SourbyCraft config registry — SLIM Canvas-benchmark build (feat/canvas-engine, PR #12).
 *
 * <p>Every operator-facing SourbyCraft setting still lives in ONE file: {@code
 * sourcebycraft_config/sourbycraft_global_config.toml} (own {@code nightconfig} {@link
 * CommentedFileConfig}, resolved directly here — no Luminol {@code ConfigManager} dependency on
 * this base, unlike the archived Folia build). The utility layer (varied messages, the auto-
 * updater, {@code /maxp}) reads its keys through the typed {@link #cfgBool}/{@link #cfgInt}/
 * {@link #cfgGet}/{@link #cfgStringList} accessors below.
 *
 * <p><b>What got cut going from Folia to Canvas.</b> The archived version of this class was
 * ~1000 lines because it also seeded/bridged the self-tuning perf-engine (knobs, sensor,
 * combat-profile, thread-pool bridges), the anti-xray raytrace reveal layer, and the proxy-
 * forwarding / hardening-advisor security layer. All three are DEFERRED on this benchmark build
 * (see the PR #12 task brief) — their config trees, {@code seed()} calls and live-apply bridges
 * are gone with them. What remains is exactly what the kept utility layer reads: varied messages,
 * {@code /maxp} persistence + bypass, the auto-updater, and the GC-advisor toggle. Since
 * build 44, immutable utility snapshots and read-only performance diagnostics. Legacy automatic
 * memory tuning is retired; existing keys remain in operator files.
 */
public final class SourbyCraftConfig {

    private static final Path CONFIG_PATH = Path.of("sourbycraft_config", "sourbycraft_global_config.toml");
    /**
     * Aurora's own file. The engine and the utility layer are different things -- that is the whole
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
        final AuroraConfig.Parsed parsed = AuroraConfig.parse(auroraFile == null ? utility
            : ConfigSnapshot.layered(utility, ConfigSnapshot.copyOf(auroraFile)));
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
            SourbyLogger.error("seedDefaults failed; utility layer will use hardcoded defaults", t);
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
        final AuroraConfig previous = aurora();
        final AuroraConfig.Parsed parsed;
        try {
            parsed = loadSnapshot(f, auroraFile());
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

        // Canvas server + world configs (canvas-server.yml / canvas-worlds.yml) — fold the canonical
        // Canvas reload (the same GlobalConfiguration.reload() + WorldConfig.reload() the removed
        // /canvas reload ran) into /sourbycraft reload so operators have ONE reload command. Each is
        // isolated: a failure re-reading the Canvas yml never aborts the rest of the SourbyCraft
        // reload. Note: options cached at construction (e.g. a per-world value read once into a field)
        // update the config object but only take effect on the next restart — matching Canvas's own
        // "some options cannot change at runtime" contract.
        if (!reloadEngine) return;
        try {
            io.canvasmc.canvas.GlobalConfiguration.reload();
        } catch (Throwable t) {
            SourbyLogger.error("Canvas GlobalConfiguration.reload() failed; keeping the previous values", t);
        }
        try {
            io.canvasmc.canvas.WorldConfig.reload();
        } catch (Throwable t) {
            SourbyLogger.error("Canvas WorldConfig.reload() failed; keeping the previous values", t);
        }
    }

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
            SourbyLogger.error("could not open sourcebycraft_config/sourbycraft_global_config.toml; "
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
     * Seed every operator-facing key this benchmark build's utility layer reads, when absent
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
