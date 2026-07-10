package dev.iyanz.sourbycraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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

/**
 * SourbyCraft config registry (Folia base, F2a port).
 *
 * <p>This is the REAL config registry ported from the Paper line
 * ({@code paper-26.2-pre-folia}): the {@code sourbycraft.yml} baseline
 * resource loader, the dotted-path {@code ymlGet}/{@code ymlBool}/
 * {@code ymlInt}/{@code ymlDouble}/{@code ymlStringList}/
 * {@code ymlEntityTypeMap} accessors with the hot-path {@code LOOKUP_CACHE},
 * the full set of public config fields, and the {@code config.*} Bukkit-yaml
 * getters. The knob system ({@link dev.iyanz.sourbycraft.perf.knob.Knobs},
 * {@link dev.iyanz.sourbycraft.perf.knob.KnobRegistry}), {@link
 * dev.iyanz.sourbycraft.perf.SupersededKeys} and {@link
 * dev.iyanz.sourbycraft.perf.ConfigBridge} are all wired against this class
 * and are equally real.
 *
 * <p><b>F2a scope boundary.</b> The full Paper {@code init()} also bridged the
 * self-tune sensor, combat-profile presets, the JVM-heap advisor, the antixray
 * ray-tracers, the emoji chat translator and the mod loader. Those subsystems
 * are NOT on the Folia base yet — the perf sensor + self-tune loop land in F2b,
 * the actuators (CombatProfile, view-throttle, lag-machine actuators) in F2c,
 * and antixray/chat/mod are separate ports. Every such bridge below is either
 * omitted with a {@code // F2b}/{@code // F2c}/{@code // out-of-scope} marker,
 * or wrapped so a missing class cannot crash boot. The registry itself is
 * complete and usable today.
 */
public class SourbyCraftConfig {

    // SourbyCraft v12 — sourbycraft.yml resource loader (single-jar, no variants).
    // Folia base ships no baked /sourbycraft.yml yet, so this resolves to an empty
    // baseline and every ymlGet returns its supplied default — same observable
    // behaviour as the F1-1 stub, but backed by the real lookup machinery.
    private static final Map<String, Object> sourbycraftYmlBaseline = loadYmlResource("/sourbycraft.yml");

    // SourbyCraft F3-2 (config unification) — the perf-engine (knobs, sensor, spark, gc-advisor)
    // reads its keys through the ymlGet/ymlBool/ymlInt/ymlDouble accessors below. Historically
    // those read ONLY the classpath /sourbycraft.yml baseline (empty on Folia => always default).
    // F3-2 unifies the operator surface onto the single Luminol config file
    // (sourbycraft_config/sourbycraft_global_config.toml): the accessors now consult that file
    // FIRST (by identical dotted path — perf.sensor.cadence-ticks, perf.lag-machine.*, spark.*,
    // branding.*, perf.entity-tick-rate, perf.ai.*), then fall back to the classpath baseline,
    // then to the supplied default. There is no separate mandatory sourbycraft.yml. The Luminol
    // config instance is fully loaded by the time SourbyCraftConfig.init() runs (Main.initConfigs
    // preloads it, DedicatedServer.loadConfigFiles finalizes it, then the post-config hook calls
    // PerfEngineBootstrap.start -> init), so this lookup is always populated at read time.
    //
    // Resolved reflectively/lazily so this class carries no hard compile/init dependency on the
    // me.earthme.luminol config package init order. Cached once resolved.
    private static volatile Object unifiedFileInstance; // com.electronwill.nightconfig.core.file.CommentedFileConfig
    private static volatile boolean unifiedResolveTried;

    private static Object unifiedFile() {
        Object cached = unifiedFileInstance;
        if (cached != null) return cached;
        if (unifiedResolveTried) return null;
        try {
            me.earthme.luminol.config.ConfigsInstance inst =
                me.earthme.luminol.config.ConfigManager.getConfigs("sourbycraft");
            if (inst != null) {
                Object file = inst.getFileInstance();
                unifiedFileInstance = file;
                unifiedResolveTried = true;
                return file;
            }
        } catch (Throwable ignored) {
            // Luminol config not available (e.g. unit test context) — fall back to classpath baseline.
        }
        return null;
    }

    /**
     * Look up a dotted path in the unified Luminol config file. Returns {@code null} when the
     * config instance is not yet available or the key is absent. nightconfig's
     * {@code CommentedFileConfig#get(String)} treats {@code .} as a path separator, matching the
     * dotted keys the perf-engine uses, so {@code perf.sensor.cadence-ticks} resolves directly.
     */
    private static Object lookupUnified(String dottedPath) {
        Object file = unifiedFile();
        if (file == null) return null;
        try {
            return ((com.electronwill.nightconfig.core.file.CommentedFileConfig) file).get(dottedPath);
        } catch (Throwable ignored) {
            return null;
        }
    }

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
        Object unified = lookupUnified(dottedPath); // F3-2: unified Luminol TOML first
        if (unified != null) {
            try { return (T) unified; } catch (ClassCastException ignored) {}
        }
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
        Object u = lookupUnified(dottedPath); // F3-2: unified Luminol TOML first
        if (u instanceof Boolean ub) return ub;
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
        Object u = lookupUnified(dottedPath); // F3-2: unified Luminol TOML first
        if (u instanceof Number un) return un.intValue();
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
        Object u = lookupUnified(dottedPath); // F3-2: unified Luminol TOML first
        if (u instanceof Number un) return un.doubleValue();
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

    // SourbyCraft - Folia line (F0 re-platform) removed Slime-World-Manager, so this
    // is permanently false here. SysCommand renders it as the SWM status line.
    public static boolean swmEnabled = false;
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
    public static int maxRedstoneUpdatesPerTick = 10_000; // caps the vanilla chained neighbor-update budget (1M); 10k spares legit contraptions, 2000 broke big doors/storage tech
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
    // Per-chunk exposed-ore scan cache TTL (ticks). The scan is player-independent, so it is
    // computed once per chunk and reused for every player/send instead of re-scanning on each
    // chunk send. Block-change + chunk-unload events invalidate precisely; this TTL bounds
    // staleness from non-event changes (worldgen finalize, fluid flow). 600t = 30s.
    public static int raytraceCacheTtlTicks = 600;
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

    // SourbyCraft MT1: thread-bridge fields.
    // -1 = smart auto (chunk-workers) / use Paper default (io-workers, max-chunk-send-rate).
    public static int chunkWorkers = -1;
    public static int ioWorkers = -1;
    public static double maxChunkSendRate = -1.0;
    // asyncSpawning: MT1 async mob-spawn density state (pufferfish semantics).
    // May be forced false by PufferfishConfig.enableAsyncMobSpawning during init().
    public static boolean asyncSpawning = true;

    // DAB entity overrides: key = "minecraft:zombie", value = [maxTickFreq, activationDistMod]
    public static final java.util.Map<String, int[]> dabEntityOverrides = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Load {@code sourbycraft.yml}, run the reflective field walk, and wire the
     * Folia-available subsystems.
     *
     * <p>F2a wires only what exists on the Folia base: the reflective per-section
     * readers, the perf-knob loader ({@link dev.iyanz.sourbycraft.perf.knob.Knobs}),
     * the virtual-thread executor, the superseded-key report and the entity/
     * network/item field reads that {@link dev.iyanz.sourbycraft.perf.ConfigBridge}
     * consumes. The perf sensor bridge (F2b), combat-profile + actuator presets
     * (F2c), JVM-heap advisor, antixray ray-tracers, emoji chat translator and mod
     * loader are intentionally not called here — see the F2b/F2c/out-of-scope
     * markers below. This method is not yet invoked from the Folia boot path; it
     * exists so the registry is boot-ready when F2b wires it in.
     */
    public static void init(File configFile) {
        CONFIG_FILE = configFile;
        config = new YamlConfiguration();

        // SourbyCraft F3-2 (config unification): sourbycraft.yml is now an OPTIONAL, legacy
        // secondary source only. The single operator-facing config is the Luminol TOML
        // (sourbycraft_config/sourbycraft_global_config.toml). We load sourbycraft.yml when it
        // already exists (so an operator who migrated one from a Paper-line install is honoured
        // as an override), but we no longer materialise/require it. When absent, the in-memory
        // Bukkit config stays empty and every read falls through to the unified TOML then to the
        // hardcoded default — see the cfg*() / yml*() unified-lookup bridge.
        final boolean legacyYmlPresent = CONFIG_FILE.isFile();
        if (legacyYmlPresent) {
            try {
                config.load(CONFIG_FILE);
            } catch (IOException e) {
                Bukkit.getLogger().warning("Could not load " + configFile.getName() + ", starting with defaults: " + e.getMessage());
            } catch (InvalidConfigurationException exception) {
                Bukkit.getLogger().log(Level.SEVERE, "Could not load " + configFile.getName() + ", please correct your syntax errors", exception);
                throw new RuntimeException(exception);
            }
        }

        config.options().copyDefaults(true);
        verbose = getBoolean("verbose", false);

        version = getInt("config-version", currentVersion);
        set("config-version", currentVersion);

        // SourbyCraft F3-2: only persist the reflective field-walk back to sourbycraft.yml when a
        // legacy file already exists; otherwise run the walk in-memory only (no second file).
        readConfig(SourbyCraftConfig.class, null, legacyYmlPresent);

        // SourbyCraft F3-2: seed the perf-engine keys into the single unified Luminol config
        // BEFORE the knob/sensor loaders read them, so the operator-facing file materialises the
        // full perf surface on first boot and every subsequent read is sourced from that one file.
        try {
            seedUnifiedPerfDefaults(CONFIG_FILE);
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("seedUnifiedPerfDefaults failed; perf-engine will use hardcoded defaults", t);
        }

        // SourbyCraft - perf-engine F2c: boot-time JVM heap configuration advisory (pure log advisory).
        try {
            dev.iyanz.sourbycraft.perf.JvmHeapAdvisor.init();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("JvmHeapAdvisor.init failed", t);
        }

        // SourbyCraft - perf-engine P0: load JAR-baked perf knobs BEFORE Bukkit-config block
        dev.iyanz.sourbycraft.perf.knob.Knobs.loadFromYml();
        // SourbyCraft - perf-engine F2b: load JAR-baked sensor config right after the knobs.
        try {
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.loadFromYml();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("PerfSensor.loadFromYml failed; using defaults", t);
        }
        // SourbyCraft - perf-engine F2c: apply combat-profile preset on top of P0 defaults.
        // A profile only sets the operator-baseline knob values; SelfTuneController still owns
        // runtime tier-escalation on top of this baseline.
        try {
            String profileName = getString("combat.profile", "vanilla");
            dev.iyanz.sourbycraft.perf.CombatProfile.parse(profileName,
                dev.iyanz.sourbycraft.perf.CombatProfile.VANILLA).apply();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("CombatProfile.apply failed; using P0 defaults", t);
        }
        // out-of-scope: dev.iyanz.sourbycraft.antixray.* ray-trace toggles not ported to Folia yet.

        asyncChunkLoad = getBoolean("performance.async-chunk-load", asyncChunkLoad);
        asyncPathfinding = getBoolean("performance.async-pathfinding", asyncPathfinding);

        multithreadingEnabled = getBoolean("multithreading.enabled", multithreadingEnabled);
        virtualThreads = getBoolean("performance.virtual-threads", virtualThreads);
        structuredConcurrency = getBoolean("performance.structured-concurrency", structuredConcurrency);
        maxPlatformThreads = getInt("performance.max-platform-threads", maxPlatformThreads);
        // SourbyCraft S5: set max.bg.threads system property for Util's background executor pool.
        if (maxPlatformThreads != 4 && System.getProperty("max.bg.threads") == null) {
            System.setProperty("max.bg.threads", String.valueOf(maxPlatformThreads));
            dev.iyanz.sourbycraft.util.SourbyLogger.info(
                "[SourbyCraft] set max.bg.threads=" + maxPlatformThreads
                + " from performance.max-platform-threads (note: Util.BACKGROUND_EXECUTOR already created)");
        }

        skipEmptySections = getBoolean("memory.skip-empty-sections", skipEmptySections);
        poolEntityData = getBoolean("memory.pool-entity-data", poolEntityData);
        preSizePackets = getBoolean("memory.pre-size-packets", preSizePackets);
        chunkCompressionCache = getBoolean("memory.chunk-compression-cache", chunkCompressionCache);

        // out-of-scope: dev.iyanz.sourbycraft.chat.EmojiShortcodes chat translator not ported to Folia yet.

        // SourbyCraft - Folia line: SWM removed. swm.* keys ignored (F0 re-platform).
        // Entity stacker config (re-port era v10+)
        // S3: legacy performance.wildstacker.* keys (pre-26.1.2 operator files) seed the
        // canonical stacker.* keys when those are absent. Explicit stacker.* always wins.
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

        // SourbyCraft - create the speedtest dir the /speedtest command uses.
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of("plugins/SourbyCraft/speedtest")); } catch (java.io.IOException e) { Bukkit.getLogger().warning("Could not create speedtest directory: " + e.getMessage()); }

        // SourbyCraft ML1 — mods/ scanning and SourbyMod lifecycle are handled by
        // dev.iyanz.sourbycraft.mod.ModLoader.bootstrap() (out-of-scope: not on Folia yet).

        autoThrottleView = getBoolean("network.auto-throttle-view", autoThrottleView);
        minViewDistance = getInt("network.min-view-distance", minViewDistance);
        compressionLevel = getInt("network.compression-level", compressionLevel);
        // SourbyCraft S5: clamp and bridge compression level to Paper's live engine
        compressionLevel = Math.max(0, Math.min(9, compressionLevel));
        if (compressionLevel != 4) {
            try {
                io.papermc.paper.configuration.GlobalConfiguration.get().misc.compressionLevel =
                    new io.papermc.paper.configuration.type.number.IntOr.Default(
                        java.util.OptionalInt.of(compressionLevel));
                dev.iyanz.sourbycraft.util.SourbyLogger.info(
                    "[SourbyCraft] network.compression-level=" + compressionLevel
                    + " bridged to Paper GlobalConfiguration.misc.compressionLevel");
            } catch (Throwable t) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                    "[SourbyCraft] compression bridge failed (GlobalConfiguration not ready?): "
                    + t.getMessage());
            }
        }
        // SourbyCraft MT1: thread bridges — chunk-workers, io-workers, max-chunk-send-rate, async-spawning.
        chunkWorkers = getInt("performance.threads.chunk-workers", chunkWorkers);
        ioWorkers = getInt("performance.threads.io-workers", ioWorkers);
        maxChunkSendRate = getDouble("network.max-chunk-send-rate", maxChunkSendRate);
        asyncSpawning = getBoolean("performance.async-spawning", asyncSpawning);
        // out-of-scope: the Paper-line Pufferfish alias (force asyncSpawning false when
        // PufferfishConfig.enableAsyncMobSpawning=false) is dropped — Folia does not ship
        // Pufferfish (gg.pufferfish.pufferfish.PufferfishConfig does not exist on this base).
        // asyncSpawning keeps its yml value here.
        // Chunk-worker bridge: resize the Moonrise pool via MoonriseCommon.adjustWorkerThreads.
        String appliedWorkers;
        try {
            if (chunkWorkers > 0) {
                ca.spottedleaf.moonrise.common.util.MoonriseCommon.adjustWorkerThreads(chunkWorkers, ioWorkers);
                appliedWorkers = String.valueOf(chunkWorkers);
            } else {
                int paperWorkers = io.papermc.paper.configuration.GlobalConfiguration.get().chunkSystem.workerThreads;
                if (paperWorkers == -1) {
                    int smart = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
                    ca.spottedleaf.moonrise.common.util.MoonriseCommon.adjustWorkerThreads(smart, -1);
                    appliedWorkers = smart + " (smart-auto)";
                } else {
                    appliedWorkers = "auto skipped (Paper explicit workerThreads=" + paperWorkers + ")";
                }
            }
        } catch (Throwable t) {
            appliedWorkers = "bridge failed";
            dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                "[SourbyCraft] MT1 chunk-worker bridge failed (pool resize unsafe or not ready): " + t.getMessage());
        }
        // max-chunk-send-rate bridge: override Paper's playerMaxChunkSendRate.
        if (maxChunkSendRate > 0.0) {
            try {
                io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkSendRate = maxChunkSendRate;
                dev.iyanz.sourbycraft.util.SourbyLogger.info(
                    "[SourbyCraft] MT1 network.max-chunk-send-rate=" + maxChunkSendRate
                    + " bridged to Paper GlobalConfiguration.chunkLoadingBasic.playerMaxChunkSendRate");
            } catch (Throwable t) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                    "[SourbyCraft] MT1 max-chunk-send-rate bridge failed: " + t.getMessage());
            }
        }
        dev.iyanz.sourbycraft.util.SourbyLogger.info(
            "[SourbyCraft] threads: chunk-workers=" + appliedWorkers
            + " io=" + ioWorkers
            + " send-rate=" + maxChunkSendRate
            + " async-spawning=" + asyncSpawning);

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
        maxRedstoneUpdatesPerTick = getInt("entity.max-redstone-updates-per-tick", maxRedstoneUpdatesPerTick);
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
        if (itemPoolEnabled) {
            Bukkit.getLogger().warning("[SourbyCraft] item.pool-enabled: true but the ItemEntityPool engine is offline "
                + "(removed for the levitation bug; keys reserved for pool v2). No pooling occurs.");
        }
        itemMaxPerChunk = getInt("item.max-per-chunk", itemMaxPerChunk);
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

        // SourbyCraft S6 - pvp.* fossil notice: the PvP variant was removed in the 26.1.2
        // migration; operator files may still carry the section. Keys are never deleted.
        if (config.contains("pvp")) {
            Bukkit.getLogger().info("[SourbyCraft] pvp.* keys in sourbycraft.yml are from the removed PvP variant "
                + "and are ignored; combat tuning now lives in combat.profile (vanilla|balanced|pvp).");
        }

        // idle-timeout is applied by dev.iyanz.sourbycraft.perf.ConfigBridge at plugin enable (S2).

        // SourbyCraft - perf-engine F2b: operator sourbycraft.yml bridge for sensor settings.
        // Uses cfg*() (config.get(), no addDefault) so the operator yml is NOT polluted with
        // sensor keys on first boot. Only keys explicitly present in the operator yml override
        // the JAR-baked defaults loaded by PerfSensor.loadFromYml() above.
        try {
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.applyOperatorConfig(
                cfgBool("perf.sensor.enabled", true),
                cfgInt("perf.sensor.warmup-ticks", 600),
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

        // SourbyCraft S5: superseded-keys audit — one INFO line + per-key WARN when non-default
        try {
            dev.iyanz.sourbycraft.perf.SupersededKeys.report(config);
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("SupersededKeys.report failed", t);
        }

        // SourbyCraft F3-2: only re-materialise sourbycraft.yml when the operator already has one
        // (legacy/migrated file). We do NOT create a second mandatory config file — the single
        // operator surface is the unified Luminol TOML.
        if (legacyYmlPresent) {
            try {
                config.save(CONFIG_FILE);
            } catch (IOException exception) {
                Bukkit.getLogger().log(Level.SEVERE, "Could not save final " + CONFIG_FILE, exception);
            }
        }
    }

    /**
     * F3-2 config unification — seed the perf-engine's operator keys into the single Luminol
     * config file ({@code sourbycraft_config/sourbycraft_global_config.toml}) so the operator
     * edits ONE file for everything. Only writes a key when it is absent (never clobbers an
     * operator edit), attaches a one-line comment, and saves the unified file once at the end.
     *
     * <p>This is the write-side complement to the read-side bridge in {@code ymlGet}/{@code cfg*}:
     * the perf-engine keeps its exact dotted keys and its knob/sensor logic untouched; only the
     * SOURCE of those values is now the one unified file. Luminol's own 60 {@code @ConfigInfo}
     * modules are not touched — this method never writes under their category keys
     * (optimizations/fixes/misc/function/experiment), only under the SourbyCraft-owned
     * {@code perf.*}, {@code spark.*} and {@code branding.*} trees.
     *
     * <p>Extensible: a future SourbyCraft config surface = one more {@code seed(...)} line here
     * (or a full Luminol {@code @ConfigInfo} module), no new file.
     *
     * <p>The {@code File} parameter is unused but deliberate: it keeps this method OUT of the
     * reflective no-arg field-walk in {@link #readConfig(Class, Object, boolean)} (which invokes
     * every private zero-arg void method) so seeding runs exactly once, from {@link #init(File)}.
     */
    private static void seedUnifiedPerfDefaults(File unusedMarker) {
        Object fileObj = unifiedFile();
        if (fileObj == null) {
            // Luminol config not resolved (e.g. init() called outside a booted server). The
            // perf-engine still works off its hardcoded defaults; nothing to seed.
            return;
        }
        com.electronwill.nightconfig.core.file.CommentedFileConfig f =
            (com.electronwill.nightconfig.core.file.CommentedFileConfig) fileObj;
        boolean[] changed = {false};

        // Section header comment on the perf tree (only if the tree is empty of a comment).
        if (f.getComment("perf") == null) {
            f.setComment("perf", "SourbyCraft self-tuning performance engine (knobs, sensor, AI + lag-machine caps).");
        }

        // --- Knobs (dev.iyanz.sourbycraft.perf.knob.Knobs) ---
        seed(f, changed, "perf.entity-tick-rate", 20, "Skip-rate for entity ticking. 1 = every tick (vanilla), 20 = once/sec.");
        seed(f, changed, "perf.ai.throttle-beyond-distance", 0, "Distance (blocks) past nearest player to throttle mob AI. 0 = disabled.");
        seed(f, changed, "perf.ai.throttle-tick-interval", 4, "When AI is throttled, run aiStep only every N ticks.");
        seed(f, changed, "perf.lag-machine.disable-saving-snowballs", true, "Skip NBT save for snowballs (known lag-machine vector).");
        seed(f, changed, "perf.lag-machine.disable-saving-fireworks", true, "Skip NBT save for firework rockets.");
        seed(f, changed, "perf.lag-machine.max-projectile-loads-per-tick", 10, "Max projectile-triggered chunk loads per tick. 0 = unlimited.");
        seed(f, changed, "perf.lag-machine.max-projectile-loads-per-projectile", 10, "Max chunk loads a single projectile may trigger. 0 = unlimited.");
        seed(f, changed, "perf.lag-machine.remove-excess-minecarts", false, "Remove excess minecarts on collision.");
        seed(f, changed, "perf.lag-machine.excess-minecarts-limit", 10, "Threshold for excess minecarts at a collision point.");
        seed(f, changed, "perf.lag-machine.remove-excess-boats", false, "Remove excess boats on collision.");
        seed(f, changed, "perf.lag-machine.excess-boats-limit", 10, "Threshold for excess boats at a collision point.");

        // --- Sensor (dev.iyanz.sourbycraft.perf.sensor.PerfSensor) ---
        seed(f, changed, "perf.sensor.enabled", true, "Master switch for the multi-signal load sensor + self-tune loop.");
        seed(f, changed, "perf.sensor.warmup-ticks", 600, "Ticks to skip at boot before sampling (avoids false escalation).");
        seed(f, changed, "perf.sensor.cadence-ticks", 20, "Sample cadence in server ticks (20 = 1s at 20 TPS).");
        seed(f, changed, "perf.sensor.dwell-samples", 3, "Consecutive samples in a worse band before escalating a tier.");
        seed(f, changed, "perf.sensor.recovery-dwell-multiplier", 2.0, "Dwell multiplier when recovering to a better tier (>= 1.0).");
        // Thresholds (indexed yellow/orange/red/emergency).
        seedThresholds(f, changed, "mspt", 30.0, 40.0, 60.0, 100.0, "MSPT tier thresholds (higher = worse).");
        seedThresholds(f, changed, "tps", 19.5, 18.0, 15.0, 10.0, "TPS tier thresholds (lower = worse).");
        seedThresholds(f, changed, "mem", 75.0, 85.0, 92.0, 97.0, "Heap % tier thresholds (higher = worse).");
        seedThresholds(f, changed, "gc-ms-per-min", 20.0, 50.0, 100.0, 300.0, "GC pause ms/min tier thresholds (higher = worse).");

        // --- Spark bridge + GC advisor ---
        seed(f, changed, "spark.enabled", true, "Enable the bundled spark profiler bridge.");
        seed(f, changed, "branding.gc-advisor.enabled", true, "Enable the startup GC/JVM-flags advisory log.");

        // --- Max players (F1-6) ---
        // Server max-player slot count set by /maxp. Seeded as 0 (= use server.properties) so the
        // key is discoverable; MaxPlayersConfig.applyAtBoot only re-applies when it is > 0, i.e.
        // after an operator runs /maxp. Written under the SourbyCraft-owned "sourbycraft.*" tree.
        seed(f, changed, dev.iyanz.sourbycraft.perf.MaxPlayersConfig.KEY, 0,
            "Server max-player slot count set by /maxp. Re-applied at boot so it wins over server.properties. 0 = use server.properties.");

        if (changed[0]) {
            try {
                f.save();
                dev.iyanz.sourbycraft.util.SourbyLogger.info(
                    "[SourbyCraft] seeded perf-engine keys into unified config (sourbycraft_config/sourbycraft_global_config.toml)");
            } catch (Throwable t) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("[SourbyCraft] could not save unified config after seeding perf keys: " + t.getMessage());
            }
        }
    }

    private static void seed(com.electronwill.nightconfig.core.file.CommentedFileConfig f,
                             boolean[] changed, String path, Object def, String comment) {
        if (!f.contains(path)) {
            f.add(path, def);
            if (comment != null) f.setComment(path, comment);
            changed[0] = true;
        }
    }

    private static void seedThresholds(com.electronwill.nightconfig.core.file.CommentedFileConfig f,
                                       boolean[] changed, String signal,
                                       double yellow, double orange, double red, double emergency, String comment) {
        String base = "perf.sensor.thresholds." + signal;
        seed(f, changed, base + ".yellow", yellow, comment);
        seed(f, changed, base + ".orange", orange, null);
        seed(f, changed, base + ".red", red, null);
        seed(f, changed, base + ".emergency", emergency, null);
    }

    protected static void log(String string) {
        if (verbose) log(Level.INFO, string);
    }

    protected static void log(Level level, String string) {
        Bukkit.getLogger().log(level, string);
    }

    public static void readConfig(Class<?> clazz, Object instance) {
        readConfig(clazz, instance, true);
    }

    /** @param saveAfter false for hot-path lazy loads (per-world config on chunk send) — no main-thread disk write. */
    public static void readConfig(Class<?> clazz, Object instance, boolean saveAfter) {
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

        if (!saveAfter) return;
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
     * <p>We use {@code config.set} when the key is absent so every default the
     * boot path consults lands on disk on the next save (YamlConfiguration's
     * default-merge path does not serialise defaults that were never read).
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
     * written to the operator yml when absent. Used for perf-engine knob bridges to
     * avoid polluting the operator yml with keys on first boot.
     */
    private static boolean cfgBool(String path, boolean def) {
        Object v = config.get(path);
        if (v instanceof Boolean b) return b;
        // F3-2: unified Luminol TOML is the operator surface — consult it when the (legacy,
        // usually absent) sourbycraft.yml has no value for this key.
        Object u = lookupUnified(path);
        if (u instanceof Boolean ub) return ub;
        return def;
    }

    private static int cfgInt(String path, int def) {
        Object v = config.get(path);
        if (v instanceof Number n) return n.intValue();
        Object u = lookupUnified(path); // F3-2: unified Luminol TOML fallback
        if (u instanceof Number un) return un.intValue();
        return def;
    }

    private static double cfgDouble(String path, double def) {
        Object v = config.get(path);
        if (v instanceof Number n) return n.doubleValue();
        Object u = lookupUnified(path); // F3-2: unified Luminol TOML fallback
        if (u instanceof Number un) return un.doubleValue();
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
        // any parser failure and returns the input as plain text.
        // out-of-scope: dev.iyanz.sourbycraft.util.TextRender not ported to Folia yet.
        // Fall back to MiniMessage's own strict parse until TextRender lands.
        String raw = getString(path, MiniMessage.miniMessage().serialize(def));
        try {
            return MiniMessage.miniMessage().deserialize(raw);
        } catch (Exception ex) {
            return def;
        }
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
