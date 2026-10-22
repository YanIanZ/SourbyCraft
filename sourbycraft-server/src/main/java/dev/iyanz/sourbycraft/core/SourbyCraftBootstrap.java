package dev.iyanz.sourbycraft.core;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.bootstrap.PluginProvisioner;
import dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics;
import dev.iyanz.sourbycraft.command.SourbyCraftCommands;
import dev.iyanz.sourbycraft.lang.SourbyJoinLeaveListener;
import dev.iyanz.sourbycraft.lang.SourbyMotd;
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
 * <p>Individual stage failures are logged and isolated. Completion publishes RUNNING when every
 * stage succeeded, DEGRADED when some failed, and FAILED only when all of them failed (see
 * {@link #verdict}). Diagnostics do not tune JVM options or operator configuration.</p>
 *
 * <p>The server owns the call order: startup precedes shutdown. Synchronization prevents duplicate
 * initialization; it does not serialize {@link #close()} against initialization.</p>
 */
public final class SourbyCraftBootstrap {

    private static volatile boolean started = false;

    /** The engine's console tag: the boot summary, failed-stage warnings and the thread ledger print as {@code Aurora}. */
    static final java.util.logging.Logger AURORA = java.util.logging.Logger.getLogger("Aurora");
    /** Same logger through the log4j API, for real DEBUG events. */
    private static final org.apache.logging.log4j.Logger AURORA_DEBUG = org.apache.logging.log4j.LogManager.getLogger("Aurora");

    /** A stage running longer than this gets one "still running" WARN (the watchdog covers longer hangs). */
    private static volatile long slowStageMillis = 10_000L;
    private static volatile java.util.function.Consumer<String> slowStageSink = AURORA::warning;

    /** Test seam: replaces the slow-stage threshold and sink until closed. */
    static AutoCloseable slowStageWarningForTesting(final long millis, final java.util.function.Consumer<String> sink) {
        final long previousMillis = slowStageMillis;
        final java.util.function.Consumer<String> previousSink = slowStageSink;
        slowStageMillis = millis;
        slowStageSink = sink;
        return () -> {
            slowStageMillis = previousMillis;
            slowStageSink = previousSink;
        };
    }

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
        // Before the first AWF wait of the shutdown (the storage lane draining below, then the region
        // saves, the chunk I/O drain and AwfEngine.closeAll): one deadline for all of them.
        try { dev.iyanz.sourbycraft.awf.AwfEngine.beginShutdown(); }
        catch (Throwable failure) { SourbyLogger.error("AWF shutdown deadline could not start", failure); }
        try { dev.iyanz.sourbycraft.hud.HudBars.close(); }
        catch (Throwable failure) { SourbyLogger.error("HUD shutdown failed", failure); }
        try { AutoUpdateSettings.stopUpdater(); }
        catch (Throwable failure) { SourbyLogger.error("Updater shutdown failed", failure); }
        try { dev.iyanz.sourbycraft.diagnostics.StallRecorderRuntime.stop(); }
        catch (Throwable failure) { SourbyLogger.error("Stall recorder shutdown failed", failure); }
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
     * Starts metrics, network counters, configuration, the stall recorder, plugin config provisioning, branding,
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
        dev.iyanz.sourbycraft.brand.ConsoleSections.engine();

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

        // After configuration (reads aurora.diagnostics.stall-recorder.*, RESTART_REQUIRED). Its own daemon
        // thread; evidence for a hung tick on hosts with no shell. Disabled = no thread, no hook.
        stage("stall recorder", () -> {
            dev.iyanz.sourbycraft.diagnostics.StallRecorderRuntime.start(
                SourbyCraftConfig.aurora().diagnostics().stallRecorder());
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
            PluginProvisioner.provisionConfigs(SourbyCraftConfig.cfgBool(dev.iyanz.sourbycraft.config.UtilityKeys.VIAVERSION_AUTO_PROVISION.path(), true));
        });

        // Warning-only JVM flag advice, logged so it reaches logs/latest.log (no stdout banner since Build 49).
        stage("jvm advisor", () -> {
            dev.iyanz.sourbycraft.brand.JvmAdvisor.logOnce();
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

        // Both read messages.*: the join/quit broadcasts, and the server-list MOTD (set in memory only,
        // server.properties is not rewritten; motd = [] keeps the server.properties MOTD).
        stage("messages", () -> {
            SourbyJoinLeaveListener.register(owner);
            SourbyMotd.apply(org.bukkit.Bukkit::motd);
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

        publishBootStages();
        // One line closes the Engine section (scripts/run_baseline.py reads it); WARN when a stage failed.
        final String summary = dev.iyanz.sourbycraft.brand.AuroraBoot.summary(
            bootStages, TOTAL_STAGES, (System.nanoTime() - begun) / 1_000_000L);
        if (failureCount > 0) {
            AURORA.warning(summary);
        } else {
            AURORA.info(summary);
        }
        // Configured thread ceilings per pool against the processors (audit ENG-9). Report only.
        try {
            for (final String line : dev.iyanz.sourbycraft.execution.ThreadLedger.format(
                dev.iyanz.sourbycraft.execution.ThreadLedger.collect(), Runtime.getRuntime().availableProcessors())) {
                AURORA.info(line);
            }
        } catch (final RuntimeException unavailable) {
            SourbyLogger.debug("thread ledger unavailable: " + unavailable);
        }
        // Build 47 lifecycle truth: a partial boot is operational but DEGRADED, not healthy.
        // FAILED is reserved for a bootstrap where no stage came up.
        AuroraRuntime.transition(verdict(failureCount, TOTAL_STAGES));
    }

    /** The lifecycle state a finished bootstrap publishes: none failed RUNNING, some DEGRADED, all FAILED. */
    static AuroraRuntime.State verdict(final int failures, final int totalStages) {
        return failures >= totalStages
            ? AuroraRuntime.State.FAILED
            : failures > 0 ? AuroraRuntime.State.DEGRADED : AuroraRuntime.State.RUNNING;
    }

    /** Makes the stages run so far readable through {@link #bootStages()} and starts the next attempt empty. */
    private static void publishBootStages() {
        bootStages = java.util.List.copyOf(progress);
        progress.clear();
    }

    /**
     * Test seam: clears the stage counters and published stages, and clears them again when closed.
     * It does not touch {@code started} or the {@link AuroraRuntime} state, so {@link #init()}
     * stays unreachable from tests.
     */
    static synchronized AutoCloseable resetStagesForTesting() {
        stageCount = 0;
        failureCount = 0;
        progress.clear();
        bootStages = java.util.List.of();
        return () -> {
            stageCount = 0;
            failureCount = 0;
            progress.clear();
            bootStages = java.util.List.of();
        };
    }

    /** Test seam: what {@link #publishBootStages()} publishes, as {@link #bootStages()} would show it. */
    static synchronized java.util.List<dev.iyanz.sourbycraft.brand.AuroraBoot.Stage> publishBootStagesForTesting() {
        publishBootStages();
        return bootStages;
    }

    /** Test seam: failures counted since the last reset. */
    static synchronized int failureCountForTesting() { return failureCount; }

    /** Stages the engine brings up, in order; the denominator of the boot bar. */
    private static final int TOTAL_STAGES = 15;
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
    static void stage(final String name, final Runnable body) {
        // The console gets no INFO line per stage: the Engine section closes with one summary.
        // START and the per-stage result are log4j DEBUG events (JUL FINE would arrive as TRACE),
        // shown with a log4j config that sets <Logger name="Aurora" level="debug"/>. A failed
        // stage gets a WARN naming it, and a stage still running after SLOW_STAGE_MILLIS gets a
        // WARN naming it, so a failure or a hang is visible without DEBUG.
        AURORA_DEBUG.debug("START {}/{}  {}", stageCount + 1, TOTAL_STAGES, name);
        final long stageBegun = System.nanoTime();
        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.function.Consumer<String> slowSink = slowStageSink;
        final long slowAfter = slowStageMillis;
        final String slowMessage = "Stage " + (stageCount + 1) + "/" + TOTAL_STAGES + " \"" + name
            + "\" is still running after " + slowAfter + " ms";
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            if (!finished.get()) {
                slowSink.accept(slowMessage);
            }
        }, java.util.concurrent.CompletableFuture.delayedExecutor(slowAfter, java.util.concurrent.TimeUnit.MILLISECONDS,
            Runnable::run));
        boolean successful = true;
        try {
            body.run();
        } catch (final Throwable failure) {
            failureCount++;
            successful = false;
            SourbyLogger.error(name + " failed during Aurora boot", failure);
        } finally {
            finished.set(true);
        }
        dev.iyanz.sourbycraft.startup.StartupTimeline.phase("boot:" + name, System.nanoTime() - stageBegun);
        stageCount++;
        var result = new dev.iyanz.sourbycraft.brand.AuroraBoot.Stage(name, successful,
            (System.nanoTime() - stageBegun) / 1_000_000L);
        progress.add(result);
        AURORA_DEBUG.debug(dev.iyanz.sourbycraft.brand.AuroraBoot.stageLine(stageCount, TOTAL_STAGES, result));
        if (!successful) {
            AURORA.warning(dev.iyanz.sourbycraft.brand.AuroraBoot.failureLine(stageCount, TOTAL_STAGES, result));
        }
    }
}
