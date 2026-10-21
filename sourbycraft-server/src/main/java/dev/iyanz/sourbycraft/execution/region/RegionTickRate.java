package dev.iyanz.sourbycraft.execution.region;

/**
 * The engine's server-wide tick rate, read and set. Every region and the global tick are scheduled
 * against the same rate, so there is one value, not one per region. {@link FoliaRegionBackend}
 * supplies it.
 */
public interface RegionTickRate {

    /** The tick rate every region aims for, in ticks per second. */
    float currentTickRate();

    /**
     * Sets the tick rate every region aims for. Takes effect from each region's next tick; players
     * are sent the new rate.
     *
     * @param hz ticks per second; the caller validates the range
     */
    void setTickRate(float hz);
}
