package dev.iyanz.sourbycraft.maxplayers;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;

/**
 * Persistence + boot re-apply for the operator-set max-player slot count ({@code /maxp <n>}, F1-6).
 *
 * <p>Relocated out of the {@code perf} package on the Canvas re-platform (feat/canvas-engine,
 * PR #12) — {@code /maxp} is a kept utility command, not part of the deferred self-tuning
 * perf-engine, so it gets its own small package instead of sitting inside the (now perf-engine-only)
 * {@code perf.*} tree. Reads/writes the same {@code sourbycraft.max-players} key in the unified
 * TOML via {@link SourbyCraftConfig}.
 */
public final class MaxPlayersConfig {

    /** Dotted key in the unified TOML. */
    public static final String KEY = "sourbycraft.max-players";

    // Sane upper bound: Bukkit stores max-players as an int; a slot count in the hundreds of
    // thousands is nonsensical and almost certainly a typo.
    private static final int MAX_ALLOWED = 100_000;

    private MaxPlayersConfig() {}

    /** Persist {@code n} into {@code sourbycraft.max-players}. Never throws. */
    public static boolean persist(int n) {
        if (n <= 0 || n > MAX_ALLOWED) return false;
        return SourbyCraftConfig.setAndSave(KEY, n,
            "Server max-player slot count set by /maxp. Re-applied at boot so it wins over server.properties. 0/absent = use server.properties.");
    }

    /**
     * If a persisted {@code sourbycraft.max-players} value {@code > 0} exists, apply it live via
     * {@link org.bukkit.Server#setMaxPlayers(int)} so it survives restart and overrides
     * {@code server.properties}. No-op when absent, zero, or out of range. Never throws.
     */
    public static void applyAtBoot() {
        int n = SourbyCraftConfig.cfgInt(KEY, 0);
        if (n <= 0 || n > MAX_ALLOWED) return;
        try {
            Bukkit.getServer().setMaxPlayers(n);
            SourbyLogger.info("applied persisted " + KEY + "=" + n + " (overrides server.properties)");
        } catch (Throwable t) {
            SourbyLogger.warn("could not apply persisted max-players: " + t.getMessage());
        }
    }
}
