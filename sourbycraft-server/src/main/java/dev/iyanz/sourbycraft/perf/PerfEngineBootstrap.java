package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.core.PerWorldHolder;
import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.util.SourbyLogger;
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
 *         <li>{@link OwnerProtection} — dropped-item pickup lock (event listener).</li>
 *         <li>{@link LagLimits} — per-chunk/per-world entity caps (event listeners) plus the
 *             1 Hz arrow sweeper, driven here on the global-region scheduler.</li>
 *         <li>{@link ViewThrottle} — per-world view-distance throttle (global-region scheduler).</li>
 *         <li>{@link LagMachineCounters#resetTickCounters()} — per-tick projectile-load counter
 *             reset, driven every tick from the global-region scheduler (no NMS tick hook on Folia).</li>
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
 * <p><b>Deferred to F2e.</b> The deepest knob effects that require NMS tick-loop hooks — actually
 * skipping entity-AI ticks ({@code perf.ai.throttle-*}), applying {@code perf.entity-tick-rate},
 * and the in-tick projectile-load cap ({@link LagMachineCounters#incrementProjectileChunkLoad()} /
 * {@link LagMachineCounters#projectileChunkLoadsThisTick()}) — need minecraft-patches and are NOT
 * wired here. The knobs are still set/read; only their in-tick enforcement is deferred.
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
            // Path parity with the Paper tag, which used new File("sourbycraft.yml") (server-root
            // relative) from its DedicatedServer NMS patch.
            SourbyCraftConfig.init(new File("sourbycraft.yml"));
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: SourbyCraftConfig.init failed", t);
        }
        try {
            PerfSensor.start();
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: PerfSensor.start failed", t);
        }
        try {
            startActuators();
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: actuator startup failed", t);
        }
    }

    /**
     * Start the F2c actuator layer. Each actuator is guarded by its own enable flag so a disabled
     * feature registers nothing (zero listener/scheduler cost). All wiring uses the internal
     * Minecraft plugin handle ({@link MinecraftInternalPlugin#INSTANCE}) — always enabled, which the
     * Bukkit event bus and the Folia schedulers both require of an owning plugin.
     */
    private static void startActuators() {
        final Plugin owner = MinecraftInternalPlugin.INSTANCE;

        // Shared per-world cleanup listener (must precede any PerWorldHolder use; holders created
        // earlier still evict). Cheap and always needed by LagLimits/ViewThrottle.
        try {
            PerWorldHolder.registerCleanup(owner);
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: PerWorldHolder.registerCleanup failed", t);
        }

        // OwnerProtection — dropped-item pickup lock. Guard: item.owner-protection-enabled.
        if (SourbyCraftConfig.ownerProtectionEnabled) {
            try {
                OwnerProtection.register(owner);
                SourbyLogger.info("perf-engine: OwnerProtection actuator registered");
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
                SourbyLogger.info("perf-engine: LagLimits actuator registered (+ Folia arrow sweeper)");
            } catch (Throwable t) {
                SourbyLogger.error("perf-engine: LagLimits.register failed", t);
            }
        }

        // ViewThrottle — per-world view-distance throttle. Self-guards on autoThrottleView, but we
        // also short-circuit here for zero cost when disabled.
        if (SourbyCraftConfig.autoThrottleView) {
            try {
                ViewThrottle.register(owner);
            } catch (Throwable t) {
                SourbyLogger.error("perf-engine: ViewThrottle.register failed", t);
            }
        }

        // LagMachineCounters — per-tick projectile-load counter reset. On Folia there is no NMS
        // tick hook; drive the reset once per server tick from the global-region scheduler so the
        // per-tick cap semantics are preserved for the deferred F2e in-tick enforcement.
        try {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                owner, task -> LagMachineCounters.resetTickCounters(), 1L, 1L);
            SourbyLogger.info("perf-engine: LagMachineCounters per-tick reset driver started (Folia)");
        } catch (Throwable t) {
            SourbyLogger.error("perf-engine: LagMachineCounters reset driver failed to start", t);
        }
    }
}
