package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerformanceHistoryTest {

    private static PerformanceHistory.Sample at(final long millis, final double tps) {
        return new PerformanceHistory.Sample(millis, tps, 50.0 / tps, 10, 1, 2);
    }

    @Test
    void keepsTheFirstSampleOfEachMinute() {
        final PerformanceHistory history = new PerformanceHistory();
        assertTrue(history.offer(at(60_000, 20)));
        assertFalse(history.offer(at(61_000, 19)), "same minute");
        assertTrue(history.offer(at(120_500, 18)));
        assertEquals(2, history.samples().size());
        assertEquals(20, history.samples().get(0).worstTps());
    }

    @Test
    void theRingIsBoundedAndKeepsTheNewest() {
        final PerformanceHistory history = new PerformanceHistory();
        for (int m = 0; m < PerformanceHistory.CAPACITY + 25; m++) history.offer(at(m * 60_000L, m));
        final var samples = history.samples();
        assertEquals(PerformanceHistory.CAPACITY, samples.size());
        assertEquals(25, samples.get(0).worstTps(), "oldest kept");
        assertEquals(PerformanceHistory.CAPACITY + 24, samples.get(samples.size() - 1).worstTps(), "newest last");
    }
}
