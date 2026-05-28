package dev.iyanz.sourbycraft.combat;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knockback global toggle + per-player multiplier (0.0-3.0).
 *
 * Storage: plugins/SourbyCraft/knockback.yml keyed by UUID string.
 * Hook: NMS LivingEntity.knockback calls scale() before applying strength.
 */
public final class KnockbackManager {

    private static final Map<UUID, Double> MULTIPLIERS = new ConcurrentHashMap<>();
    private static final File FILE = new File("plugins/SourbyCraft/knockback.yml");
    private static boolean started = false;

    private KnockbackManager() {}

    public static void start() {
        if (started) return;
        try {
            FILE.getParentFile().mkdirs();
            if (FILE.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(FILE);
                var players = cfg.getConfigurationSection("players");
                if (players != null) {
                    for (String key : players.getKeys(false)) {
                        try {
                            MULTIPLIERS.put(UUID.fromString(key), players.getDouble(key));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                SourbyLogger.info("KnockbackManager loaded " + MULTIPLIERS.size() + " per-player multipliers.");
            }
        } catch (Throwable t) {
            SourbyLogger.warn("KnockbackManager load failed: " + t);
        }
        started = true;
    }

    public static void save() {
        try {
            FILE.getParentFile().mkdirs();
            YamlConfiguration cfg = new YamlConfiguration();
            for (var e : MULTIPLIERS.entrySet()) {
                cfg.set("players." + e.getKey().toString(), e.getValue());
            }
            cfg.save(FILE);
        } catch (IOException e) {
            SourbyLogger.warn("KnockbackManager save failed: " + e.getMessage());
        }
    }

    public static double getMultiplier(UUID uuid) {
        return MULTIPLIERS.getOrDefault(uuid, 1.0);
    }

    public static void setMultiplier(UUID uuid, double m) {
        double clamped = Math.max(0.0, Math.min(3.0, m));
        if (clamped == 1.0) MULTIPLIERS.remove(uuid);
        else MULTIPLIERS.put(uuid, clamped);
        save();
    }

    /**
     * Called from NMS LivingEntity.knockback. Returns scaled strength.
     * If global is off, returns 0. Otherwise scales by victim's per-player multiplier (if Player).
     */
    public static double scale(net.minecraft.world.entity.LivingEntity victim, double strength) {
        if (!SourbyCraftConfig.knockbackGlobalEnabled) return 0.0;
        if (victim instanceof net.minecraft.server.level.ServerPlayer sp) {
            double m = getMultiplier(sp.getUUID());
            return strength * m;
        }
        return strength;
    }
}
