package dev.iyanz.sourbycraft;

import gg.pufferfish.pufferfish.util.AsyncExecutor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.world.entity.ai.gossip.GossipType;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.logging.Level;

public class SourbyCraftConfig {

    private static File CONFIG_FILE;
    public static YamlConfiguration config;

    static int version, currentVersion = 4;
    static boolean verbose;

    public static boolean asyncChunkLoad = false;
    public static boolean asyncPathfinding = false;
    public static int asyncThreads = 2;
    public static boolean multithreadingEnabled = false;
    public static boolean dimensionThreads = false;

    public static boolean skipEmptySections = true;
    public static boolean poolEntityData = true;
    public static boolean preSizePackets = false;
    public static boolean chunkCompressionCache = false;

    public static boolean swmEnabled = true;

    public static boolean autoThrottleView = true;
    public static int minViewDistance = 4;
    public static int compressionLevel = 4;
    public static boolean entityTickRateLimit = true;
    public static int entityTickRate = 20;
    public static boolean hopperBatch = true;
    public static boolean redstoneOptimize = true;
    public static int maxEntityPerChunk = 10;
    public static int idleTimeout = 0;
    public static boolean itemMergeOptimize = true;
    public static int itemDespawnRate = 6000;
    public static int itemMergeRadius = 3;
    public static int mobTickDistance = 32;
    public static int mobPathfindInterval = 20;
    public static boolean asyncSaveBatch = true;

    public static void init(File configFile) {
        CONFIG_FILE = configFile;
        config = new YamlConfiguration();

        try {
            config.load(CONFIG_FILE);
        } catch (IOException ignored) {
        } catch (InvalidConfigurationException exception) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not load " + configFile.getName() + ", please correct your syntax errors", exception);
            throw new RuntimeException(exception);
        }

        config.options().copyDefaults(true);
        verbose = getBoolean("verbose", false);

        version = getInt("config-version", currentVersion);
        set("config-version", currentVersion);

        readConfig(SourbyCraftConfig.class, null);

        asyncChunkLoad = getBoolean("performance.async-chunk-load", asyncChunkLoad);
        asyncPathfinding = getBoolean("performance.async-pathfinding", asyncPathfinding);
        asyncThreads = getInt("performance.async-threads", asyncThreads);

        multithreadingEnabled = getBoolean("multithreading.enabled", multithreadingEnabled);
        dimensionThreads = getBoolean("multithreading.dimension-threads", dimensionThreads);
        asyncThreads = getInt("multithreading.async-threads", asyncThreads);

        skipEmptySections = getBoolean("memory.skip-empty-sections", skipEmptySections);
        poolEntityData = getBoolean("memory.pool-entity-data", poolEntityData);
        preSizePackets = getBoolean("memory.pre-size-packets", preSizePackets);
        chunkCompressionCache = getBoolean("memory.chunk-compression-cache", chunkCompressionCache);

        swmEnabled = getBoolean("swm.enabled", swmEnabled);

        autoThrottleView = getBoolean("network.auto-throttle-view", autoThrottleView);
        minViewDistance = getInt("network.min-view-distance", minViewDistance);
        compressionLevel = getInt("network.compression-level", compressionLevel);
        entityTickRateLimit = getBoolean("entity.tick-rate-limit", entityTickRateLimit);
        entityTickRate = getInt("entity.tick-rate", entityTickRate);
        hopperBatch = getBoolean("entity.hopper-batch", hopperBatch);
        redstoneOptimize = getBoolean("entity.redstone-optimize", redstoneOptimize);
        maxEntityPerChunk = getInt("entity.max-per-chunk", maxEntityPerChunk);
        idleTimeout = getInt("server.idle-timeout", idleTimeout);
        itemMergeOptimize = getBoolean("entity.item-merge-optimize", itemMergeOptimize);
        itemDespawnRate = getInt("entity.item-despawn-rate", itemDespawnRate);
        itemMergeRadius = getInt("entity.item-merge-radius", itemMergeRadius);
        mobTickDistance = getInt("entity.mob-tick-distance", mobTickDistance);
        mobPathfindInterval = getInt("entity.mob-pathfind-interval", mobPathfindInterval);
        asyncSaveBatch = getBoolean("chunk.async-save-batch", asyncSaveBatch);

        if (idleTimeout > 0) {
            startIdleTimeoutChecker();
        }

        AsyncExecutor.initPool(asyncThreads);
    }

    protected static void log(String string) {
        if (verbose) log(Level.INFO, string);
    }

    protected static void log(Level level, String string) {
        Bukkit.getLogger().log(level, string);
    }

    public static void readConfig(Class<?> clazz, Object instance) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (!Modifier.isPrivate(method.getModifiers())) continue;
            if (method.getParameterTypes().length != 0 || method.getReturnType() != Void.TYPE) continue;

            try {
                method.setAccessible(true);
                method.invoke(instance);
            } catch (InvocationTargetException exception) {
                throw new RuntimeException(exception);
            } catch (Exception exception) {
                Bukkit.getLogger().log(Level.SEVERE, "Error invoking " + method, exception);
            }
        }

        try {
            config.save(CONFIG_FILE);
        } catch (IOException exception) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not save " + CONFIG_FILE, exception);
        }
    }

    private static void set(String path, Object val) {
        config.addDefault(path, val);
        config.set(path, val);
    }

    private static boolean getBoolean(String path, boolean def) {
        config.addDefault(path, def);
        return config.getBoolean(path, config.getBoolean(path));
    }

    private static double getDouble(String path, double def) {
        config.addDefault(path, def);
        return config.getDouble(path, config.getDouble(path));
    }

    private static int getInt(String path, int def) {
        config.addDefault(path, def);
        return config.getInt(path, config.getInt(path));
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> getList(String path, List<T> def) {
        config.addDefault(path, def);
        return (List<T>) config.getList(path, config.getList(path));
    }

    private static String getString(String path, String def) {
        config.addDefault(path, def);
        return config.getString(path, config.getString(path));
    }

    private static Component getComponent(String path, Component def) {
        return MiniMessage.miniMessage().deserialize(getString(path,
                MiniMessage.miniMessage().serialize(def)));
    }

    public static boolean detailedBrand = true;
    private static void detailedBrand() {
        if (version <= 3) {
            detailedBrand = getBoolean("settings.debug-version", detailedBrand);
            set("settings.detailed-brand-info", detailedBrand);
            set("settings.debug-version", null);
        }
        detailedBrand = getBoolean("settings.detailed-brand-info", detailedBrand);
    }

    public static boolean localizeItems = true;
    private static void adventure() {
        if (version <= 2) {
            localizeItems = getBoolean("settings.localize.items", localizeItems);
            set("settings.adventure.localize-items", localizeItems);
            set("settings.localize", null);
        }
        if (version <= 3) {
            localizeItems = getBoolean("settings.adventure.localize-items", localizeItems);
            set("settings.translate-items", localizeItems);
            set("settings.adventure", null);
        }

        localizeItems = getBoolean("settings.translate-items", localizeItems);
    }

    public static boolean srPlaceInDefaultFluid = false;
    private static void surfaceRules() {
        srPlaceInDefaultFluid = getBoolean("settings.allow-surface-rules-for-default-fluids", srPlaceInDefaultFluid);
    }

    private static void villagerGossip() {
        for (GossipType gossipType : GossipType.values()) {
            gossipType.max = gossipType.defaultMax;
            gossipType.decayPerDay = gossipType.defaultDecayPerDay;
            gossipType.decayPerTransfer = gossipType.defaultDecayPerTransfer;
            gossipType.max = getInt("settings.villager-gossip." + gossipType.id + ".limit", gossipType.max);
            gossipType.decayPerDay = getInt("settings.villager-gossip." + gossipType.id + ".decay-per-day", gossipType.decayPerDay);
            gossipType.decayPerTransfer = getInt("settings.villager-gossip." + gossipType.id + ".decay-per-transfer", gossipType.decayPerTransfer);
        }
    }
}
