package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RegionIoCountersTest {

    @Test
    void countsReadsWritesAndDeletesSeparately() {
        final RegionIoCounters counters = new RegionIoCounters();
        counters.read();
        counters.read();
        counters.write(false);
        counters.write(true);
        counters.write(true);
        assertEquals(new RegionIoCounters.Totals(2, 1, 2), counters.totals());
    }
}
