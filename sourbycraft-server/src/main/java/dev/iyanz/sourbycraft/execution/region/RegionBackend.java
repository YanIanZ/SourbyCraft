package dev.iyanz.sourbycraft.execution.region;

import dev.iyanz.sourbycraft.perf.RegionTickMetrics;

/**
 * What the Aurora region system needs from whichever engine is actually scheduling regions.
 *
 * <p>Two questions, both read-only. Everything else SourbyCraft knows about regions — their
 * identity, their merges, their retirement, their tick history — it already tracks itself.</p>
 */
public interface RegionBackend {

    /**
     * The global tick handle's metrics: the work that belongs to no single region.
     *
     * @param nowNanos the reading's timestamp, from the collector's clock
     * @return a snapshot of the global tick's windows
     */
    RegionTickMetrics.Snapshot globalTickMetrics(long nowNanos);

    /**
     * The global tick's live metrics object (not a snapshot), for readers that must not take a monitor.
     * Only its volatile markers ({@link RegionTickMetrics#lastCompletedEndNanos()},
     * {@link RegionTickMetrics#activeTickStartNanos()}) are safe to read from a watchdog.
     *
     * @return the global tick's metrics, or {@code null} before the engine created them
     */
    RegionTickMetrics globalTickMetricsLive();

    /**
     * A class-name fragment that appears in the stack of the thread running the global tick (so a thread
     * dump can name the stalled thread without the engine's thread being exposed).
     *
     * @return the fragment
     */
    String globalTickFrameMarker();

    /**
     * A class-name fragment that appears in the stack of any thread running a region tick or region task
     * (the global tick's thread included).
     *
     * @return the fragment
     */
    String regionTickFrameMarker();

    /**
     * The configured tick rate in hertz, against which TPS is judged.
     *
     * @return ticks per second the engine is aiming for
     */
    double tickRateHz();
}
