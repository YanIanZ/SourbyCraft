package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;

/**
 * Persistence + boot re-apply for the operator-set max-player slot count
 * ({@code /maxp <n>}), on the SourbyCraft Folia base (F1-6).
 *
 * <p>The value lives under the {@code sourbycraft.max-players} key in the single
 * unified SourbyCraft config file
 * ({@code sourbycraft_config/sourbycraft_global_config.toml}) — the same
 * nightconfig {@code CommentedFileConfig} the perf-engine seeds its keys into
 * (see {@code SourbyCraftConfig#seedUnifiedPerfDefaults}). Reusing that file
 * keeps the operator surface to ONE file and lets the setting survive restarts.
 *
 * <ul>
 *   <li>{@link #persist(int)} — called by {@code /maxp <n>} to write the value.
 *       Returns {@code false} (never throws) when the config is unavailable so
 *       the live {@code setMaxPlayers} still stands, only without persistence.</li>
 *   <li>{@link #applyAtBoot()} — called once from
 *       {@link PerfEngineBootstrap#start()} after
 *       {@code SourbyCraftConfig.init()} has loaded the unified file. If a
 *       persisted value {@code > 0} exists it calls
 *       {@link org.bukkit.Server#setMaxPlayers(int)} so the stored value wins
 *       over {@code server.properties} on every boot.</li>
 * </ul>
 *
 * <p>Resolved reflectively/lazily through Luminol's {@code ConfigManager} so this
 * class carries no hard init-order dependency, mirroring
 * {@code SourbyCraftConfig#unifiedFile()}.
 */
public final class MaxPlayersConfig {

    /** Dotted key in the unified TOML. */
    public static final String KEY = "sourbycraft.max-players";

    // Same sane bound the /maxp command enforces; defense-in-depth for a hand-edited config.
    private static final int MAX_ALLOWED = 100_000;

    private MaxPlayersConfig() {}

    /**
     * Resolve the unified nightconfig file lazily via Luminol's ConfigManager.
     * Returns {@code null} when the config is not available (e.g. pre-boot / test).
     */
    private static com.electronwill.nightconfig.core.file.CommentedFileConfig unifiedFile() {
        try {
            me.earthme.luminol.config.ConfigsInstance inst =
                me.earthme.luminol.config.ConfigManager.getConfigs("sourbycraft");
            if (inst == null) return null;
            Object file = inst.getFileInstance();
            if (file instanceof com.electronwill.nightconfig.core.file.CommentedFileConfig cfc) {
                return cfc;
            }
        } catch (Throwable ignored) {
            // Luminol config not available — caller handles the null.
        }
        return null;
    }

    /**
     * Persist {@code n} into {@code sourbycraft.max-players} in the unified config and save.
     * Never throws. Returns {@code true} on a successful write+save.
     */
    public static boolean persist(int n) {
        if (n <= 0 || n > MAX_ALLOWED) return false;
        com.electronwill.nightconfig.core.file.CommentedFileConfig f = unifiedFile();
        if (f == null) {
            SourbyLogger.warn("/maxp could not persist: unified config unavailable");
            return false;
        }
        try {
            f.set(KEY, n);
            if (f.getComment(KEY) == null) {
                f.setComment(KEY,
                    "Server max-player slot count set by /maxp. Re-applied at boot so it wins over server.properties. 0/absent = use server.properties.");
            }
            f.save();
            return true;
        } catch (Throwable t) {
            SourbyLogger.warn("/maxp could not persist max-players: " + t.getMessage());
            return false;
        }
    }

    /**
     * If a persisted {@code sourbycraft.max-players} value {@code > 0} exists, apply it live via
     * {@link org.bukkit.Server#setMaxPlayers(int)} so the operator's persisted cap survives restart
     * and overrides {@code server.properties}. No-op when absent, zero, or out of range. Never throws.
     */
    public static void applyAtBoot() {
        com.electronwill.nightconfig.core.file.CommentedFileConfig f = unifiedFile();
        if (f == null) return;
        Object raw;
        try {
            raw = f.get(KEY);
        } catch (Throwable t) {
            return;
        }
        if (!(raw instanceof Number num)) return;
        int n = num.intValue();
        if (n <= 0 || n > MAX_ALLOWED) return;
        try {
            Bukkit.getServer().setMaxPlayers(n);
            SourbyLogger.info("applied persisted " + KEY + "=" + n
                + " (overrides server.properties)");
        } catch (Throwable t) {
            SourbyLogger.warn("could not apply persisted max-players: " + t.getMessage());
        }
    }
}
