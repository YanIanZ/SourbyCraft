package dev.iyanz.sourbycraft.perf.sensor;

/**
 * Minimal Folia-side stub of the SourbyCraft perf sensor.
 *
 * <p>The self-tuning perf engine (sensor tick loop, tier classifier,
 * {@code SelfTuneController}, {@code TierBossBar}) has not been ported to the
 * Folia base. Nothing ticks the sensor here, so it reports the "not running"
 * state: no snapshot, disabled, no warmup. {@code /perf} handles a {@code null}
 * snapshot gracefully (GREEN tier, no signal lines), so the command still runs
 * and honestly shows the engine as inactive on this build.
 *
 * <p>Replace with the full port when the perf engine lands on Folia.
 */
public final class PerfSensor {

    /** No sensor loop runs on Folia yet — no snapshot has ever been published. */
    public static SensorSnapshot snapshot() {
        return null;
    }

    /** The perf engine is not wired on the Folia base. */
    public static boolean isEnabled() {
        return false;
    }

    /** No warmup because the sensor never starts. */
    public static int warmupRemainingTicks() {
        return 0;
    }

    private PerfSensor() {}
}
