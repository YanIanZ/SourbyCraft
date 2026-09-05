package dev.iyanz.sourbycraft.core;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.bootstrap.PluginProvisioner;
import dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics;
import dev.iyanz.sourbycraft.brand.StartupBanner;
import dev.iyanz.sourbycraft.command.SourbyCraftCommands;
import dev.iyanz.sourbycraft.lang.SourbyJoinLeaveListener;
import dev.iyanz.sourbycraft.maxplayers.MaxPlayersBypass;
import dev.iyanz.sourbycraft.maxplayers.MaxPlayersConfig;
import dev.iyanz.sourbycraft.update.AutoUpdateSettings;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import org.bukkit.plugin.Plugin;

/**
 * SourbyCraft's single boot hook on the Canvas re-platform (feat/canvas-engine, PR #12).
 *
 * <p>The archived Folia build hooked its post-config boot sequence via
 * {@code me.earthme.luminol.commands.CommandRegister#register()} (itself invoked from Luminol's
 * {@code ConfigManager#loadConfigFiles}, called from {@code DedicatedServer#initServer}). Canvas
 * carries no Luminol, so this class is called directly from a small hand-authored
 * {@code minecraft-patch} to {@code DedicatedServer#initServer} instead — right after Paper's own
 * {@code PaperCommands.registerCommands(this)} call (so our commands claim the bare names AFTER
 * Paper registers its built-ins, with no two-phase reclaim needed) and before
 * {@code CraftServer#loadPlugins()} (so {@link PluginLoadDiagnostics} is installed in time to
 * observe a plugin load failure, and the config file exists before any plugin might read it).
 *
 * <p>Deliberately minimal: only the utility layer lives here now. The self-tuning perf-engine,
 * anti-xray raytrace reveal and proxy-forwarding/hardening-advisor security layer that the archived
 * {@code PerfEngineBootstrap} also wired are DEFERRED on this benchmark build — see the PR #12 task
 * brief. Every step is wrapped so a single failure can never abort boot.
 *
 * <p>r40 adds two standalone memory-management steps (9-10) that are NOT part of the deferred
 * perf-engine: {@link dev.iyanz.sourbycraft.perf.SmartSwap} (adaptive heap reclaim) and
 * {@link dev.iyanz.sourbycraft.swap.AutoSwap} (optional OS swapfile creation).
 */
public final class SourbyCraftBootstrap {

    private static volatile boolean started = false;

    private SourbyCraftBootstrap() {}

    /**
     * Runs every utility-layer boot step in order (config, ViaVersion config seeding, startup
     * banner, plugin-load diagnostics, command registration, join/leave messages, max-players,
     * the virtual-thread executor, the auto-updater, SmartSwap, auto-swap). Idempotent — a second
     * call is a no-op. Each step is individually wrapped so one failure never aborts the rest or the
     * server boot.
     */
    public static synchronized void init() {
        if (started) return;
        started = true;

        final Plugin owner = MinecraftInternalPlugin.INSTANCE;

        // Register before command and plugin loading; shutdown keeps it readable through plugin disable.
        try {
            dev.iyanz.sourbycraft.perf.MetricsRuntime.start(org.bukkit.Bukkit.getServicesManager(), owner);
        } catch (Throwable t) {
            SourbyLogger.error("MetricsRuntime.start failed", t);
        }

        // 1. Load + seed the unified TOML (messages, /maxp, auto-updater settings).
        try {
            SourbyCraftConfig.init();
        } catch (Throwable t) {
            SourbyLogger.error("SourbyCraftConfig.init failed; utility layer will use hardcoded defaults", t);
        }

        // 1b. Write the shipped default ViaVersion/ViaBackwards config.yml (1.20 floor) into
        //     plugins/<name>/, only when absent — must run before CraftServer#enablePlugins,
        //     where Via reads its config in onEnable.
        try {
            PluginProvisioner.provisionConfigs(SourbyCraftConfig.cfgBool("viaversion.auto-provision", true));
        } catch (Throwable t) {
            SourbyLogger.error("PluginProvisioner.provisionConfigs failed", t);
        }

        // 2. Branded startup banner + GC/JVM advisory (console output; warn-only).
        try {
            StartupBanner.printOnce();
        } catch (Throwable t) {
            SourbyLogger.error("StartupBanner.printOnce failed", t);
        }

        // 3. Capture plugin-load failures for /sys — must be installed before CraftServer#loadPlugins.
        try {
            PluginLoadDiagnostics.install();
        } catch (Throwable t) {
            SourbyLogger.error("PluginLoadDiagnostics.install failed", t);
        }

        // 4. Claim the bare command names (/tps, /ping, /ver, ...) + register the HUD quit-listener.
        try {
            SourbyCraftCommands.registerAll();
        } catch (Throwable t) {
            SourbyLogger.error("SourbyCraftCommands.registerAll failed", t);
        }

        // 5. Varied join/leave broadcast messages.
        try {
            SourbyJoinLeaveListener.register(owner);
        } catch (Throwable t) {
            SourbyLogger.error("SourbyJoinLeaveListener.register failed", t);
        }

        // 6. /maxp persisted value + opt-in full-server bypass.
        try {
            MaxPlayersConfig.applyAtBoot();
        } catch (Throwable t) {
            SourbyLogger.error("MaxPlayersConfig.applyAtBoot failed", t);
        }
        try {
            MaxPlayersBypass.register(owner);
        } catch (Throwable t) {
            SourbyLogger.error("MaxPlayersBypass.register failed", t);
        }

        // 7. Virtual-thread executor for off-thread command work (/speedtest, /update, /ping geoip).
        try {
            VirtualExecutor.init();
        } catch (Throwable t) {
            SourbyLogger.error("VirtualExecutor.init failed", t);
        }

        // 8. Auto-updater (+ ViaVersion/ViaBackwards keep-current on the same cadence).
        try {
            AutoUpdateSettings.startUpdater();
        } catch (Throwable t) {
            SourbyLogger.error("AutoUpdateSettings.startUpdater failed", t);
        }

        // 9. SmartSwap — standalone adaptive heap-reclaim sensor (r40; config-gated, reloadable).
        //    DEFAULT OFF: SourbyCraft ships with no automatic perf-tuning (operator opts in). The
        //    repeating sensor loop is only scheduled when explicitly enabled, so when off there is no
        //    background task at all — not merely a per-sample no-op. A later /sourbycraft reload that
        //    flips it on will schedule it via SmartSwap.configure()'s start path.
        try {
            if (SourbyCraftConfig.cfgBool("perf.smart-swap.enabled", false)) {
                dev.iyanz.sourbycraft.perf.SmartSwap.ensureStarted();
            }
        } catch (Throwable t) {
            SourbyLogger.error("SmartSwap.ensureStarted failed", t);
        }

        // 9b. GcTracker — always-on, lightweight GC-health sampler feeding /sys and the perf readout.
        //     GC pauses are invisible in TPS/MSPT, so this rolling-window tracker is the only source
        //     for collections/min + GC-time%. One daemon thread; never throws.
        try {
            dev.iyanz.sourbycraft.perf.GcTracker.start();
        } catch (Throwable t) {
            SourbyLogger.error("GcTracker.start failed", t);
        }

        // 10. Auto-swap — optional OS swapfile creation on boot (r40; config-gated, default off).
        //     Dispatched on the virtual-thread executor so a slow fallocate/dd fallback on an unusual
        //     filesystem can never delay boot; AutoSwap.attempt() itself never throws.
        try {
            final boolean swapEnabled = SourbyCraftConfig.cfgBool("swap.auto-create.enabled", false);
            final String swapPath = SourbyCraftConfig.cfgGet("swap.auto-create.path", "cache/sourbycraft.swap");
            final int swapMaxMb = SourbyCraftConfig.cfgInt("swap.auto-create.max-size-mb", 8192);
            VirtualExecutor.run(() -> dev.iyanz.sourbycraft.swap.AutoSwap.attempt(swapEnabled, swapPath, swapMaxMb));
        } catch (Throwable t) {
            SourbyLogger.error("AutoSwap.attempt dispatch failed", t);
        }
    }
}
