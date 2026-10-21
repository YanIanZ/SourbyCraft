package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.papermc.paper.threadedregions.TickRegions;
import org.junit.jupiter.api.Test;

class RegionStatsPublishTest {

    @Test
    void theTickerCountFollowsWhatTheRegionPublishesWithNoEntityOrChunkChange() {
        final TickRegions.RegionStats stats = new TickRegions.RegionStats();
        assertEquals(0, stats.getTickableBlockEntityCount());
        stats.publishTickableBlockEntityCount(7);
        assertEquals(7, stats.getTickableBlockEntityCount());
        stats.publishTickableBlockEntityCount(6); // a ticker removed while the region stood still
        assertEquals(6, stats.getTickableBlockEntityCount());
        assertEquals(0, stats.getEntityCount(), "the other statistics are untouched");
        assertEquals(0, stats.getChunkCount());
    }
}
