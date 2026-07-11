package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.core.PerWorldHolder;
import dev.iyanz.sourbycraft.lang.SourbyJoinLeaveListener;
import dev.iyanz.sourbycraft.lang.SourbyMessages;
import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.leavesmc.leaves.plugin.MinecraftInternalPlugin;

import java.io.File;

/**
 * One-shot boot entry point for the SourbyCraft perf-engine on the Folia base (F2b + F2c).
 *
 * <p>Invoked once from the authored post-config hook
 * ({@link me.earthme.luminol.commands.CommandRegister#register()}). It:
 *
 * <ol>
 *   <li>Runs {@link SourbyCraftConfig#init(File)} — previously wired but never called on the
 *       Folia base, so the config registry + perf-knob + sensor-config bridges are now live.
 *       (F2c also drives {@code JvmHeapAdvisor.init()} and {@code CombatProfile.apply()} from
 *       inside {@code init()}, matching the Paper tag.)</li>
 *   <li>Starts the {@link PerfSensor} Folia global-region-scheduler sampler, which drives the
 *       {@link SelfTuneController} + {@link TierBossBar} on each tier transition.</li>
 *   <li><b>(F2c)</b> Starts the actuator layer — the behaviour that <em>reads</em> the knobs /
 *       sensor tier and changes real server behaviour — each guarded by its
 *       {@link SourbyCraftConfig} enable flag (zero cost when disabled):
 *       <ul>
 *         <li>{@link PerWorldHolder#registerCleanup} — one shared WorldUnloadEvent listener for
 *             every per-world map (LagLimits arrow counts, ViewThrottle originals).</li>
 *         <li>{@link ConfigBridge} — pushes SourbyCraft entity/item/server.idle-timeout
 *             overrides into the per-world Spigot/Paper config engines (WorldLoadEvent listener).</li>
 *         <li>{@link OwnerProtection} — dropped-item pickup lock (event listener).</li>
 *         <li>{@link LagLimits} — per-chunk/per-world entity caps (event listeners) plus the
 *             1 Hz arrow sweeper, driven here on the global-region scheduler.</li>
 *         <li>{@link ViewThrottle} — per-world view-distance throttle (global-region scheduler).</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>The Paper tag wired {@code SourbyCraftConfig.init()} into a {@code DedicatedServer} NMS
 * patch, ticked the sensor from {@code MinecraftServer.tickChildren}, and enrolled the actuators
 * through a {@code SourbyCorePlugin}/{@code ModuleRegistry} JavaPlugin. Folia has no global tick
 * loop and this port keeps the patch series untouched (authored source only), so every concern is
 * driven from this authored bootstrap using the internal Minecraft plugin handle instead.
 *
 * <p><b>F2e (knob enforcement).</b> The mappable knobs are now enforced in-tick by bridging them
 * onto the Luminol/Pufferfish/Kaiiju base config rather than adding NMS minecraft-patches: the
 * projectile chunk-load caps ({@code perf.lag-machine.max-projectile-loads-per-*}) drive
 * {@code ProjectileChunkReduceConfig} (enforced in {@code Projectile.setPosRaw}), and the AI
 * throttle gate ({@code perf.ai.throttle-*}) drives {@code EntityGoalSelectorInactiveTickConfig}
 * (enforced in {@code Mob.serverAiStep}). See {@link KnobEnforcer}; it runs once at boot (below,
 * after config init) and again on every tier transition from {@link SelfTuneController}. Knobs
 * with no equivalent base mechanism — {@code perf.entity-tick-rate}, the excess minecart/boat
 * collision sweepers, and the transient-projectile save suppression
 * ({@code perf.lag-machine.disable-saving-{snowballs,fireworks}}) — remain documented in the F2e
 * report rather than forcing a churn-prone patch. (The earlier {@code LagMachineCounters}
 * per-tick counter scaffold was removed: the base config now enforces the projectile caps, so
 * nothing read the counter and its permanent per-tick reset task was dead weight.)
 *
 * <p><b>Double-start guard.</b> {@link #start()} is guarded by {@link #started} so a second
 * invocation of the boot hook is a no-op. {@link PerfSensor#start()} carries its own guard too.
 * The whole body is wrapped so a failure cannot abort the server boot sequence.
 */
public final class PerfEngineBootstrap {

    private static volatile boolean started = false;

    private PerfEngineBootstrap() {}

    /** Idempotent. Runs config init + starts the sampler + actuators. Safe to call from the boot hook. */
    public static synchronized void start() {
        if (started) return;
        started = true;
        try {
            // Config lives entirely in the unified TOML now; the File arg is an unused marker that
            // keeps seedUnifiedPerfDefaults out of any reflective no-arg walk (see init()).
            SourbyCraftConfig.init(new File("sourbycraft"));
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: SourbyCraftConfig.init failed", t);
        }
        // SourbyCraft — built-in ViaVersion/ViaBackwards, phase 2: seed the default config.yml
        // (with the 1.20 client floor) for each provisioned plugin. The jars themselves are
        // downloaded far earlier, in SourbyBootstrap.main, before the plugin scan — see that class.
        // This hook precedes CraftServer.enablePlugins(), where a legacy Bukkit plugin reads its
        // config in onEnable, so the floor is in place before Via reads it. Idempotent (absent-only),
        // gated by the now-parsed viaversion.auto-provision toggle; wrapped so it cannot abort boot.
        try {
            dev.iyanz.sourbycraft.bootstrap.PluginProvisioner.provisionConfigs(
                SourbyCraftConfig.viaVersionAutoProvision);
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: ViaVersion default-config seeding failed", t);
        }
        // SourbyCraft F2e: enforce the loaded knob baseline onto the Luminol/Pufferfish base config
        // now that init() has applied every yml override (combat profile + explicit knob keys). This
        // is the boot-time bridge so the base actually enforces the SourbyCraft baseline in-tick;
        // SelfTuneController re-enforces on every subsequent tier transition.
        try {
            KnobEnforcer.enforceAll();
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: KnobEnforcer boot enforcement failed", t);
        }
        // SourbyCraft F1-6: re-apply the persisted /maxp value AFTER init() has loaded the unified
        // config, so an operator's stored sourbycraft.max-players wins over server.properties on
        // every restart. No-op when unset. Runs before actuators so the cap is correct pre-login.
        try {
            MaxPlayersConfig.applyAtBoot();
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: MaxPlayersConfig.applyAtBoot failed", t);
        }
        // SourbyCraft F1-7 (varied lang): apply a random SourbyCraft MOTD variant at startup, now
        // that init() has loaded/seeded the unified config's messages.* section. One MiniMessage
        // parse + Bukkit.motd(Component) — no scheduling, safe on the boot thread.
        try {
            applyMotd();
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: SourbyCraft MOTD apply failed", t);
        }
        try {
            PerfSensor.start();
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: PerfSensor.start failed", t);
        }
        // SourbyCraft F1-8 (advanced auto-updater): start the SourbyCraft updater now that the
        // unified config is loaded AND the internal plugin handle + Folia async scheduler are ready.
        // No-op unless misc.auto_update.enabled=true. Channel-aware, verify-before-stage, safe apply
        // modes (default notify), hex op-notification; checks run on Bukkit.getAsyncScheduler().
        try {
            me.earthme.luminol.config.modules.misc.AutoUpdateConfig.startUpdater();
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: SourbyCraft auto-updater start failed", t);
        }
        try {
            startActuators();
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: actuator startup failed", t);
        }
    }

    /** Apply one random SourbyCraft MOTD variant (F1-7). Skipped silently if the variant is empty. */
    private static void applyMotd() {
        Component motd = SourbyMessages.get(SourbyMessages.MOTD);
        if (motd == Component.empty()) return;
        Bukkit.motd(motd);
        SourbyLogger.debug("applied a random MOTD variant ("
            + SourbyMessages.variantCount(SourbyMessages.MOTD) + " configured)");
    }

    /**
     * Start the F2c actuator layer. Each actuator is guarded by its own enable flag so a disabled
     * feature registers nothing (zero listener/scheduler cost). All wiring uses the internal
     * Minecraft plugin handle ({@link MinecraftInternalPlugin#INSTANCE}) — always enabled, which the
     * Bukkit event bus and the Folia schedulers both require of an owning plugin.
     */
    private static void startActuators() {
        final Plugin owner = MinecraftInternalPlugin.INSTANCE;
        // Collect the actuators that registered cleanly and log them in ONE tidy summary line rather
        // than one INFO per actuator. Failures still log individually (they matter).
        final java.util.List<String> registered = new java.util.ArrayList<>();

        // Shared per-world cleanup listener (must precede any PerWorldHolder use; holders created
        // earlier still evict). Cheap and always needed by LagLimits/ViewThrottle.
        try {
            PerWorldHolder.registerCleanup(owner);
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: PerWorldHolder.registerCleanup failed", t);
        }

        // ConfigBridge — pushes SourbyCraft entity/item/server.idle-timeout master values into the
        // per-world Spigot/Paper config engines. Always registered (WorldLoadEvent listener, no-op
        // when nothing was overridden). Its own per-world "[bridge] world: ..." lines report applied keys.
        try {
            ConfigBridge.register(owner);
            registered.add("config-bridge");
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: ConfigBridge.register failed", t);
        }

        // OwnerProtection — dropped-item pickup lock. Guard: item.owner-protection-enabled.
        if (SourbyCraftConfig.ownerProtectionEnabled) {
            try {
                OwnerProtection.register(owner);
                registered.add("owner-protection");
            } catch (Throwable t) {
                SourbyLogger.error("perf-engine: OwnerProtection.register failed", t);
            }
        }

        // LagLimits — per-chunk/per-world entity caps + 1 Hz arrow sweeper.
        // Guard: any of the entity/item/arrow caps is active (> 0). All caps <= 0 => nothing to do.
        if (SourbyCraftConfig.maxEntityPerChunk > 0 || SourbyCraftConfig.maxSpecialsPerChunk > 0
                || SourbyCraftConfig.itemMaxPerChunk > 0 || SourbyCraftConfig.maxArrowsPerWorld > 0) {
            try {
                LagLimits.register(owner);
                // Folia: drive the 1 Hz arrow sweeper from the global-region scheduler (no global
                // main-thread scheduler exists). First sweep after 100t, then every 20t.
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    owner, task -> LagLimits.sweepArrows(), 100L, 20L);
                registered.add("lag-limits");
            } catch (Throwable t) {
                SourbyLogger.error("perf-engine: LagLimits.register failed", t);
            }
        }

        // ViewThrottle — per-world view-distance throttle. Self-guards on autoThrottleView, but we
        // also short-circuit here for zero cost when disabled. (Logs its own "ViewThrottle: registered" line.)
        if (SourbyCraftConfig.autoThrottleView) {
            try {
                ViewThrottle.register(owner);
                registered.add("view-throttle");
            } catch (Throwable t) {
                SourbyLogger.error("perf-engine: ViewThrottle.register failed", t);
            }
        }

        // MaxPlayersBypass — let sourbycraft.maxplayers.bypass holders (+ ops) join a full server.
        // OPT-IN, default OFF: registering the PlayerLoginEvent listener disables the config-phase
        // fast join path (HorriblePlayerLoginEventHack) and slows every join, so it only registers
        // when sourbycraft.maxplayers.bypass-enabled=true. When off, no listener → fast joins.
        try {
            if (MaxPlayersBypass.register(owner)) {
                registered.add("maxplayers-bypass");
            }
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: MaxPlayersBypass.register failed", t);
        }

        // Proxy anti-bypass allowlist (F-proxy). OPT-IN, default OFF: refuses direct (non-proxy)
        // connections whose real source IP is not in proxy.allowed-ips. Only registers when
        // proxy.anti-bypass-enabled=true AND a proxy mode is active AND the allowlist is non-empty —
        // registering a PlayerLoginEvent listener disables the config-phase fast join path
        // (HorriblePlayerLoginEventHack). When off, no listener → fast joins. Firewalling the backend
        // port to the proxy IP remains the documented primary defense.
        try {
            if (dev.iyanz.sourbycraft.security.ProxyAllowlistListener.register(owner)) {
                registered.add("proxy-anti-bypass");
            }
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: ProxyAllowlistListener.register failed", t);
        }

        // SourbyCraft anti-xray raytrace ore/liquid reveal (Folia core). Guard on antixray.enabled so
        // a disabled engine registers no listeners / scheduler (zero cost — onChunkSent is then a single
        // volatile read). Registers the OreReveal listener + starts the tickCycle on the global-region
        // scheduler; onChunkSent is driven by the PlayerChunkSender NMS hook. Logs its own ENABLED line.
        if (SourbyCraftConfig.antixrayEnabled) {
            try {
                dev.iyanz.sourbycraft.antixray.OreReveal.register(owner);
                registered.add("antixray-ore-reveal");
            } catch (Throwable t) {
                SourbyLogger.error("perf-engine: OreReveal.register failed", t);
            }
            // SourbyCraft anti-ESP sub-layers: entity/hologram + particle occlusion. No listener or
            // scheduler to register — the checks are driven synchronously from the NMS packet hooks
            // (ChunkMap.TrackedEntity#updatePlayer / ServerLevel#sendParticles) on the owning region
            // thread, gated by their own AtomicBoolean seeded in SourbyCraftConfig.init. This just
            // reports which anti-ESP sub-layers are live so the boot log shows them (zero cost when off).
            if (dev.iyanz.sourbycraft.antixray.EntityVisibilityCheck.ENABLED.get()) {
                registered.add("antixray-hide-entities");
            }
            if (dev.iyanz.sourbycraft.antixray.ParticleVisibilityCheck.ENABLED.get()) {
                registered.add("antixray-hide-particles");
            }
            owner.getLogger().info("[antixray] anti-ESP: entities "
                + (dev.iyanz.sourbycraft.antixray.EntityVisibilityCheck.ENABLED.get() ? "ENABLED" : "disabled")
                + ", particles "
                + (dev.iyanz.sourbycraft.antixray.ParticleVisibilityCheck.ENABLED.get() ? "ENABLED" : "disabled"));
        }

        // SourbyCraft F1-7 (varied lang): varied SourbyCraft join/leave broadcast messages.
        // Region-safe (name read + Component swap only). Always registered.
        try {
            SourbyJoinLeaveListener.register(owner);
            registered.add("join-leave-messages");
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: SourbyJoinLeaveListener.register failed", t);
        }

        SourbyLogger.info("actuators registered: " + String.join(", ", registered));
    }
}
