package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RegionStatisticsTest {

    @Test
    void theWorldFigureIsTheSumOverItsRegions() {
        assertEquals(0, RegionStatistics.sum(counts -> { }), "no regions, no tickers");
        assertEquals(12, RegionStatistics.sum(counts -> {
            counts.accept(5);
            counts.accept(0);
            counts.accept(7);
        }));
    }
}
