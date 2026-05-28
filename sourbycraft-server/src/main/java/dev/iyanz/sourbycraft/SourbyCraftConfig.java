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

    static int version, currentVersion = 7;
    static boolean verbose;

    public static boolean asyncChunkLoad = false;
    public static boolean asyncPathfinding = false;
    public static boolean multithreadingEnabled = false;
    public static boolean virtualThreads = true;
    public static boolean structuredConcurrency = true;
    public static int maxPlatformThreads = 4;

    public static boolean skipEmptySections = true;
    public static boolean poolEntityData = true;
    public static boolean preSizePackets = false;
    public static boolean chunkCompressionCache = false;

    public static boolean swmEnabled = true;
    public static String swmVersion = "v7-REL";
    public static boolean swmAutoInstall = true;       // SourbyCraft v9.22 — default ON
    public static boolean swmAutoUpdate = true;        // SourbyCraft v9.22 — check GitHub for updates on startup
    public static String swmLoader = "file";
    public static String swmFileDir = "slime_worlds";

    public static boolean autoThrottleView = true;
    public static int minViewDistance = 4;
    public static int compressionLevel = 4;
    // SourbyCraft v9.13 — default FALSE; DynamicPerformanceScaler adjusts this value but no
    // code in paper-server actually reads it to gate item-entity ticks. Leaving it true caused
    // confusion (false sense of throttling). Disable until a real tick-gate consumer is wired.
    public static boolean entityTickRateLimit = false;
    public static volatile int entityTickRate = 20;
    public static boolean hopperBatch = true;
    public static boolean redstoneOptimize = true;
    public static int maxEntityPerChunk = 10;
    // SourbyCraft start - lag prevention
    public static int maxRedstoneUpdatesPerTick = 2000;
    public static int maxSpecialsPerChunk = 15;
    public static int maxFallingBlockPerChunk = 20;
    public static int maxArrowsPerWorld = 5000;
    // SourbyCraft end
    // SourbyCraft v9 — performance.v9 block (each feature has its own killswitch + circuit breaker).
    public static boolean v9Enabled = true;
    public static boolean v9AsyncLighting = true;
    public static boolean v9AsyncPathfind = true;
    public static boolean v9AsyncChunkSave = true;
    // SourbyCraft v9.10 - parallel-tick defaults FALSE; physics phase is currently noop and
    // deferred end-of-tick drain caused floating items (no real perf benefit until v10+ real physics split).
    public static boolean v9ParallelMobAi = false;
    public static boolean v9ParallelItemTick = false;
    public static boolean v9ParallelOrbArrowTick = false;
    public static boolean v9RegionIoPool = true;
    public static boolean v9ObjectPools = true;
    public static int v9PoolSizeLighting = 2;
    public static int v9PoolSizePathfind = 4;
    public static int v9PoolSizeChunkSave = 2;
    public static int v9PoolSizeItemTick = 3;
    public static int v9PoolSizeMobAi = 4;
    public static int v9PoolSizeRegionIo = 4;
    public static double v9WatchdogTaskTimeoutMultiplier = 5.0;
    public static int v9WatchdogCircuitBreakSeconds = 30;
    public static int v9BackpressureQueueDepthCap = 256;
    // SourbyCraft v9.5 — wildstacker ground item merge (virtual stack count)
    public static boolean wildstackerEnabled = true;
    public static boolean wildstackerHologram = true;        // SourbyCraft v9.11
    public static boolean wildstackerLosCheck = true;        // SourbyCraft v9.11 — anti-fraud
    // SourbyCraft v10.0 — knockback manipulation
    public static boolean knockbackGlobalEnabled = true;
    // SourbyCraft end knockback
    // SourbyCraft start - antixray
    public static boolean fluidObscures = true;
    // SourbyCraft end
    // SourbyCraft start - disable vanilla commands
    public static boolean disableCommunicationCommands = false;
    // SourbyCraft end
    // SourbyCraft start - no durability
    public static boolean noDurabilityExcept = false;
    // SourbyCraft end
    public static int idleTimeout = 0;
    public static boolean itemMergeOptimize = true;
    public static int itemDespawnRate = 6000;
    // SourbyCraft v9.13 — was 1; raised to 3 so a player dropping a full inventory of dirt
    // (items scattered ~1-2 blocks apart) reliably merges into one entity.
    public static int itemMergeRadius = 3;
    public static boolean unlimitedDropStack = true;
    public static int dropStackCap = Integer.MAX_VALUE;
    public static boolean ownerProtectionEnabled = true;
    public static int ownerProtectionTime = 10;
    // SourbyCraft v10.2 — Alternate Current redstone optimization (Paper's built-in AC, default OFF)
    public static boolean redstoneAlternateCurrent = false;
    // SourbyCraft end
    // SourbyCraft v9.13 — was true; ItemEntityPool recycles entity instances and can return
    // stale objects with noGravity=true or a frozen velocity vector, causing items to levitate.
    // Disabled until pool correctly resets all entity state on recycle.
    public static boolean itemPoolEnabled = false;
    public static int itemPoolSize = 256;
    public static int itemPoolMaxGrowth = 1024;
    public static float itemPoolShrinkThreshold = 0.5f;
    public static int itemMaxPerChunk = 64;
    public static int mobTickDistance = 32;
    public static int mobPathfindInterval = 20;
    public static boolean asyncSaveBatch = true;

    // DAB entity overrides: key = "minecraft:zombie", value = [maxTickFreq, activationDistMod]
    public static final java.util.Map<String, int[]> dabEntityOverrides = new java.util.concurrent.ConcurrentHashMap<>();

    public static void init(File configFile) {
        CONFIG_FILE = configFile;
        config = new YamlConfiguration();

        try {
            config.load(CONFIG_FILE);
        } catch (IOException e) {
            Bukkit.getLogger().warning("Could not load " + configFile.getName() + ", starting with defaults: " + e.getMessage());
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

        multithreadingEnabled = getBoolean("multithreading.enabled", multithreadingEnabled);
        virtualThreads = getBoolean("performance.virtual-threads", virtualThreads);
        structuredConcurrency = getBoolean("performance.structured-concurrency", structuredConcurrency);
        maxPlatformThreads = getInt("performance.max-platform-threads", maxPlatformThreads);

        skipEmptySections = getBoolean("memory.skip-empty-sections", skipEmptySections);
        poolEntityData = getBoolean("memory.pool-entity-data", poolEntityData);
        preSizePackets = getBoolean("memory.pre-size-packets", preSizePackets);
        chunkCompressionCache = getBoolean("memory.chunk-compression-cache", chunkCompressionCache);

        swmEnabled = getBoolean("swm.enabled", swmEnabled);
        swmVersion = getString("swm.version", swmVersion);
        swmAutoInstall = getBoolean("swm.auto-install", swmAutoInstall);
        swmAutoUpdate = getBoolean("swm.auto-update", swmAutoUpdate);
        swmFileDir = getString("swm.file-dir", swmFileDir);

        // Auto-install SWM plugin from GitHub releases
        if (swmEnabled && swmAutoInstall) {
        // SourbyCraft start - auto-create required folders
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of(swmFileDir)); } catch (java.io.IOException e) { Bukkit.getLogger().warning("Could not create " + swmFileDir + ": " + e.getMessage()); }
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of("mods")); } catch (java.io.IOException e) { Bukkit.getLogger().warning("Could not create mods directory: " + e.getMessage()); }
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of("plugins")); } catch (java.io.IOException e) { Bukkit.getLogger().warning("Could not create plugins directory: " + e.getMessage()); }
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of("plugins/SourbyCraft")); } catch (java.io.IOException e) { Bukkit.getLogger().warning("Could not create plugins/SourbyCraft directory: " + e.getMessage()); }
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of("plugins/SourbyCraft/speedtest")); } catch (java.io.IOException e) { Bukkit.getLogger().warning("Could not create speedtest directory: " + e.getMessage()); }
        // SourbyCraft end
            dev.iyanz.sourbycraft.swm.installer.PluginInstaller.install("plugins/");
        }

        // SourbyCraft start - auto-create mods folder
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of("mods")); } catch (java.io.IOException e) { Bukkit.getLogger().warning("Could not create mods directory: " + e.getMessage()); }
        // SourbyCraft end

        if (version < currentVersion) {
            // SourbyCraft start - migration: update SWM version tag
            String oldSwmVer = swmVersion;
            if ("v4-REL".equals(swmVersion) || "v5-REL".equals(swmVersion) || "1.21.11".equals(swmVersion)) {
                swmVersion = "v6-REL";
                set("swm.version", swmVersion);
            }
            // SourbyCraft end
            if ("v6-REL".equals(swmVersion)) {
                swmVersion = "v7-REL";
                set("swm.version", swmVersion);
            }
        }

        autoThrottleView = getBoolean("network.auto-throttle-view", autoThrottleView);
        minViewDistance = getInt("network.min-view-distance", minViewDistance);
        compressionLevel = getInt("network.compression-level", compressionLevel);
        entityTickRateLimit = getBoolean("entity.tick-rate-limit", entityTickRateLimit);
        entityTickRate = getInt("entity.tick-rate", entityTickRate);
        v9Enabled = getBoolean("performance.v9.enabled", v9Enabled);
        v9AsyncLighting = getBoolean("performance.v9.async-lighting", v9AsyncLighting);
        v9AsyncPathfind = getBoolean("performance.v9.async-pathfind", v9AsyncPathfind);
        v9AsyncChunkSave = getBoolean("performance.v9.async-chunk-save", v9AsyncChunkSave);
        v9ParallelMobAi = getBoolean("performance.v9.parallel-mob-ai", v9ParallelMobAi);
        v9ParallelItemTick = getBoolean("performance.v9.parallel-item-tick", v9ParallelItemTick);
        v9ParallelOrbArrowTick = getBoolean("performance.v9.parallel-orb-arrow-tick", v9ParallelOrbArrowTick);
        v9RegionIoPool = getBoolean("performance.v9.region-io-pool", v9RegionIoPool);
        v9ObjectPools = getBoolean("performance.v9.object-pools", v9ObjectPools);
        v9PoolSizeLighting = clamp(getInt("performance.v9.pool-size.lighting", v9PoolSizeLighting), 1, 16);
        v9PoolSizePathfind = clamp(getInt("performance.v9.pool-size.pathfind", v9PoolSizePathfind), 1, 16);
        v9PoolSizeChunkSave = clamp(getInt("performance.v9.pool-size.chunk-save", v9PoolSizeChunkSave), 1, 16);
        v9PoolSizeItemTick = clamp(getInt("performance.v9.pool-size.item-tick", v9PoolSizeItemTick), 1, 16);
        v9PoolSizeMobAi = clamp(getInt("performance.v9.pool-size.mob-ai", v9PoolSizeMobAi), 1, 16);
        v9PoolSizeRegionIo = clamp(getInt("performance.v9.pool-size.region-io", v9PoolSizeRegionIo), 1, 16);
        v9WatchdogTaskTimeoutMultiplier = getDouble("performance.v9.watchdog.task-timeout-multiplier", v9WatchdogTaskTimeoutMultiplier);
        v9WatchdogCircuitBreakSeconds = clamp(getInt("performance.v9.watchdog.circuit-break-seconds", v9WatchdogCircuitBreakSeconds), 1, 3600);
        v9BackpressureQueueDepthCap = clamp(getInt("performance.v9.backpressure.queue-depth-cap", v9BackpressureQueueDepthCap), 16, 65536);
        wildstackerEnabled = getBoolean("performance.wildstacker.enabled", wildstackerEnabled);
        wildstackerHologram = getBoolean("performance.wildstacker.hologram", wildstackerHologram);
        wildstackerLosCheck = getBoolean("performance.wildstacker.los-check", wildstackerLosCheck);
        // SourbyCraft v10.0 — knockback manipulation
        knockbackGlobalEnabled = getBoolean("combat.knockback.global-enabled", knockbackGlobalEnabled);
        // SourbyCraft end knockback
        hopperBatch = getBoolean("entity.hopper-batch", hopperBatch);
        redstoneOptimize = getBoolean("entity.redstone-optimize", redstoneOptimize);
        // SourbyCraft v10.2 — Alternate Current redstone optimization (delegates to Paper's built-in AC)
        redstoneAlternateCurrent = getBoolean("performance.redstone.alternate-current", redstoneAlternateCurrent);
        // SourbyCraft end
        maxEntityPerChunk = getInt("entity.max-per-chunk", maxEntityPerChunk);
        // SourbyCraft start - lag prevention
        maxSpecialsPerChunk = getInt("entity.max-specials-per-chunk", maxSpecialsPerChunk);
        maxFallingBlockPerChunk = getInt("entity.max-falling-block-per-chunk", maxFallingBlockPerChunk);
        maxArrowsPerWorld = getInt("entity.max-arrows-per-world", maxArrowsPerWorld);
        // SourbyCraft end
        // SourbyCraft start - antixray
        fluidObscures = getBoolean("antixray.fluid-obscures", fluidObscures);
        // SourbyCraft end
        // SourbyCraft start - no durability
        noDurabilityExcept = getBoolean("item.no-durability-except", noDurabilityExcept);
        // SourbyCraft end
        // SourbyCraft start - disable vanilla commands
        disableCommunicationCommands = getBoolean("settings.disable-communication-commands", disableCommunicationCommands);
        // SourbyCraft end
        idleTimeout = getInt("server.idle-timeout", idleTimeout);
        itemMergeOptimize = getBoolean("entity.item-merge-optimize", itemMergeOptimize);
        itemDespawnRate = getInt("entity.item-despawn-rate", itemDespawnRate);
        itemMergeRadius = getInt("entity.item-merge-radius", itemMergeRadius);
        // SourbyCraft v9 - dynamic-max-stack-size removed (NBT codec mismatch, see spec §13b).
        // Will be re-delivered as wildstacker custom-NBT count in a follow-up sub-spec.
        if (config.contains("dynamic-max-stack-size") || config.contains("item.max-stack-size")) {
            Bukkit.getLogger().warning(
                "[SourbyCraft] dynamic-max-stack-size (item.max-stack-size) is deprecated and ignored as of v9. " +
                "It caused ItemEntity NBT serialization failures (count > 99). " +
                "Wildstacker-style stacking will return via a separate patch in a future release."
            );
        }
        unlimitedDropStack = getBoolean("item.unlimited-drop-stack", unlimitedDropStack);
        dropStackCap = getInt("item.drop-stack-cap", dropStackCap);
        ownerProtectionEnabled = getBoolean("item.owner-protection-enabled", ownerProtectionEnabled);
        ownerProtectionTime = Math.min(getInt("item.owner-protection-time", ownerProtectionTime), 1638);
        itemPoolEnabled = getBoolean("item.pool-enabled", itemPoolEnabled);
        itemPoolSize = getInt("item.pool-size", itemPoolSize);
        itemPoolMaxGrowth = getInt("item.pool-max-growth", itemPoolMaxGrowth);
        itemPoolShrinkThreshold = (float) getDouble("item.pool-shrink-threshold", itemPoolShrinkThreshold);
        itemMaxPerChunk = getInt("item.max-per-chunk", itemMaxPerChunk);
        noDurabilityExcept = getBoolean("item.no-durability-except", noDurabilityExcept);
        mobTickDistance = getInt("entity.mob-tick-distance", mobTickDistance);
        mobPathfindInterval = getInt("entity.mob-pathfind-interval", mobPathfindInterval);
        asyncSaveBatch = getBoolean("chunk.async-save-batch", asyncSaveBatch);

        // DAB entity overrides
        org.bukkit.configuration.ConfigurationSection dabSec = config.getConfigurationSection("dab.entity-overrides");
        if (dabSec != null) {
            for (String key : dabSec.getKeys(false)) {
                int freq = dabSec.getInt(key + ".max-tick-freq", 20);
                int mod = dabSec.getInt(key + ".activation-dist-mod", 8);
                dabEntityOverrides.put(key, new int[]{freq, mod});
            }
        }

        if (idleTimeout > 0) {
            // Idle timeout will be implemented via scheduler
        }

        dev.iyanz.sourbycraft.util.VirtualExecutor.init();
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

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

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
        // GossipType fields are now final in Paper 1.21.11 — config skipped
    }
}
