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
     * The configured tick rate in hertz, against which TPS is judged.
     *
     * @return ticks per second the engine is aiming for
     */
    double tickRateHz();
}
