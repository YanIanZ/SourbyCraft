package dev.iyanz.sourbycraft.execution.region;

import dev.iyanz.sourbycraft.perf.RegionMetricsRegistry;
import java.util.function.Consumer;

/**
 * The regions that exist right now, and the ones recently retired but still worth reading.
 *
 * <p>Retired regions are included on purpose. A region that merged away thirty seconds ago still
 * holds the only record of what those chunks were doing, and dropping it the instant it retires is
 * how a fifteen-minute window ends up with nothing in it.</p>
 */
public final class RegionTopology {

    /**
     * Where regions are read from.
     *
     * <p>A function rather than the registry itself, so the topology is decoupled from how regions
     * happen to be tracked today and can be exercised without standing one up.</p>
     */
    @FunctionalInterface
    public interface Source {
        void forEachUnexpired(long nowNanos, Consumer<RegionMetricsRegistry.GenerationView> consumer);
    }

    private final Source source;

    public RegionTopology(final Source source) {
        this.source = source;
    }

    /** The topology of the regions a registry is tracking. */
    public static RegionTopology of(final RegionMetricsRegistry registry) {
        return new RegionTopology(registry::forEachUnexpired);
    }

    /**
     * Visits every region not yet expired, active or retired.
     *
     * @param nowNanos the reading's timestamp
     * @param consumer receives each region
     */
    public void forEach(final long nowNanos, final Consumer<AuroraRegion> consumer) {
        this.source.forEachUnexpired(nowNanos, view -> consumer.accept(AuroraRegion.of(view)));
    }

    /**
     * How many regions are ticking.
     *
     * <p>Counts active regions only: a retired generation is history, not a thread doing work.</p>
     *
     * @param nowNanos the reading's timestamp
     * @return the number of active regions
     */
    public int activeCount(final long nowNanos) {
        final int[] active = {0};
        this.forEach(nowNanos, region -> {
            if (region.active()) {
                active[0]++;
            }
        });
        return active[0];
    }
}
