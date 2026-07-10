package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.io.File;

/**
 * One-shot boot entry point for the SourbyCraft perf-engine on the Folia base (F2b).
 *
 * <p>Invoked once from the authored post-config hook
 * ({@link me.earthme.luminol.commands.CommandRegister#register()}). It:
 *
 * <ol>
 *   <li>Runs {@link SourbyCraftConfig#init(File)} — previously wired but never called on the
 *       Folia base, so the config registry + perf-knob + sensor-config bridges are now live.</li>
 *   <li>Starts the {@link PerfSensor} Folia global-region-scheduler sampler, which drives the
 *       {@link SelfTuneController} on each tier transition.</li>
 * </ol>
 *
 * <p>The Paper tag wired {@code SourbyCraftConfig.init()} into a {@code DedicatedServer} NMS
 * patch and ticked the sensor from {@code MinecraftServer.tickChildren}. Folia has no global
 * tick loop and this port keeps the patch series untouched (authored source only), so both
 * concerns are driven from this authored bootstrap instead.
 *
 * <p><b>Double-start guard.</b> {@link #start()} is guarded by {@link #started} so a second
 * invocation of the boot hook is a no-op. {@link PerfSensor#start()} carries its own guard too.
 * The whole body is wrapped so a failure cannot abort the server boot sequence.
 */
public final class PerfEngineBootstrap {

    private static volatile boolean started = false;

    private PerfEngineBootstrap() {}

    /** Idempotent. Runs config init + starts the sampler. Safe to call from the boot hook. */
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
    }
}
