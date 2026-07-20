package dev.iyanz.sourbycraft;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import dev.iyanz.sourbycraft.util.SourbyLogger;

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
 * {@code /maxp} persistence + bypass, the auto-updater, and the GC-advisor toggle — plus, as of
 * r40, a standalone memory-management feature deliberately NOT part of the deferred perf-engine:
 * {@code perf.smart-swap.*} ({@link dev.iyanz.sourbycraft.perf.SmartSwap}, adaptive heap reclaim).
 */
public final class SourbyCraftConfig {

    private static final Path CONFIG_PATH = Path.of("sourbycraft_config", "sourbycraft_global_config.toml");

    private static volatile CommentedFileConfig FILE;

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
        } catch (Throwable t) {
            SourbyLogger.error("seedDefaults failed; utility layer will use hardcoded defaults", t);
        }
        applyLiveConfig();
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
        try {
            applyLiveConfig();
        } catch (Throwable t) {
            SourbyLogger.error("config reload apply failed", t);
            return "reload FAILED during apply: " + t.getMessage();
        }
        SourbyLogger.info("config reloaded from disk (/sourbycraft reload)");
        return "reloaded — messages, /maxp persistence and the auto-updater settings applied live. "
            + "A scheduled auto-update check interval only takes effect on the next restart.";
    }

    private static void applyLiveConfig() {
        try {
            dev.iyanz.sourbycraft.update.AutoUpdateSettings.loadFromToml();
        } catch (Throwable t) {
            SourbyLogger.error("AutoUpdateSettings.loadFromToml failed; using defaults", t);
        }

        // SmartSwap (adaptive heap reclaim, r40) operator bridge — reloadable, no restart needed.
        try {
            dev.iyanz.sourbycraft.perf.SmartSwap.configure(
                cfgBool("perf.smart-swap.enabled", true),
                cfgDouble("perf.smart-swap.soft-percent", 82.0),
                cfgDouble("perf.smart-swap.medium-percent", 90.0),
                cfgDouble("perf.smart-swap.hard-percent", 95.0),
                cfgInt("perf.smart-swap.sample-interval-ticks", 20));
        } catch (Throwable t) {
            SourbyLogger.error("SmartSwap.configure failed; using defaults", t);
        }
    }

    private static synchronized CommentedFileConfig file() {
        if (FILE != null) return FILE;
        try {
            if (CONFIG_PATH.getParent() != null) {
                Files.createDirectories(CONFIG_PATH.getParent());
            }
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
        CommentedFileConfig f = file();
        if (f == null) return null;
        try {
            return f.get(dottedPath);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------- typed writes

    /**
     * Set + persist a single key (e.g. {@code /maxp <n>}). Never throws. Returns {@code true} on a
     * successful write+save.
     */
    public static boolean setAndSave(String dottedPath, Object value, String commentIfNew) {
        CommentedFileConfig f = file();
        if (f == null) return false;
        try {
            f.set(dottedPath, value);
            if (commentIfNew != null && f.getComment(dottedPath) == null) {
                f.setComment(dottedPath, commentIfNew);
            }
            f.save();
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

        // SmartSwap (r40) — standalone, trend-aware adaptive heap reclaim. Trims rebuildable soft
        // caches + requests a concurrent GC (ZGC/Shenandoah uncommit) as usage climbs, so RSS stays
        // under the container limit WITHOUT touching TPS. Percentages are of max(heap%, container-RSS%).
        // Independent of the deferred self-tuning perf-engine — see dev.iyanz.sourbycraft.perf.SmartSwap.
        seed(f, changed, "perf.smart-swap.enabled", true,
            "Adaptive heap reclaim: trim soft caches + concurrent-GC hint as memory usage rises, so RSS "
            + "stays under the container limit without touching TPS. Trend-aware (acts early when climbing fast).");
        seed(f, changed, "perf.smart-swap.soft-percent", 82.0,
            "Usage % at which SmartSwap starts trimming rebuildable soft caches.");
        seed(f, changed, "perf.smart-swap.medium-percent", 90.0,
            "Usage % at which SmartSwap also requests a concurrent GC to hand freed pages back to the OS.");
        seed(f, changed, "perf.smart-swap.hard-percent", 95.0,
            "Usage % at which SmartSwap reclaims on a shorter throttle (acts more often while critical).");
        seed(f, changed, "perf.smart-swap.sample-interval-ticks", 20,
            "How often (in ticks) SmartSwap samples heap/RSS usage. 20 = once per second.");

        dev.iyanz.sourbycraft.lang.SourbyMessages.seedDefaults(f, changed);
        dev.iyanz.sourbycraft.update.AutoUpdateSettings.seedDefaults(f, changed);

        if (changed[0]) {
            try {
                f.save();
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
