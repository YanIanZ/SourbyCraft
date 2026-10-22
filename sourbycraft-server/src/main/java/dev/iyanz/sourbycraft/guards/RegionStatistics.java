package dev.iyanz.sourbycraft.guards;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * World statistics that the base answered with "Unsupported in region threading" because a world
 * has no single list to count. A region publishes its own count at the end of each of its ticks
 * ({@code TickRegions.RegionStats}); the world's figure is the sum over its regions. It is a
 * statistic: it is as old as each region's last tick and regions that split or merge mid-sum can
 * be counted twice or missed for one reading, so it is for monitoring, not for logic.
 */
public final class RegionStatistics {

    private RegionStatistics() {
    }

    /** Engine glue: tickable block entities of a world, summed over its regions (no lock, no thread hop). */
    public static int tickableBlockEntities(final net.minecraft.server.level.ServerLevel level) {
        return sum(counts -> level.regioniser.computeForAllRegionsUnsynchronised(
            region -> counts.accept(region.getData().getRegionStats().getTickableBlockEntityCount())));
    }

    /** Sums the values {@code regions} feeds to the consumer it is given. */
    public static int sum(final Consumer<IntConsumer> regions) {
        final int[] total = {0};
        regions.accept(count -> total[0] += count);
        return total[0];
    }
}
