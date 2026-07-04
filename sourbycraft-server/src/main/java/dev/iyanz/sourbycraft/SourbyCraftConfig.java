package dev.iyanz.sourbycraft;

import gg.pufferfish.pufferfish.util.AsyncExecutor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.world.entity.ai.gossip.GossipType;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class SourbyCraftConfig {

    // SourbyCraft v12 — sourbycraft.yml resource loader (single-jar, no variants).
    private static final Map<String, Object> sourbycraftYmlBaseline = loadYmlResource("/sourbycraft.yml");

    private static Map<String, Object> loadYmlResource(String resource) {
        try (InputStream in = SourbyCraftConfig.class.getResourceAsStream(resource)) {
            if (in == null) return Map.of();
            // SourbyCraft - SafeConstructor (defense-in-depth; resource is classpath-baked)
            Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
            Map<String, Object> y = yaml.load(in);
            return y == null ? Map.of() : y;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Read a value from sourbycraft.yml using a dotted path. Returns
     * {@code defaultValue} when any path segment is missing.
     */
    @SuppressWarnings("unchecked")
    public static <T> T ymlGet(String dottedPath, T defaultValue) {
        Object baseVal = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (baseVal != null) {
            try { return (T) baseVal; } catch (ClassCastException ignored) {}
        }
        return defaultValue;
    }

    /**
     * Type-safe boolean read from sourbycraft.yml. Returns {@code defaultValue} when the
     * key is missing or the value cannot be cast to Boolean.
     */
    public static boolean ymlBool(String dottedPath, boolean defaultValue) {
        Object v = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (v instanceof Boolean b) return b;
        if (v != null) warnOnce(dottedPath, v, "boolean");
        return defaultValue;
    }

    /**
     * Type-safe int read from sourbycraft.yml. Coerces any {@link Number} via
     * {@code intValue()}. Returns {@code defaultValue} when the key is missing
     * or the value is not numeric.
     */
    public static int ymlInt(String dottedPath, int defaultValue) {
        Object v = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (v instanceof Number n) return n.intValue();
        if (v != null) warnOnce(dottedPath, v, "int");
        return defaultValue;
    }

    /**
     * Type-safe double read from sourbycraft.yml. Coerces any {@link Number} via
     * {@code doubleValue()}. Returns {@code defaultValue} when the key is missing
     * or the value is not numeric.
     */
    public static double ymlDouble(String dottedPath, double defaultValue) {
        Object v = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) warnOnce(dottedPath, v, "double");
        return defaultValue;
    }

    /**
     * Reads a list of strings from sourbycraft.yml. Returns an empty list when the
     * key is missing or the value is not a List. Non-string entries are filtered out
     * with one WARN per offending value position.
     */
    public static java.util.List<String> ymlStringList(String dottedPath) {
        Object v = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (!(v instanceof java.util.List<?> raw)) {
            if (v != null) warnOnce(dottedPath, v, "List<String>");
            return java.util.List.of();
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>(raw.size());
        int idx = 0;
        for (Object item : raw) {
            if (item instanceof String s) {
                out.add(s);
            } else {
                warnOnce(dottedPath + "[" + idx + "]", item, "String");
            }
            idx++;
        }
        return java.util.Collections.unmodifiableList(out);
    }

    /**
     * Parses a list of {@code "EntityType:Number"} entries from sourbycraft.yml into
     * a typed map. Bad entries are skipped with one WARN each; valid entries collected.
     * Returns an empty map when the key is missing or the value is not a List.
     */
    public static java.util.Map<org.bukkit.entity.EntityType, Integer> ymlEntityTypeMap(String dottedPath) {
        Object v = lookupYml(sourbycraftYmlBaseline, dottedPath);
        if (!(v instanceof java.util.List<?> raw)) {
            if (v != null) warnOnce(dottedPath, v, "List<String>");
            return java.util.Map.of();
        }
        java.util.EnumMap<org.bukkit.entity.EntityType, Integer> out =
            new java.util.EnumMap<>(org.bukkit.entity.EntityType.class);
        int idx = 0;
        for (Object item : raw) {
            if (item instanceof String s) {
                Map.Entry<org.bukkit.entity.EntityType, Integer> entry = parseEntityTypeEntry(s);
                if (entry != null) {
                    out.put(entry.getKey(), entry.getValue());
                } else {
                    warnOnce(dottedPath + "[" + idx + "]", s, "EntityType:Number");
                }
            } else {
                warnOnce(dottedPath + "[" + idx + "]", item, "String");
            }
            idx++;
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * Parses a single {@code "TYPE:N"} entry. Returns {@code null} on any failure
     * (no colon, blank type, non-numeric value, unknown EntityType). Package-private
     * so it can be unit-tested without a populated baseline.
     */
    static java.util.Map.Entry<org.bukkit.entity.EntityType, Integer> parseEntityTypeEntry(String entry) {
        if (entry == null) return null;
        int colon = entry.indexOf(':');
        if (colon <= 0 || colon == entry.length() - 1) return null;
        String typeStr = entry.substring(0, colon).trim();
        String numStr = entry.substring(colon + 1).trim();
        if (typeStr.isEmpty() || numStr.isEmpty()) return null;
        try {
            org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(typeStr);
            int num = Integer.parseInt(numStr);
            return java.util.Map.entry(type, num);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Hot-path cache for dotted-path lookups. {@code ymlBool}/{@code ymlInt}/etc are called
     * per-entity-tick from NMS patches (LivingEntity#aiStep particle/sound gates, Entity#playStepSound,
     * etc) so the cost of split + map traversal compounds quickly. Cache misses populate
     * the map; cache returns the {@link #SENTINEL_ABSENT} marker for missing keys to avoid
     * re-walking the YAML map on every absent lookup.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOOKUP_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object SENTINEL_ABSENT = new Object();

    private static Object lookupYml(Map<String, Object> root, String dottedPath) {
        Object cached = LOOKUP_CACHE.get(dottedPath);
        if (cached != null) {
            return cached == SENTINEL_ABSENT ? null : cached;
        }
        Object cur = root;
        for (String seg : dottedPath.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) { cur = null; break; }
            cur = m.get(seg);
            if (cur == null) break;
        }
        LOOKUP_CACHE.put(dottedPath, cur == null ? SENTINEL_ABSENT : cur);
        return cur;
    }

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

    // Entity stacker (re-port from legacy WildStacker, slimmer)
    public static boolean stackerEnabled = false;
    public static double stackerRadius = 10.0;
    public static int stackerMaxStack = 100;
    public static java.util.List<String> stackerBlacklist = java.util.List.of("PLAYER", "ARMOR_STAND", "ENDER_DRAGON", "WITHER");
    public static boolean stackerHologram = true;
    public static boolean stackerLosCheck = true;
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
    public static int entityTickRate() {
        return dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.get();
    }
    public static boolean hopperBatch = true;
    public static boolean redstoneOptimize = true;
    public static int maxEntityPerChunk = 10;
    // SourbyCraft start - lag prevention
    public static int maxRedstoneUpdatesPerTick = 2000;
    public static int maxSpecialsPerChunk = 15;
    public static int maxFallingBlockPerChunk = 20;
    public static int maxArrowsPerWorld = 5000;
    // SourbyCraft end
    // SourbyCraft start - antixray
    public static boolean fluidObscures = true;
    public static int raytraceIntervalTicks = 10;
    public static int raytraceDistance = 48;
    public static int raytraceMaxChecksPerCycle = 192;
    public static int raytraceMaxPendingPerPlayer = 8192;
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

        // SourbyCraft - boot-time JVM heap configuration advisory
        try {
            dev.iyanz.sourbycraft.perf.JvmHeapAdvisor.init();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("JvmHeapAdvisor.init failed", t);
        }

        // SourbyCraft - perf-engine P0: load JAR-baked perf knobs BEFORE Bukkit-config block
        dev.iyanz.sourbycraft.perf.knob.Knobs.loadFromYml();
        try {
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.loadFromYml();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("PerfSensor.loadFromYml failed; using defaults", t);
        }
        // SourbyCraft - perf-engine P4: apply combat-profile preset on top of P0 defaults
        try {
            String profileName = getString("combat.profile", "vanilla");
            dev.iyanz.sourbycraft.perf.CombatProfile.parse(profileName,
                dev.iyanz.sourbycraft.perf.CombatProfile.VANILLA).apply();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("CombatProfile.apply failed; using P0 defaults", t);
        }
        // SourbyCraft - RayTraceAntiXray opt-in toggle
        try {
            dev.iyanz.sourbycraft.antixray.RayTraceWorker.ENABLED.set(
                getBoolean("antixray.raytrace.enabled", false));
            dev.iyanz.sourbycraft.antixray.EntityVisibilityCheck.ENABLED.set(
                getBoolean("antixray.entity-raytrace.enabled", false));
            dev.iyanz.sourbycraft.antixray.ParticleVisibilityCheck.ENABLED.set(
                getBoolean("antixray.particle-raytrace.enabled", false));
            raytraceIntervalTicks = Math.max(1, getInt("antixray.raytrace.interval-ticks", raytraceIntervalTicks));
            raytraceDistance = Math.max(8, Math.min(128, getInt("antixray.raytrace.distance", raytraceDistance)));
            raytraceMaxChecksPerCycle = Math.max(16, Math.min(2048, getInt("antixray.raytrace.max-checks-per-cycle", raytraceMaxChecksPerCycle)));
            raytraceMaxPendingPerPlayer = Math.max(512, Math.min(65536, getInt("antixray.raytrace.max-pending-per-player", raytraceMaxPendingPerPlayer)));
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("RayTrace antixray toggle bridge failed", t);
        }

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

        // SourbyCraft - emoji shortcode chat translator (:smile: -> 😀 etc.).
        // Defaults loaded from EmojiShortcodes#defaults(); operators can override
        // / extend via `emoji.shortcodes.codes` in sourbycraft.yml.
        try {
            boolean emojiEnabled = getBoolean("emoji.shortcodes.enabled", true);
            dev.iyanz.sourbycraft.chat.EmojiShortcodes.setEnabled(emojiEnabled);
            org.bukkit.configuration.ConfigurationSection codesSec =
                    config.getConfigurationSection("emoji.shortcodes.codes");
            if (codesSec == null) {
                // Write the default map back so operators can see + edit it.
                org.bukkit.configuration.ConfigurationSection created =
                        config.createSection("emoji.shortcodes.codes");
                dev.iyanz.sourbycraft.chat.EmojiShortcodes.map().forEach(created::set);
            } else {
                java.util.Map<String, String> custom = new java.util.LinkedHashMap<>();
                for (String key : codesSec.getKeys(false)) {
                    Object raw = codesSec.get(key);
                    if (raw != null) custom.put(key, raw.toString());
                }
                dev.iyanz.sourbycraft.chat.EmojiShortcodes.replaceAll(custom);
            }
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("EmojiShortcodes init failed; defaults still active", t);
        }

        swmEnabled = getBoolean("swm.enabled", swmEnabled);
        swmVersion = getString("swm.version", swmVersion);
        // Entity stacker config (re-port era v10+)
        // S3: legacy performance.wildstacker.* keys (pre-26.1.2 operator files) seed the
        // canonical stacker.* keys when those are absent. Explicit stacker.* always wins.
        // Legacy keys stay in the file as documented aliases — never deleted.
        if (!config.isSet("stacker.enabled") && config.getBoolean("performance.wildstacker.enabled", false)) {
            config.set("stacker.enabled", true);
        }
        if (!config.isSet("stacker.hologram") && config.isSet("performance.wildstacker.hologram")) {
            config.set("stacker.hologram", config.getBoolean("performance.wildstacker.hologram", true));
        }
        if (!config.isSet("stacker.los-check") && config.isSet("performance.wildstacker.los-check")) {
            config.set("stacker.los-check", config.getBoolean("performance.wildstacker.los-check", true));
        }
        stackerEnabled = getBoolean("stacker.enabled", stackerEnabled);
        stackerRadius = getDouble("stacker.radius", stackerRadius);
        stackerMaxStack = getInt("stacker.max-stack", stackerMaxStack);
        stackerHologram = getBoolean("stacker.hologram", stackerHologram);
        stackerLosCheck = getBoolean("stacker.los-check", stackerLosCheck);
        try {
            java.util.List<?> raw = config.getList("stacker.blacklist");
            if (raw == null) {
                set("stacker.blacklist", stackerBlacklist);
            } else {
                java.util.List<String> parsed = new java.util.ArrayList<>();
                for (Object o : raw) if (o != null) parsed.add(o.toString());
                stackerBlacklist = parsed;
            }
        } catch (Throwable ignored) {}
        swmAutoInstall = getBoolean("swm.auto-install", swmAutoInstall);
        swmAutoUpdate = getBoolean("swm.auto-update", swmAutoUpdate);
        swmFileDir = getString("swm.file-dir", swmFileDir);
        // SourbyCraft - reject swm.file-dir that escapes the server root or hits the FS root
        if (swmFileDir.contains("..") || swmFileDir.startsWith("/") || swmFileDir.startsWith("\\")
            || (swmFileDir.length() > 1 && swmFileDir.charAt(1) == ':')) {
            Bukkit.getLogger().warning("Ignoring unsafe swm.file-dir='" + swmFileDir + "', falling back to slime_worlds");
            swmFileDir = "slime_worlds";
        }

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
        dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.set(
            getInt("entity.tick-rate", dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.get())
        );
        hopperBatch = getBoolean("entity.hopper-batch", hopperBatch);
        redstoneOptimize = getBoolean("entity.redstone-optimize", redstoneOptimize);
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


        // SourbyCraft - perf-engine P1: operator sourbycraft.yml bridge for sensor settings.
        // Uses config.get() (no addDefault) so operator yml is NOT polluted with sensor keys on first boot.
        // Only keys explicitly present in the operator yml override JAR-baked defaults.
        try {
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.applyOperatorConfig(
                cfgBool("perf.sensor.enabled", true),
                cfgInt("perf.sensor.warmup-ticks", 200),
                cfgInt("perf.sensor.cadence-ticks", 20),
                cfgInt("perf.sensor.dwell-samples", 3),
                cfgDouble("perf.sensor.recovery-dwell-multiplier", 2.0),
                cfgDouble("perf.sensor.thresholds.mspt.yellow",   30.0),
                cfgDouble("perf.sensor.thresholds.mspt.orange",   40.0),
                cfgDouble("perf.sensor.thresholds.mspt.red",      60.0),
                cfgDouble("perf.sensor.thresholds.mspt.emergency",100.0),
                cfgDouble("perf.sensor.thresholds.tps.yellow",    19.5),
                cfgDouble("perf.sensor.thresholds.tps.orange",    18.0),
                cfgDouble("perf.sensor.thresholds.tps.red",       15.0),
                cfgDouble("perf.sensor.thresholds.tps.emergency", 10.0),
                cfgDouble("perf.sensor.thresholds.mem.yellow",    75.0),
                cfgDouble("perf.sensor.thresholds.mem.orange",    85.0),
                cfgDouble("perf.sensor.thresholds.mem.red",       92.0),
                cfgDouble("perf.sensor.thresholds.mem.emergency", 97.0),
                cfgDouble("perf.sensor.thresholds.gc-ms-per-min.yellow",    20.0),
                cfgDouble("perf.sensor.thresholds.gc-ms-per-min.orange",    50.0),
                cfgDouble("perf.sensor.thresholds.gc-ms-per-min.red",      100.0),
                cfgDouble("perf.sensor.thresholds.gc-ms-per-min.emergency",300.0)
            );
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("PerfSensor.applyOperatorConfig failed; using yml defaults", t);
        }

        // SourbyCraft - perf-engine P2: operator sourbycraft.yml bridge for lag-machine knobs.
        try {
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_DISABLE_SAVING_SNOWBALLS.set(
                cfgBool("perf.lag-machine.disable-saving-snowballs", true));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_DISABLE_SAVING_FIREWORKS.set(
                cfgBool("perf.lag-machine.disable-saving-fireworks", true));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_TICK.set(
                cfgInt("perf.lag-machine.max-projectile-loads-per-tick", 10));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_MAX_PROJECTILE_LOADS_PER_PROJECTILE.set(
                cfgInt("perf.lag-machine.max-projectile-loads-per-projectile", 10));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_REMOVE_EXCESS_MINECARTS.set(
                cfgBool("perf.lag-machine.remove-excess-minecarts", false));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_EXCESS_MINECARTS_LIMIT.set(
                cfgInt("perf.lag-machine.excess-minecarts-limit", 10));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_REMOVE_EXCESS_BOATS.set(
                cfgBool("perf.lag-machine.remove-excess-boats", false));
            dev.iyanz.sourbycraft.perf.knob.Knobs.LAG_MACHINE_EXCESS_BOATS_LIMIT.set(
                cfgInt("perf.lag-machine.excess-boats-limit", 10));
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("P2 lag-machine operator-config bridge failed; using yml defaults", t);
        }

        // SourbyCraft - perf-engine P0: log final knob values AFTER all Bukkit-config bridge overrides
        dev.iyanz.sourbycraft.perf.knob.Knobs.logLoaded();

        dev.iyanz.sourbycraft.util.VirtualExecutor.init();

        // SourbyCraft - final save: readConfig() saves after its reflective field walk but every
        // post-readConfig getBoolean/getInt/getString call (antixray, particle, perf-sensor bridge,
        // combat profile etc) writes new defaults to the in-memory config that the early save
        // never sees. Persist those now so sourbycraft.yml is a complete materialisation of the
        // baseline on first boot instead of the four-line stub operators currently get.
        try {
            config.save(CONFIG_FILE);
        } catch (IOException exception) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not save final " + CONFIG_FILE, exception);
        }
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

    /**
     * Read a key, persisting the default when missing.
     *
     * <p>Earlier versions relied on {@code config.addDefault(...)} +
     * {@code copyDefaults(true)} to materialise unset keys at save
     * time, but the YamlConfiguration default-merge path does not
     * actually serialise defaults that were never read through the
     * tree visitor — operators ended up with a near-empty
     * {@code sourbycraft.yml} that contained only the keys our code
     * wrote via {@code set(...)}. We now use {@code config.set} when
     * the key is absent so every default the boot path consults
     * lands on disk on the next save.
     */
    private static boolean getBoolean(String path, boolean def) {
        if (!config.isSet(path)) {
            config.set(path, def);
        }
        return config.getBoolean(path, def);
    }

    private static double getDouble(String path, double def) {
        if (!config.isSet(path)) {
            config.set(path, def);
        }
        return config.getDouble(path, def);
    }

    private static int getInt(String path, int def) {
        if (!config.isSet(path)) {
            config.set(path, def);
        }
        return config.getInt(path, def);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> getList(String path, List<T> def) {
        if (!config.isSet(path)) {
            config.set(path, def);
        }
        List<?> raw = config.getList(path, def);
        return (List<T>) raw;
    }

    private static String getString(String path, String def) {
        if (!config.isSet(path)) {
            config.set(path, def);
        }
        return config.getString(path, def);
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    /**
     * Read from the operator Bukkit config WITHOUT calling addDefault, so the key is NOT
     * written to the operator yml when absent. Used for perf-engine P1 sensor bridge to
     * avoid polluting the operator yml with sensor keys on first boot.
     */
    private static boolean cfgBool(String path, boolean def) {
        Object v = config.get(path);
        if (v instanceof Boolean b) return b;
        return def;
    }

    private static int cfgInt(String path, int def) {
        Object v = config.get(path);
        if (v instanceof Number n) return n.intValue();
        return def;
    }

    private static double cfgDouble(String path, double def) {
        Object v = config.get(path);
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }

    // SourbyCraft v12 — UniverseSpigot config import accessors. Once-per-startup WARN dedupe
    // so a single malformed key doesn't spam the log every tick a patch reads it.
    private static final java.util.Set<String> WARNED_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void warnOnce(String path, Object actual, String expected) {
        if (WARNED_KEYS.add(path)) {
            dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                "[SourbyCraft] config key '" + path + "' invalid type '"
                + actual.getClass().getSimpleName()
                + "', expected " + expected + " — using default"
            );
        }
    }

    /** Test-only view of the dedupe set. Snapshot — mutations to the returned set do not affect WARNED_KEYS. */
    static java.util.Set<String> warnedKeysForTest() {
        return java.util.Set.copyOf(WARNED_KEYS);
    }

    private static Component getComponent(String path, Component def) {
        // Defensive: the operator may have edited the config with legacy &-codes,
        // Adventure &#RRGGBB hex, or malformed MiniMessage. TextRender catches
        // any parser failure and returns the input as plain text so the boot
        // sequence cannot crash on a typo in sourbycraft.yml.
        String raw = getString(path, MiniMessage.miniMessage().serialize(def));
        return dev.iyanz.sourbycraft.util.TextRender.parseOr(raw, def);
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
