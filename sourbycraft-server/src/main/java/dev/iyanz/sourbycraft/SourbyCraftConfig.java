package dev.iyanz.sourbycraft;

import java.io.File;

/**
 * SourbyCraft config registry (Folia base) — single-file TOML.
 *
 * <p>Every operator-facing SourbyCraft setting lives in ONE file: the unified Luminol
 * config ({@code sourbycraft_config/sourbycraft_global_config.toml}). The perf-engine
 * (knobs, sensor, spark, gc-advisor), the varied-message layer, the auto-updater, the
 * ViaVersion auto-provisioner, {@code /maxp} and the actuator layer all read their keys
 * through the typed {@link #cfgBool}/{@link #cfgInt}/{@link #cfgDouble}/{@link #cfgGet}/
 * {@link #cfgStringList} accessors below, which resolve the dotted path against that one
 * TOML (nightconfig treats {@code .} as a path separator, so {@code perf.sensor.cadence-ticks}
 * resolves directly). When a key is absent the supplied hardcoded default is returned.
 *
 * <p>{@link #init(File)} seeds the perf/message/via/maxp defaults into the TOML on first boot
 * (never clobbering an operator edit), then wires the Folia-available subsystems: the JVM-heap
 * advisor, the perf knobs + sensor config, the combat-profile preset and the entity/network/item
 * master fields that {@link dev.iyanz.sourbycraft.perf.ConfigBridge} pushes into the per-world
 * Spigot/Paper config engines. The Luminol config instance is fully loaded before this runs, so
 * every lookup is populated at read time.
 */
public class SourbyCraftConfig {

    // --- Unified Luminol TOML resolution (resolved lazily so this class carries no hard
    // compile/init dependency on the me.earthme.luminol config package init order). Cached once. ---
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
            // Luminol config not available (e.g. unit-test context) — every read falls to its default.
        }
        return null;
    }

    /**
     * Look up a dotted path in the unified Luminol TOML. Returns {@code null} when the config
     * instance is not yet available or the key is absent.
     */
    private static Object lookup(String dottedPath) {
        Object file = unifiedFile();
        if (file == null) return null;
        try {
            return ((com.electronwill.nightconfig.core.file.CommentedFileConfig) file).get(dottedPath);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Read a value from the unified TOML by dotted path. Returns {@code defaultValue} when the
     * key is missing or cannot be cast to the requested type.
     */
    @SuppressWarnings("unchecked")
    public static <T> T cfgGet(String dottedPath, T defaultValue) {
        Object v = lookup(dottedPath);
        if (v != null) {
            // The unchecked cast is erased — a CCE would surface at the CALL SITE, not here, so
            // catching it locally was dead code. Check assignability against the default instead
            // (mirrors cfgBool/cfgInt) so a mistyped key degrades to the default with one WARN.
            if (defaultValue == null || defaultValue.getClass().isInstance(v)) {
                return (T) v;
            }
            warnOnce(dottedPath, v, defaultValue.getClass().getSimpleName());
        }
        return defaultValue;
    }

    /** Type-safe boolean read from the unified TOML. */
    public static boolean cfgBool(String dottedPath, boolean defaultValue) {
        Object v = lookup(dottedPath);
        if (v instanceof Boolean b) return b;
        if (v != null) warnOnce(dottedPath, v, "boolean");
        return defaultValue;
    }

    /** Type-safe int read from the unified TOML (any {@link Number} coerced via {@code intValue()}). */
    public static int cfgInt(String dottedPath, int defaultValue) {
        Object v = lookup(dottedPath);
        if (v instanceof Number n) return n.intValue();
        if (v != null) warnOnce(dottedPath, v, "int");
        return defaultValue;
    }

    /** Type-safe double read from the unified TOML (any {@link Number} coerced via {@code doubleValue()}). */
    public static double cfgDouble(String dottedPath, double defaultValue) {
        Object v = lookup(dottedPath);
        if (v instanceof Number n) return n.doubleValue();
        if (v != null) warnOnce(dottedPath, v, "double");
        return defaultValue;
    }

    /**
     * Reads a list of strings from the unified TOML. Returns an empty list when the key is
     * missing or not a List. Non-string entries are filtered out with one WARN per position.
     */
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

    /** Combat profile applied this boot — decides whether the lag-machine TOML bridge runs. */
    private static volatile dev.iyanz.sourbycraft.perf.CombatProfile activeCombatProfile =
        dev.iyanz.sourbycraft.perf.CombatProfile.VANILLA;

    // --- Live config fields (hardcoded defaults; operator-overridable via the unified TOML). ---

    // MT1 thread bridges. -1 = smart auto (chunk-workers) / use Paper default (io-workers, send-rate).
    public static int maxPlatformThreads = 4;

    // SourbyCraft — built-in ViaVersion/ViaBackwards auto-provisioning (default ON). When true,
    // the server downloads + SHA-256-verifies the pinned ViaVersion + ViaBackwards jars into
    // plugins/ on first boot so old clients (>=1.20) can join the native 26.2/1.21.9 server.
    //
    // Note (2026-07-12): the "native 1.21.9 join stall" once blamed on ViaVersion was actually the
    // EDF region-scheduler starvation bug in vendored Metal (fixed in EDFSchedulerThreadPool) — Via
    // was never the cause, so the default is back to ON. Operators who manage Via themselves (or
    // run without it) opt out with viaversion.auto-provision=false; the next boot then quarantines
    // the auto-provisioned jars to *.jar.disabled (operator-installed Via jars are never touched).
    public static boolean viaVersionAutoProvision = true;

    public static boolean autoThrottleView = true;
    public static int minViewDistance = 4;
    public static int compressionLevel = 4;
    public static int entityTickRate() {
        return dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.get();
    }
    public static boolean hopperBatch = true;
    public static boolean redstoneOptimize = true;
    public static int maxEntityPerChunk = 10;
    public static int maxSpecialsPerChunk = 15;
    public static int maxArrowsPerWorld = 5000;
    public static int idleTimeout = 0;
    public static boolean itemMergeOptimize = true;
    public static int itemDespawnRate = 6000;
    // SourbyCraft v9.13 — was 1; raised to 3 so a player dropping a full inventory of dirt
    // (items scattered ~1-2 blocks apart) reliably merges into one entity.
    public static int itemMergeRadius = 3;
    public static boolean ownerProtectionEnabled = true;
    public static int ownerProtectionTime = 3; // seconds a dropped item is locked to the dropper (others wait this long)
    /** Pickup delay (ticks) applied to a player-dropped item — how long before ANYONE may collect it.
     *  Vanilla is 40 (2s). Raise it to stop instant re-grabs in PvP; 0 = pickup immediately. */
    public static int itemPickupDelayTicks = 40;
    public static int itemMaxPerChunk = 64;
    public static int mobTickDistance = 32;
    public static int mobPathfindInterval = 20;

    // MT1 thread bridges.
    public static int chunkWorkers = -1;
    public static int ioWorkers = -1;
    public static double maxChunkSendRate = -1.0;
    // asyncSpawning: MT1 async mob-spawn density state (pufferfish semantics).
    public static boolean asyncSpawning = true;

    // --- Anti-xray raytrace ore/liquid reveal (Folia port of the pre-folia core). Global master
    // knobs read once at boot from the unified TOML antixray.* section; per-world fluid-obscures /
    // all-blocks live in SourbyCraftWorldConfig. RayTraceWorker.ENABLED mirrors antixray.enabled. ---
    /** Master enable for the ore/liquid raytrace reveal layer. Seeds RayTraceWorker.ENABLED. */
    public static boolean antixrayEnabled = true;
    /** Global master for fluid-obscures; ANDed with the per-world SourbyCraftWorldConfig.fluidObscures. */
    public static boolean fluidObscures = true;
    /** Also hide cave-exposed LIQUID (water/lava) blocks, not just ores. SourbyEngine re-hides them
     *  dynamically on loss of sight, so the old persistent-reveal flicker is gone. Default ON. */
    public static boolean hideLiquids = true;
    /** Hide cave-exposed BLOCK-ENTITIES (chests, barrels, shulker boxes, spawners, vaults, …) via the
     *  SourbyEngine block-update path. Wurst/xray reads the client's block-entity list, so a chest sent
     *  real leaks even behind wall; the stone block-update SourbyEngine sends removes the client-side
     *  block-entity, and the reveal re-creates it on line of sight. Never routed through Paper's palette
     *  obfuscation (that leaves the block-entity in the chunk packet → the old "chest berubah" glitch). */
    public static boolean hideBlockEntities = true;
    /** Anti-ESP: hide occluded mobs / item drops (holograms exempt). Seeds EntityVisibilityCheck.ENABLED. */
    public static boolean hideEntities = true;
    /** Anti-ESP: drop particle packets the receiver has no line-of-sight to. Seeds ParticleVisibilityCheck.ENABLED. */
    public static boolean hideParticles = true;
    /** tickCycle cadence in server ticks (reveal-confirmed + submit near-pending raytraces). */
    public static int raytraceIntervalTicks = 2;
    /** Max distance (blocks) a player eye may be from an ore for a raytrace to be submitted. */
    public static double raytraceDistance = 64.0;
    /** Per tickCycle, the max number of async raytrace checks submitted per player. */
    public static int raytraceMaxChecksPerCycle = 10;
    /** Per player, the max number of hidden-and-pending ore positions held at once (budget). */
    public static int raytraceMaxPendingPerPlayer = 512;
    /** TTL (ticks) for the per-chunk exposed-ore scan cache before a non-event re-scan. */
    public static int raytraceCacheTtlTicks = 100;

    /**
     * Seed the SourbyCraft TOML defaults, wire the Folia-available subsystems, and read the live
     * config fields from the unified TOML.
     *
     * <p>Invoked once from the authored post-config hook via
     * {@link dev.iyanz.sourbycraft.perf.PerfEngineBootstrap#start()}.
     */
    public static void init(File configFile) {
        // Seed the perf-engine / message / via / maxp keys into the single unified TOML BEFORE the
        // knob/sensor loaders read them, so the operator-facing file materialises the full surface
        // on first boot and every subsequent read is sourced from that one file.
        try {
            seedUnifiedPerfDefaults(configFile);
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("seedUnifiedPerfDefaults failed; perf-engine will use hardcoded defaults", t);
        }

        // Proxy forwarding (F-proxy): resolve proxy.mode, apply it to Paper's native forwarding
        // settings (Folia-safe — init() runs from CommandRegister.register, strictly BEFORE the
        // network binds), validate, and log the active mode as one hex line. Must run before the
        // TCP listener so SpigotConfig.bungee / proxies.velocity are authoritative pre-handshake.
        try {
            dev.iyanz.sourbycraft.security.ProxyForwarding.run();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("ProxyForwarding.run failed; native proxy config left untouched", t);
        }

        // Boot-time JVM heap configuration advisory (pure log advisory).
        try {
            dev.iyanz.sourbycraft.perf.JvmHeapAdvisor.init();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("JvmHeapAdvisor.init failed", t);
        }

        // Re-usable live-apply (also driven by /sourbycraft reload). Boot-only steps (seed,
        // proxy forwarding, heap advisor) stay in init() above and are NOT re-run on reload.
        applyLiveConfig();
    }

    /**
     * Apply every operator-tunable value from the (already-parsed) unified TOML to the live engine.
     * Called from {@link #init(File)} at boot and from {@link #reload()} on {@code /sourbycraft
     * reload}. Safe to re-run: each line re-reads a key and re-sets a field / knob / toggle.
     */
    private static void applyLiveConfig() {
        // Load JAR-baked perf knobs, then the sensor config, then the combat-profile preset.
        dev.iyanz.sourbycraft.perf.knob.Knobs.loadFromYml();
        try {
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.loadFromYml();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("PerfSensor.loadFromYml failed; using defaults", t);
        }
        dev.iyanz.sourbycraft.perf.CombatProfile appliedProfile = dev.iyanz.sourbycraft.perf.CombatProfile.VANILLA;
        try {
            String profileName = cfgGet("combat.profile", "vanilla");
            appliedProfile = dev.iyanz.sourbycraft.perf.CombatProfile.parse(profileName,
                dev.iyanz.sourbycraft.perf.CombatProfile.VANILLA);
            appliedProfile.apply();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("CombatProfile.apply failed; using defaults", t);
        }
        activeCombatProfile = appliedProfile;

        maxPlatformThreads = cfgInt("performance.max-platform-threads", maxPlatformThreads);
        // S5: set max.bg.threads system property for Util's background executor pool.
        if (maxPlatformThreads != 4 && System.getProperty("max.bg.threads") == null) {
            System.setProperty("max.bg.threads", String.valueOf(maxPlatformThreads));
        }

        // Via auto-provision toggle (unified TOML; default ON — bridges clients down to 1.20.
        // Operator opts out with false; the provisioner then quarantines our jars next boot).
        viaVersionAutoProvision = cfgBool("viaversion.auto-provision", viaVersionAutoProvision);

        autoThrottleView = cfgBool("network.auto-throttle-view", autoThrottleView);
        minViewDistance = cfgInt("network.min-view-distance", minViewDistance);
        compressionLevel = clamp(cfgInt("network.compression-level", compressionLevel), 0, 9);
        // S5: bridge compression level to Paper's live engine when non-default.
        if (compressionLevel != 4) {
            try {
                io.papermc.paper.configuration.GlobalConfiguration.get().misc.compressionLevel =
                    new io.papermc.paper.configuration.type.number.IntOr.Default(
                        java.util.OptionalInt.of(compressionLevel));
            } catch (Throwable t) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                    "compression bridge failed (GlobalConfiguration not ready?): " + t.getMessage());
            }
        }

        // MT1 thread bridges — chunk-workers, io-workers, max-chunk-send-rate, async-spawning.
        chunkWorkers = cfgInt("performance.threads.chunk-workers", chunkWorkers);
        ioWorkers = cfgInt("performance.threads.io-workers", ioWorkers);
        maxChunkSendRate = cfgDouble("network.max-chunk-send-rate", maxChunkSendRate);
        asyncSpawning = cfgBool("performance.async-spawning", asyncSpawning);
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
                    appliedWorkers = smart + " (auto)";
                } else {
                    appliedWorkers = "auto skipped (Paper workerThreads=" + paperWorkers + ")";
                }
            }
        } catch (Throwable t) {
            appliedWorkers = "bridge failed";
            dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                "chunk-worker bridge failed (pool resize unsafe or not ready): " + t.getMessage());
        }
        // max-chunk-send-rate bridge: override Paper's playerMaxChunkSendRate.
        if (maxChunkSendRate > 0.0) {
            try {
                io.papermc.paper.configuration.GlobalConfiguration.get().chunkLoadingBasic.playerMaxChunkSendRate = maxChunkSendRate;
            } catch (Throwable t) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                    "max-chunk-send-rate bridge failed: " + t.getMessage());
            }
        }
        dev.iyanz.sourbycraft.util.SourbyLogger.info(
            "threads: chunk-workers=" + appliedWorkers + " io=" + ioWorkers
            + " send-rate=" + maxChunkSendRate + " async-spawning=" + asyncSpawning);

        // Anti-xray raytrace ore/liquid reveal (Folia core). Read the global master knobs and mirror
        // the enable flag into RayTraceWorker.ENABLED (the single volatile the hot path reads). The
        // per-world fluid-obscures / all-blocks are read lazily in SourbyCraftWorldConfig.
        antixrayEnabled = cfgBool("antixray.enabled", antixrayEnabled);
        fluidObscures = cfgBool("antixray.fluid-obscures", fluidObscures);
        hideLiquids = cfgBool("antixray.hide-liquids", hideLiquids);
        hideBlockEntities = cfgBool("antixray.hide-block-entities", hideBlockEntities);
        hideEntities = cfgBool("antixray.hide-entities", hideEntities);
        hideParticles = cfgBool("antixray.hide-particles", hideParticles);
        raytraceIntervalTicks = Math.max(1, cfgInt("antixray.raytrace.interval-ticks", raytraceIntervalTicks));
        raytraceDistance = Math.max(1.0, cfgDouble("antixray.raytrace.distance", raytraceDistance));
        raytraceMaxChecksPerCycle = Math.max(0, cfgInt("antixray.raytrace.max-checks-per-cycle", raytraceMaxChecksPerCycle));
        raytraceMaxPendingPerPlayer = Math.max(0, cfgInt("antixray.raytrace.max-pending-per-player", raytraceMaxPendingPerPlayer));
        raytraceCacheTtlTicks = Math.max(1, cfgInt("antixray.raytrace.cache-ttl-ticks", raytraceCacheTtlTicks));
        dev.iyanz.sourbycraft.antixray.RayTraceWorker.ENABLED.set(antixrayEnabled);
        // Anti-ESP sub-layers: entity/hologram + particle occlusion. ANDed with the master so a disabled
        // engine can never leave a sub-check live. Each is the single volatile its hot packet path reads.
        dev.iyanz.sourbycraft.antixray.EntityVisibilityCheck.ENABLED.set(antixrayEnabled && hideEntities);
        dev.iyanz.sourbycraft.antixray.ParticleVisibilityCheck.ENABLED.set(antixrayEnabled && hideParticles);

        // Baritone / anti-raid defense: seed Paper's built-in anti-xray to engine-mode 1 (HIDE —
        // lightweight) with the ore + base-indicator hidden set. Runs HERE (post-config hook, before
        // DedicatedServer#loadLevel builds each world's chunkPacketBlockController from
        // config/paper-world-defaults.yml) so every world comes up already defended. Idempotent +
        // gated on antixray.baritone-defense; wrapped so a config-write failure never aborts boot.
        try {
            dev.iyanz.sourbycraft.antixray.PaperAntiXrayDefense.apply();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("PaperAntiXrayDefense.apply failed; Paper anti-xray left as-is", t);
        }

        dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.set(
            cfgInt("perf.entity-tick-rate", dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.get())
        );
        hopperBatch = cfgBool("entity.hopper-batch", hopperBatch);
        redstoneOptimize = cfgBool("entity.redstone-optimize", redstoneOptimize);
        maxEntityPerChunk = cfgInt("entity.max-per-chunk", maxEntityPerChunk);
        maxSpecialsPerChunk = cfgInt("entity.max-specials-per-chunk", maxSpecialsPerChunk);
        maxArrowsPerWorld = cfgInt("entity.max-arrows-per-world", maxArrowsPerWorld);
        idleTimeout = cfgInt("server.idle-timeout", idleTimeout);
        itemMergeOptimize = cfgBool("entity.item-merge-optimize", itemMergeOptimize);
        itemDespawnRate = cfgInt("entity.item-despawn-rate", itemDespawnRate);
        itemMergeRadius = cfgInt("entity.item-merge-radius", itemMergeRadius);
        ownerProtectionEnabled = cfgBool("item.owner-protection-enabled", ownerProtectionEnabled);
        ownerProtectionTime = Math.min(cfgInt("item.owner-protection-time", ownerProtectionTime), 1638);
        itemPickupDelayTicks = Math.max(0, Math.min(cfgInt("item.pickup-delay-ticks", itemPickupDelayTicks), 32766));
        itemMaxPerChunk = cfgInt("item.max-per-chunk", itemMaxPerChunk);
        mobTickDistance = cfgInt("entity.mob-tick-distance", mobTickDistance);
        mobPathfindInterval = cfgInt("entity.mob-pathfind-interval", mobPathfindInterval);

        // Load-gated knob operator bridge (entity-limiter + goal-selector). Their GREEN/YELLOW
        // baseline must equal the operator's chosen base default so the perf-engine never causes a
        // regression at rest; SelfTuneController captures whatever is set here as the restore-baseline.
        try {
            boolean opEntityLimiter = cfgBool("perf.entity-limiter.enabled",
                dev.kaiijumc.kaiiju.KaiijuEntityLimits.enabled);
            dev.iyanz.sourbycraft.perf.knob.Knobs.KAIIJU_ENTITY_LIMITER_ENABLED.set(opEntityLimiter);
            dev.iyanz.sourbycraft.perf.knob.Knobs.GOAL_SELECTOR_INACTIVE_TICK_ENABLED.set(
                cfgBool("perf.ai.goal-selector-inactive-throttle",
                    me.earthme.luminol.config.modules.optimizations.EntityGoalSelectorInactiveTickConfig.enabled));
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("load-gated knob operator bridge failed; using knob defaults", t);
        }

        // Sensor operator bridge (thresholds + cadence).
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
                // TPS ladder: idle/healthy (~20 TPS) stays GREEN; RED/EMERGENCY require TPS below the
                // operator's 17-TPS self-tune floor. Keep in sync with PerfSensor.tpsThresholds.
                cfgDouble("perf.sensor.thresholds.tps.yellow",    17.0),
                cfgDouble("perf.sensor.thresholds.tps.orange",    15.0),
                cfgDouble("perf.sensor.thresholds.tps.red",       13.0),
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
            dev.iyanz.sourbycraft.util.SourbyLogger.error("PerfSensor.applyOperatorConfig failed; using defaults", t);
        }

        // Lag-machine knob operator bridge. Layering rule: when a NON-vanilla combat profile is
        // active, the PROFILE owns these eight knobs — the seeded TOML keys always exist, so an
        // unconditional bridge would silently clobber the profile back to the seeded defaults
        // every boot (profile "half-dead"). Operators on a profile tune via the profile; operators
        // on vanilla tune via the perf.lag-machine.* keys.
        if (activeCombatProfile != dev.iyanz.sourbycraft.perf.CombatProfile.VANILLA) {
            dev.iyanz.sourbycraft.util.SourbyLogger.info("combat profile " + activeCombatProfile
                + " owns perf.lag-machine.* — TOML lag-machine keys not bridged this boot");
        } else try {
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
            dev.iyanz.sourbycraft.util.SourbyLogger.error("lag-machine operator-config bridge failed; using defaults", t);
        }

        // Log the final knob values AFTER all operator overrides, then start the virtual executor.
        dev.iyanz.sourbycraft.perf.knob.Knobs.logLoaded();
        dev.iyanz.sourbycraft.util.VirtualExecutor.init();
    }

    /**
     * Re-read the unified TOML from disk and re-apply it live ({@code /sourbycraft reload}). Returns
     * a short human summary. Boot-only steps (config seeding, proxy forwarding, JVM heap advisor,
     * command registration) are NOT re-run. A few settings only fully take effect on restart —
     * Paper anti-xray engine-mode (worlds already built their controller) and thread-pool sizes —
     * so the caller should surface that caveat.
     */
    public static synchronized String reload() {
        Object fileObj = unifiedFile();
        if (fileObj instanceof com.electronwill.nightconfig.core.file.CommentedFileConfig f) {
            try {
                f.load(); // re-parse sourbycraft_config/sourbycraft_global_config.toml from disk
            } catch (Throwable t) {
                return "reload FAILED: could not re-read the config file: " + t.getMessage();
            }
        } else {
            return "reload FAILED: unified config not available";
        }
        try {
            applyLiveConfig();
        } catch (Throwable t) {
            dev.iyanz.sourbycraft.util.SourbyLogger.error("config reload apply failed", t);
            return "reload FAILED during apply: " + t.getMessage();
        }
        dev.iyanz.sourbycraft.util.SourbyLogger.info("config reloaded from disk (/sourbycraft reload)");
        return "reloaded — perf knobs, sensor, anti-xray toggles, lag limits + caps applied live. "
            + "Paper anti-xray engine-mode and thread-pool sizes need a restart to fully change.";
    }


    /**
     * Seed the SourbyCraft-owned keys into the single unified TOML so the operator edits ONE file
     * for everything. Only writes a key when absent (never clobbers an operator edit), attaches a
     * one-line comment, and saves once at the end. Luminol's own {@code @ConfigInfo} modules are
     * never touched — this only writes under the SourbyCraft-owned {@code perf.*}, {@code spark.*},
     * {@code branding.*}, {@code messages.*}, {@code viaversion.*} and {@code sourbycraft.*} trees.
     */
    private static void seedUnifiedPerfDefaults(File unusedMarker) {
        Object fileObj = unifiedFile();
        if (fileObj == null) return; // config not resolved (e.g. init() outside a booted server)
        com.electronwill.nightconfig.core.file.CommentedFileConfig f =
            (com.electronwill.nightconfig.core.file.CommentedFileConfig) fileObj;
        boolean[] changed = {false};

        if (f.getComment("perf") == null) {
            f.setComment("perf", "SourbyCraft self-tuning performance engine (knobs, sensor, AI + lag-machine caps).");
        }

        // --- Knobs ---
        seed(f, changed, "perf.entity-tick-rate", 20, "Skip-rate for entity ticking. 1 = every tick (vanilla), 20 = once/sec.");
        seed(f, changed, "perf.ai.throttle-beyond-distance", 0, "Distance (blocks) past nearest player to throttle mob AI. 0 = disabled.");
        seed(f, changed, "perf.ai.throttle-tick-interval", 4, "When AI is throttled, run aiStep only every N ticks.");
        seed(f, changed, "perf.ai.goal-selector-inactive-throttle", true, "Throttle the AI goal-selector to 1-in-20 ticks for inactive mobs (behaviour-neutral). Perf-engine also forces this on under load.");
        seed(f, changed, "perf.entity-limiter.enabled", false, "Master gate for the per-region per-entity-type tick/removal limiter (sourby_entity_limits.yml). Off = no throttling; the perf-engine turns it on under load.");
        seed(f, changed, "perf.lag-machine.disable-saving-snowballs", true, "Skip NBT save for snowballs (known lag-machine vector).");
        seed(f, changed, "perf.lag-machine.disable-saving-fireworks", true, "Skip NBT save for firework rockets.");
        seed(f, changed, "perf.lag-machine.max-projectile-loads-per-tick", 10, "Max projectile-triggered chunk loads per tick. 0 = unlimited.");
        seed(f, changed, "perf.lag-machine.max-projectile-loads-per-projectile", 10, "Max chunk loads a single projectile may trigger. 0 = unlimited.");
        seed(f, changed, "perf.lag-machine.remove-excess-minecarts", false, "Remove excess minecarts on collision.");
        seed(f, changed, "perf.lag-machine.excess-minecarts-limit", 10, "Threshold for excess minecarts at a collision point.");
        seed(f, changed, "perf.lag-machine.remove-excess-boats", false, "Remove excess boats on collision.");
        seed(f, changed, "perf.lag-machine.excess-boats-limit", 10, "Threshold for excess boats at a collision point.");

        // --- Sensor ---
        seed(f, changed, "perf.sensor.enabled", true, "Master switch for the multi-signal load sensor + self-tune loop.");
        seed(f, changed, "perf.sensor.warmup-ticks", 600, "Ticks to skip at boot before sampling (avoids false escalation).");
        seed(f, changed, "perf.sensor.cadence-ticks", 20, "Sample cadence in server ticks (20 = 1s at 20 TPS).");
        seed(f, changed, "perf.sensor.dwell-samples", 3, "Consecutive samples in a worse band before escalating a tier.");
        seed(f, changed, "perf.sensor.recovery-dwell-multiplier", 2.0, "Dwell multiplier when recovering to a better tier (>= 1.0).");
        seedThresholds(f, changed, "mspt", 30.0, 40.0, 60.0, 100.0, "MSPT tier thresholds (higher = worse).");
        migrateSeededTpsLadder(f, changed); // pre-2f installs carry the flap-prone 19/18/17/15 seed
        migrateSeededAntixrayExtras(f, changed); // retract the chest/liquid-flicker seeds once
        migrateAntixrayReenableLiquids(f, changed); // r22: SourbyEngine re-hides dynamically -> liquids back on
        seedThresholds(f, changed, "tps", 17.0, 15.0, 13.0, 10.0, "TPS tier thresholds (lower = worse). Self-tune reacts only below ~17 TPS; idle/healthy servers stay GREEN.");
        seedThresholds(f, changed, "mem", 75.0, 85.0, 92.0, 97.0, "Heap % tier thresholds (higher = worse).");
        seedThresholds(f, changed, "gc-ms-per-min", 20.0, 50.0, 100.0, 300.0, "GC pause ms/min tier thresholds (higher = worse).");

        // --- Built-in ViaVersion/ViaBackwards auto-provision (default ON) ---
        seed(f, changed, "viaversion.auto-provision", true,
            "Auto-download + SHA-256-verify the pinned ViaVersion + ViaBackwards into plugins/ on first "
            + "boot so old clients (>=1.20) can join the native 26.2/1.21.9 server. false = manage Via "
            + "yourself / run offline; the next boot then quarantines the auto-provisioned jars to "
            + "*.jar.disabled (your own Via jars are never touched). The oldest allowed client (1.20) "
            + "is set in plugins/ViaVersion/config.yml -> block-versions.");

        // --- Anti-xray raytrace ore/liquid reveal (Folia core) ---
        // Complementary layer ABOVE Paper's built-in anti-xray engine (seeded to engine-mode 1 / HIDE
        // by baritone-defense below): hides cave-EXPOSED ores (which Paper still leaks through walls) on
        // chunk send, then reveals each one when an async raytrace confirms real line-of-sight (or
        // instantly within 8 blocks for mining UX). Stays inert unless a world also has Paper
        // anticheat.anti-xray.enabled: true (baritone-defense seeds exactly that in paper-world-defaults.yml).
        if (f.getComment("antixray") == null) {
            f.setComment("antixray", "SourbyCraft anti-xray. Two composed layers: (1) baritone-defense seeds Paper's "
                + "built-in engine to engine-mode 1 (HIDE — lightweight) so occluded ores + base-indicator blocks "
                + "(chests/spawners/valuables) are sent to the client as plain stone — xray/Baritone can't see real "
                + "ores through walls (can't beeline to them) and can't scout underground bases; (2) the raytrace "
                + "layer below reveals the genuinely line-of-sight-visible ores to legit players. Layer 1 hides the "
                + "occluded set; layer 2 un-hides the truly-visible subset. (engine-mode 2 = fake-ores is available "
                + "but heavier — see antixray.engine-mode.)");
        }
        seed(f, changed, "antixray.enabled", true,
            "Master switch for the raytrace ore/liquid reveal layer. false = onChunkSent is a single volatile read (zero cost).");
        seed(f, changed, "antixray.fluid-obscures", true,
            "A fluid (water/lava) between the player eye and an ore obscures it (ore stays hidden). ANDed with the per-world override.");
        seed(f, changed, "antixray.hide-liquids", true,
            "Also hide cave-exposed LIQUID (water/lava, source + flowing) blocks the same way as ores. Reveals on real "
            + "line-of-sight and RE-HIDES when sight is lost (SourbyEngine dynamic visibility). Set false if fluid re-render bothers you.");
        seed(f, changed, "antixray.hide-block-entities", true,
            "Hide cave-exposed BLOCK-ENTITIES (chests, trapped/ender chests, barrels, shulker boxes, hoppers, "
            + "dispensers/droppers, furnaces, brewing stands, mob/trial spawners, vaults) via SourbyEngine's block-update "
            + "path — this removes the block-entity from the receiver's client so xray/ESP can't read it through walls. "
            + "Reveals + re-hides on line-of-sight like ores. NOT routed through Paper's palette (that glitched chests).");
        seed(f, changed, "antixray.hide-entities", true,
            "Anti-ESP: hide occluded mobs / item drops from a player's client (behind blocks, out of line-of-sight). "
            + "Players, holograms (Display / armor-stand), and hanging entities (frames/paintings) are always shown; "
            + "entities within 8 blocks are always shown. Per-world gate: antixray.world-overrides.<world>.entity-obfuscation. AND-ed with antixray.enabled.");
        seed(f, changed, "antixray.hide-particles", true,
            "Anti-ESP: drop particle packets a player has no line-of-sight to (particles emitted behind walls from their POV). "
            + "Particles within 8 blocks are always shown so the player's own effects survive chunk streaming. AND-ed with antixray.enabled.");
        seed(f, changed, "antixray.all-blocks", false,
            "Gate ALL Paper anti-xray hidden-blocks (not just ores) through the raytrace, not only the ore tags. Off = ores only.");
        seed(f, changed, "antixray.raytrace.interval-ticks", 2,
            "tickCycle cadence in server ticks: reveal confirmed ores + submit near-pending raytraces. Higher = cheaper, laggier reveal.");
        seed(f, changed, "antixray.raytrace.distance", 64.0,
            "Max distance (blocks) from the player eye an ore may be for a line-of-sight raytrace to be submitted.");
        seed(f, changed, "antixray.raytrace.max-checks-per-cycle", 10,
            "Per tickCycle, max async raytrace checks submitted per player (throttle; the async worker runs off-thread).");
        seed(f, changed, "antixray.raytrace.max-pending-per-player", 512,
            "Per player, max hidden-and-pending ore positions held at once. Budget full = remaining ores stay visible (fail-open).");
        seed(f, changed, "antixray.raytrace.cache-ttl-ticks", 100,
            "TTL (ticks) for the per-chunk exposed-ore scan cache before a non-event re-scan. Precise event invalidation still applies.");

        // --- Baritone / anti-raid defense: seed Paper's built-in engine to engine-mode 1 (HIDE —
        // lightweight) with a broad ore + base-indicator hidden set. This is a boot-time CONFIG seed of
        // config/paper-world-defaults.yml (applied in init(), before worlds build their chunk-packet
        // controller); the keys below are the SourbyCraft toggles that drive it. ---
        dev.iyanz.sourbycraft.antixray.PaperAntiXrayDefense.seedDefaults(f, changed);

        // --- Network / client latency (documented latency-relevant knobs) ---
        // These keys are read in init() but were previously unseeded, so they never surfaced in the
        // operator file. Seed + document them here so the latency surface is discoverable. The
        // network-section header comment (set after the keys exist, below) covers the knobs that
        // live outside this TOML.
        seed(f, changed, "network.auto-throttle-view", true,
            "Dynamically drop each player's view distance under load (perf-engine). Fewer chunks in flight "
            + "= less per-tick chunk-send work and lower client-perceived stall. Safe: only view distance.");
        seed(f, changed, "network.min-view-distance", 4,
            "Floor for auto-throttle-view: view distance never drops below this many chunks.");
        seed(f, changed, "network.compression-level", 4,
            "zlib level (0-9) for packets above network-compression-threshold. Bridged live to Paper's "
            + "misc.compressionLevel when != 4. Lower (e.g. 1-3) = less CPU per compressed packet and "
            + "slightly lower latency on large packets, at the cost of more bandwidth; 4 is a balanced "
            + "default. 0 = no compression work (max CPU savings, max bandwidth).");
        seed(f, changed, "network.max-chunk-send-rate", -1.0,
            "Cap on chunks streamed to a player per second (bridged to Paper playerMaxChunkSendRate). "
            + "-1 = auto/unlimited. A finite cap (e.g. 40.0) smooths join/teleport bursts so the netty "
            + "thread is not saturated by chunk data, keeping gameplay packets low-latency during streaming.");
        // Section-header note (set after the network.* keys exist so the table node is present).
        // Documents the latency knobs that are NOT SourbyCraft-TOML keys, plus the safe defaults
        // already applied and the wins that would need an NMS patch (left documented, not done).
        if (f.getComment("network") == null) {
            f.setComment("network", ""
                + " Client-latency / network tuning.\n"
                + "\n"
                + " Already optimal on this build (no config needed):\n"
                + "   - TCP_NODELAY (Nagle disabled): hardcoded ON for both the server acceptor and every\n"
                + "     player connection (ServerConnectionListener + Connection). Nagle batches tiny packets\n"
                + "     for up to ~40ms; disabling it sends movement/interact packets immediately, the single\n"
                + "     biggest safe latency win, already applied. Safe: affects only TCP buffering, never\n"
                + "     packet contents or ordering.\n"
                + "\n"
                + " server.properties knob (set it there, not in this TOML):\n"
                + "   - network-compression-threshold: default 256 (a sane default). Packets larger than this\n"
                + "     many bytes are zlib-compressed; smaller latency-sensitive packets (movement, interact)\n"
                + "     skip the compress/decompress round-trip, saving CPU and a few ms, while chunks still\n"
                + "     compress to save bandwidth. Behind a proxy that already compresses the backend link,\n"
                + "     set -1 to avoid double-compression.\n"
                + "\n"
                + " Luminol optimizations (luminol_global_config.toml, NOT here):\n"
                + "   - use_async_protocol_switching.enabled: kept OFF. Changes the login/config packet\n"
                + "     sequence and is incompatible with ViaVersion (auto-provisioned for old clients).\n"
                + "     Enabling it would shave a little join latency but risks breaking legacy-client packet\n"
                + "     handling — DO NOT enable while Via is in use.\n"
                + "\n"
                + " Documented-but-not-done (needs an NMS patch, intentionally not applied):\n"
                + "   - Per-connection SO_SNDBUF/SO_RCVBUF sizing + TCP_QUICKACK: tuning these needs an edit to\n"
                + "     the Netty bootstrap; OS defaults are fine for typical player counts, so left as a\n"
                + "     documented option only.\n"
                + "   - Live-reloading network-compression-threshold from this TOML would require reflecting\n"
                + "     into the final DedicatedServerProperties field; left in server.properties.");
            changed[0] = true;
        }

        // --- Proxy forwarding (F-proxy) ---
        // Paper/Folia implements both forwarding schemes natively; this SourbyCraft section is a
        // clean surface over them. proxy.mode drives the effective native settings at boot (applied
        // pre-bind by ProxyForwarding.run, which is Folia-safe). 'none' (default) = never override,
        // just detect + validate + log whatever the operator set in paper-global.yml / spigot.yml.
        if (f.getComment("proxy") == null) {
            f.setComment("proxy", "SourbyCraft proxy-forwarding surface over Paper's native forwarding "
                + "(Velocity modern / legacy BungeeCord). Behind a proxy: set the backend to online-mode=false "
                + "in server.properties and FIREWALL the backend port to the proxy IP (or use proxy.allowed-ips).");
        }
        seed(f, changed, "proxy.mode", "none",
            "Proxy forwarding mode: none | velocity | bungeecord. 'none' = direct/no override (the server "
            + "still honours native paper-global.yml/spigot.yml forwarding if you set it there). 'velocity' = "
            + "Velocity modern (secure, secret-based). 'bungeecord' = legacy BungeeCord forwarding — also used "
            + "by Waterfall and the BungeeCord forks XCord + FlameCord. Applied to Paper's native settings at "
            + "boot before the network binds.");
        seed(f, changed, "proxy.velocity.secret", "",
            "Velocity modern-forwarding secret. Must EXACTLY match the 'secret' in your proxy's "
            + "velocity forwarding.secret file. Only used when proxy.mode=velocity. Leave blank and set "
            + "the PAPER_VELOCITY_SECRET env var instead if you prefer not to store it in this file. "
            + "NEVER commit a real secret to version control.");
        seed(f, changed, "proxy.velocity.online-mode", true,
            "Whether the proxy tells the backend the player is online-mode (has a real Mojang UUID/skin). "
            + "Almost always true. Only used when proxy.mode=velocity.");
        seed(f, changed, "proxy.bungeecord.online-mode", true,
            "Whether legacy BungeeCord forwarding passes through online-mode player data (real UUID/skin). "
            + "Almost always true. Only used when proxy.mode=bungeecord.");
        seed(f, changed, "proxy.anti-bypass-enabled", false,
            "OPT-IN (default false): when a proxy mode is active, register a login listener that REFUSES any "
            + "direct (non-proxy) connection whose source IP is not in proxy.allowed-ips, so players cannot "
            + "skip the proxy and spoof their identity. PRIMARY defense is a firewall (restrict the backend "
            + "port to the proxy IP) — this listener is a fallback. Enabling it disables the config-phase fast "
            + "join path (HorriblePlayerLoginEventHack) and slows every join, so it is off by default.");
        seed(f, changed, "proxy.allowed-ips", new java.util.ArrayList<String>(),
            "IP allowlist for proxy.anti-bypass-enabled. List your proxy server IP(s) here (e.g. "
            + "[\"127.0.0.1\", \"10.0.0.5\"]). Checked against the connection's real (un-spoofable) source IP. "
            + "Empty = the listener is NOT registered (an empty allowlist would reject everyone).");

        // --- Spark bridge + GC advisor ---
        seed(f, changed, "spark.enabled", true,
            "Enable the spark profiler bridge (used by /spark and /sparkview). The bundled spark is "
            + "disabled on this Folia build; for FULL spark drop a standalone spark plugin jar into "
            + "plugins/ (it is honoured — the server prefers an external spark plugin) and restart. "
            + "That registers the real /spark and lights up /sparkview. false = never touch spark.");
        seed(f, changed, "branding.gc-advisor.enabled", true, "Enable the startup GC/JVM-flags advisory log.");

        // --- Varied server messages (F1-7): the message layer owns the key set + built-in variants. ---
        dev.iyanz.sourbycraft.lang.SourbyMessages.seedDefaults(f, changed);

        // --- Max players (F1-6): set by /maxp; 0 = use server.properties. ---
        seed(f, changed, dev.iyanz.sourbycraft.perf.MaxPlayersConfig.KEY, 0,
            "Server max-player slot count set by /maxp. Re-applied at boot so it wins over server.properties. 0 = use server.properties.");
        seed(f, changed, dev.iyanz.sourbycraft.perf.MaxPlayersBypass.ENABLED_KEY, false,
            "Let sourbycraft.maxplayers.bypass holders (+ ops) join a full server. Default false: OFF keeps"
            + " the fast config-phase join path; enabling registers a PlayerLoginEvent listener that"
            + " disables that fast path (HorriblePlayerLoginEventHack) and slows every join.");

        if (changed[0]) {
            try {
                f.save();
                dev.iyanz.sourbycraft.util.SourbyLogger.info("seeded defaults into sourbycraft_config/sourbycraft_global_config.toml");
            } catch (Throwable t) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("could not save unified config after seeding: " + t.getMessage());
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

    /**
     * One-time migration: builds before 2c6bea7's ladder fix SEEDED tps 19/18/17/15 into every
     * install's TOML, and an explicit value wins over any new default forever (the seeded-default
     * trap). If the file carries exactly that quad it is the old seed, not an operator choice —
     * rewrite it to the 17/15/13/10 ladder the sensor ships with.
     */
    /**
     * One-time retraction of the anti-xray extras that flicker as stone (chests are block entities,
     * fluids re-render on LOS change). Pre-r18 installs seeded antixray.hide-liquids=true and
     * antixray.hide-base-blocks=true; those explicit values win over the new false defaults forever.
     * Flip them false EXACTLY ONCE, gated on antixray.extras-migrated so an operator who deliberately
     * turns them back on afterwards is never overridden again. The PaperAntiXrayDefense re-assert
     * (later in init) then rewrites paper-world-defaults.yml to drop the base blocks on this same boot.
     */
    private static void migrateSeededAntixrayExtras(com.electronwill.nightconfig.core.file.CommentedFileConfig f,
                                                    boolean[] changed) {
        final Object done = f.get("antixray.extras-migrated");
        if (done instanceof Boolean b && b) return; // already migrated
        boolean flipped = false;
        if (Boolean.TRUE.equals(f.get("antixray.hide-liquids"))) {
            f.set("antixray.hide-liquids", false);
            flipped = true;
        }
        if (Boolean.TRUE.equals(f.get("antixray.hide-base-blocks"))) {
            f.set("antixray.hide-base-blocks", false);
            flipped = true;
        }
        f.set("antixray.extras-migrated", true);
        f.setComment("antixray.extras-migrated",
            "Internal: marks the one-time retraction of the chest/liquid-flicker anti-xray extras. "
            + "Do not edit. Set antixray.hide-liquids / antixray.hide-base-blocks yourself to re-enable them.");
        changed[0] = true;
        if (flipped) {
            dev.iyanz.sourbycraft.util.SourbyLogger.info(
                "migrated anti-xray extras -> hide-liquids=false + hide-base-blocks=false "
                + "(they flicker as stone; ore anti-xray unchanged). Set them true to re-enable.");
        }
    }

    /**
     * One-time re-enable of {@code antixray.hide-liquids}. {@link #migrateSeededAntixrayExtras} turned
     * liquids OFF because the old persistent-reveal engine left them flickering as stone. SourbyEngine
     * (r21+) re-hides dynamically on loss of sight, which removes that flicker mode, so liquids go back
     * ON exactly once — gated on {@code antixray.dynamic-rehide-migrated} so an operator who turns them
     * off again afterwards is never overridden. Only flips a value that is currently the retracted
     * {@code false}; an already-true or absent (fresh-install, seeded true below) value is left alone.
     */
    private static void migrateAntixrayReenableLiquids(com.electronwill.nightconfig.core.file.CommentedFileConfig f,
                                                       boolean[] changed) {
        final Object done = f.get("antixray.dynamic-rehide-migrated");
        if (done instanceof Boolean b && b) return; // already migrated
        boolean flipped = false;
        if (Boolean.FALSE.equals(f.get("antixray.hide-liquids"))) {
            f.set("antixray.hide-liquids", true);
            flipped = true;
        }
        f.set("antixray.dynamic-rehide-migrated", true);
        f.setComment("antixray.dynamic-rehide-migrated",
            "Internal: marks the one-time re-enable of hide-liquids after SourbyEngine gained dynamic "
            + "re-hide. Do not edit. Set antixray.hide-liquids=false yourself to keep liquids visible.");
        changed[0] = true;
        if (flipped) {
            dev.iyanz.sourbycraft.util.SourbyLogger.info(
                "re-enabled anti-xray hide-liquids (SourbyEngine now re-hides cave fluids dynamically "
                + "on loss of sight). Set antixray.hide-liquids=false to opt out.");
        }
    }

    private static void migrateSeededTpsLadder(com.electronwill.nightconfig.core.file.CommentedFileConfig f,
                                               boolean[] changed) {
        final String base = "perf.sensor.thresholds.tps.";
        final Object y = f.get(base + "yellow"), o = f.get(base + "orange"),
            r = f.get(base + "red"), e = f.get(base + "emergency");
        if (y instanceof Number ny && ny.doubleValue() == 19.0
            && o instanceof Number no && no.doubleValue() == 18.0
            && r instanceof Number nr && nr.doubleValue() == 17.0
            && e instanceof Number ne && ne.doubleValue() == 15.0) {
            f.set(base + "yellow", 17.0);
            f.set(base + "orange", 15.0);
            f.set(base + "red", 13.0);
            f.set(base + "emergency", 10.0);
            changed[0] = true;
            dev.iyanz.sourbycraft.util.SourbyLogger.info(
                "migrated seeded TPS tier ladder 19/18/17/15 -> 17/15/13/10 (pre-2f seed, flap-prone)");
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

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    // Once-per-startup WARN dedupe so a single malformed key doesn't spam the log.
    private static final java.util.Set<String> WARNED_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void warnOnce(String path, Object actual, String expected) {
        if (WARNED_KEYS.add(path)) {
            dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                "config key '" + path + "' invalid type '" + actual.getClass().getSimpleName()
                + "', expected " + expected + " — using default");
        }
    }

    /** Test-only view of the dedupe set. */
    static java.util.Set<String> warnedKeysForTest() {
        return java.util.Set.copyOf(WARNED_KEYS);
    }
}
