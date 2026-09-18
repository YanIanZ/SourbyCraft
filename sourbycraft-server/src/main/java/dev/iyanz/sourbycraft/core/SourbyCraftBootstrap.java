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
 * SourbyCraft's single boot hook, and where the Aurora engine is brought up stage by stage.
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
 * <p>Deliberately minimal: this brings up Aurora and SourbyCraft's own services. The self-tuning perf-engine,
 * anti-xray raytrace reveal and proxy-forwarding/hardening-advisor security layer that the archived
 * {@code PerfEngineBootstrap} also wired are DEFERRED on this benchmark build — see the PR #12 task
 * brief. Every step is wrapped so a single failure can never abort boot.
 *
 * <p>Build 44 retires automatic JVM/OS memory tuning. Diagnostics remain read-only.
 */
public final class SourbyCraftBootstrap {

    private static volatile boolean started = false;

    private SourbyCraftBootstrap() {}

    /** Server stop hook after plugin disable; each optional service has isolated cleanup. */
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
        AuroraRuntime.transition(AuroraRuntime.State.STOPPED);
    }

    /**
     * Runs every utility-layer boot step in order (config, ViaVersion config seeding, startup
     * banner, plugin-load diagnostics, command registration, join/leave messages, max-players,
     * the virtual-thread executor, the auto-updater, GC telemetry). Idempotent — a second
     * call is a no-op. Each step is individually wrapped so one failure never aborts the rest or the
     * server boot.
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

        // The unified TOML: messages, /maxp, auto-updater settings.
        stage("configuration", () -> {
            SourbyCraftConfig.init();
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

        // GC pauses are invisible in TPS/MSPT, so this rolling-window sampler is the only source
        // for collections/min and GC-time%. One daemon thread; never throws.
        stage("gc tracker", () -> {
            dev.iyanz.sourbycraft.perf.GcTracker.start();
        });

        progress.add(dev.iyanz.sourbycraft.brand.AuroraBoot.summary(
            TOTAL_STAGES, failureCount, (System.nanoTime() - begun) / 1_000_000L));
        // One event, so nothing can appear between the lines. Logged through a logger the console
        // renders as the engine rather than as SourbyCraft: this is the engine coming up.
        java.util.logging.Logger.getLogger("Aurora Engine")
            .info(String.join(System.lineSeparator(), progress));
        progress.clear();
        // FAILED only when nothing came up. A stage or two failing leaves a server that runs and
        // says which part is missing, which is more useful than refusing to start.
        AuroraRuntime.transition(failureCount >= TOTAL_STAGES
            ? AuroraRuntime.State.FAILED : AuroraRuntime.State.RUNNING);
    }

    /** Stages the engine brings up, in order; the denominator of the boot bar. */
    private static final int TOTAL_STAGES = 11;
    private static int stageCount;
    private static int failureCount;
    private static final java.util.List<String> progress = new java.util.ArrayList<>();

    /**
     * Runs one boot stage and reports it.
     *
     * <p>A stage that throws is counted, logged and stepped past: none of these are load-bearing
     * enough to abort a server start, and a half-started engine that says which part is missing is
     * more useful than one that refuses to boot. The bar turns critical from the first failure, so
     * the progress line cannot read healthy while something is broken.</p>
     */
    private static void stage(final String name, final Runnable body) {
        try {
            body.run();
        } catch (final Throwable failure) {
            failureCount++;
            SourbyLogger.error(name + " failed during Aurora boot", failure);
        }
        stageCount++;
        // Held, not printed. Stages log their own output as they run -- the virtual executor and
        // the command registry both announce themselves -- so emitting a bar line per stage
        // interleaved the bar with those lines and neither read well. The whole bar goes out as
        // one event below, before the server finishes starting.
        progress.add(dev.iyanz.sourbycraft.brand.AuroraBoot.render(
            stageCount, TOTAL_STAGES, name, failureCount > 0));
    }
}
