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
 * Owns the ordered startup and cleanup of SourbyCraft services and publishes Aurora lifecycle state.
 *
 * <p>The downstream {@code DedicatedServer#initServer} patch invokes {@link #init()} after
 * Paper command registration and before plugin loading. This lets SourbyCraft register its
 * commands and install {@link PluginLoadDiagnostics} before plugins are loaded.</p>
 *
 * <p>Individual stage failures are logged and isolated. Completion publishes RUNNING when any
 * stage succeeded, or FAILED when all stages failed; RUNNING therefore permits degraded service
 * availability. Diagnostics do not tune JVM options or operator configuration.</p>
 *
 * <p>The server owns the call order: startup precedes shutdown. Synchronization prevents duplicate
 * initialization; it does not serialize {@link #close()} against initialization.</p>
 */
public final class SourbyCraftBootstrap {

    private static volatile boolean started = false;

    private SourbyCraftBootstrap() {}

    /**
     * Runs service cleanup after plugin disable, publishing STOPPING before cleanup and STOPPED
     * after all attempts. Each failure is logged without preventing the remaining cleanup steps;
     * STOPPED reports completion of this sequence, not proof that every service stopped successfully.
     */
    public static void close() {
        // Before the first service goes down, so anything consulting acceptingWork() stops
        // admitting during the window when shutdown is in flight and work is still arriving.
        AuroraRuntime.transition(AuroraRuntime.State.STOPPING);
        try { dev.iyanz.sourbycraft.hud.HudBars.close(); }
        catch (Throwable failure) { SourbyLogger.error("HUD shutdown failed", failure); }
        try { AutoUpdateSettings.stopUpdater(); }
        catch (Throwable failure) { SourbyLogger.error("Updater shutdown failed", failure); }
        try { dev.iyanz.sourbycraft.perf.MetricsRuntime.close(org.bukkit.Bukkit.getServicesManager()); }
        catch (Throwable failure) { SourbyLogger.error("Metrics shutdown failed", failure); }
        try { dev.iyanz.sourbycraft.perf.GcTracker.stop(); }
        catch (Throwable failure) { SourbyLogger.error("GC sampler shutdown failed", failure); }
        try { dev.iyanz.sourbycraft.perf.AsyncPathProcessor.shutdown(); }
        catch (Throwable failure) { SourbyLogger.error("Path worker shutdown failed", failure); }
        try { VirtualExecutor.shutdown(); }
        catch (Throwable failure) { SourbyLogger.error("I/O shutdown failed", failure); }
        try { dev.iyanz.sourbycraft.execution.ResourceGovernor.GLOBAL.shutdown(10_000L); }
        catch (Throwable failure) { SourbyLogger.error("Resource Governor shutdown failed", failure); }
        AuroraRuntime.transition(AuroraRuntime.State.STOPPED);
    }

    /**
     * Starts metrics, network counters, configuration, plugin config provisioning, branding,
     * diagnostics, the startup index, commands, listeners, player-slot settings, I/O workers, the
     * updater and GC sampling, in that order.
     * A second invocation is a no-op, including after a partially failed startup. Individual stage
     * failures are logged and counted without skipping subsequent stages.
     */
    public static synchronized void init() {
        if (started) return;
        started = true;

        AuroraRuntime.transition(AuroraRuntime.State.BOOTSTRAPPING);
        AuroraRuntime.transition(AuroraRuntime.State.STARTING);
        final Plugin owner = MinecraftInternalPlugin.INSTANCE;
        final long begun = System.nanoTime();
        stageCount = 0;
        failureCount = 0;

        // Before command and plugin loading; shutdown keeps it readable through plugin disable.
        stage("metrics runtime", () -> {
            dev.iyanz.sourbycraft.perf.MetricsRuntime.start(org.bukkit.Bukkit.getServicesManager(), owner);
        });

        // Wire bytes and packets per direction; rates are sampled by the metrics collector.
        stage("network counters", () -> {
            dev.iyanz.sourbycraft.perf.NetworkMetrics.install();
        });

        // The unified TOML: messages, /maxp, auto-updater settings.
        stage("configuration", () -> {
            SourbyCraftConfig.init();
        });

        // AuroraWorlds API: before plugins enable, so they can look it up in onEnable.
        stage("aurora worlds", () -> {
            try {
                dev.iyanz.sourbycraft.awf.world.AuroraWorldsRuntime.start(owner);
            } catch (final java.io.IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        });

        // ORDERING: must precede CraftServer#enablePlugins, where Via reads its config in
        // onEnable. Writes the shipped default ViaVersion/ViaBackwards config only when absent.
        stage("plugin provisioning", () -> {
            PluginProvisioner.provisionConfigs(SourbyCraftConfig.cfgBool("viaversion.auto-provision", true));
        });

        stage("banner", () -> {
            StartupBanner.printOnce();
        });

        // ORDERING: must precede CraftServer#loadPlugins, or load failures go uncaptured for /sys.
        stage("plugin diagnostics", () -> {
            PluginLoadDiagnostics.install();
            // Folia no longer cancels Bukkit-scheduler tasks on disable; bridged ones must be.
            dev.iyanz.sourbycraft.bridge.AuroraBridge.registerListener(owner);
        });

        // ORDERING: must precede CraftServer#loadPlugins so the operator learns which jars the
        // base will refuse before the refusals scroll past. Analysis only; loads nothing.
        stage("startup index", () -> {
            dev.iyanz.sourbycraft.startup.StartupIndexStage.run();
            dev.iyanz.sourbycraft.startup.StartupTimeline.registerListener(owner);
        });

        // Claims the bare command names (/tps, /ping, /ver, ...) and the HUD quit-listener.
        stage("commands", () -> {
            SourbyCraftCommands.registerAll();
        });

        stage("join listener", () -> {
            SourbyJoinLeaveListener.register(owner);
        });

        // Persisted /maxp value, then the opt-in full-server bypass.
        stage("player slots", () -> {
            MaxPlayersConfig.applyAtBoot();
            MaxPlayersBypass.register(owner);
        });

        // Off-thread command work: /speedtest, /update, /ping geoip.
        stage("virtual executor", () -> {
            VirtualExecutor.init();
        });

        stage("auto updater", () -> {
            AutoUpdateSettings.startUpdater();
        });

        // MXBean collection counts and elapsed collection time, including concurrent GC work.
        // These are not stop-the-world pause measurements.
        stage("gc tracker", () -> {
            dev.iyanz.sourbycraft.perf.GcTracker.start();
        });

        bootStages = java.util.List.copyOf(progress);
        java.util.logging.Logger.getLogger("Aurora Engine")
            .info(dev.iyanz.sourbycraft.brand.AuroraBoot.summary(
                TOTAL_STAGES, failureCount, (System.nanoTime() - begun) / 1_000_000L));
        progress.clear();
        // Build 47 lifecycle truth: a partial boot is operational but DEGRADED, not healthy.
        // FAILED is reserved for a bootstrap where no stage came up.
        AuroraRuntime.transition(failureCount >= TOTAL_STAGES
            ? AuroraRuntime.State.FAILED
            : failureCount > 0 ? AuroraRuntime.State.DEGRADED : AuroraRuntime.State.RUNNING);
    }

    /** Stages the engine brings up, in order; the denominator of the boot bar. */
    private static final int TOTAL_STAGES = 14;
    private static int stageCount;
    private static int failureCount;
    private static final java.util.List<dev.iyanz.sourbycraft.brand.AuroraBoot.Stage> progress = new java.util.ArrayList<>();
    private static volatile java.util.List<dev.iyanz.sourbycraft.brand.AuroraBoot.Stage> bootStages = java.util.List.of();

    /** Completed bootstrap attempts, not a claim about Minecraft/plugin readiness. */
    public static java.util.List<dev.iyanz.sourbycraft.brand.AuroraBoot.Stage> bootStages() { return bootStages; }

    /**
     * Runs one boot stage and reports it.
     *
     * <p>A stage that throws is counted, logged and stepped past: none of these are load-bearing
     * enough to abort a server start, and a half-started engine that says which part is missing is
     * more useful than one that refuses to boot. The bar turns critical from the first failure, so
     * the progress line cannot read healthy while something is broken.</p>
     */
    private static void stage(final String name, final Runnable body) {
        java.util.logging.Logger.getLogger("Aurora Engine").info("START " + (stageCount + 1) + "/" + TOTAL_STAGES + "  " + name);
        final long stageBegun = System.nanoTime();
        boolean successful = true;
        try {
            body.run();
        } catch (final Throwable failure) {
            failureCount++;
            successful = false;
            SourbyLogger.error(name + " failed during Aurora boot", failure);
        }
        dev.iyanz.sourbycraft.startup.StartupTimeline.phase("boot:" + name, System.nanoTime() - stageBegun);
        stageCount++;
        var result = new dev.iyanz.sourbycraft.brand.AuroraBoot.Stage(name, successful,
            (System.nanoTime() - stageBegun) / 1_000_000L);
        progress.add(result);
        java.util.logging.Logger.getLogger("Aurora Engine").info(dev.iyanz.sourbycraft.brand.AuroraBoot.render(
            stageCount, TOTAL_STAGES, result, failureCount, dev.iyanz.sourbycraft.brand.ConsoleStyle.current()));
    }
}
