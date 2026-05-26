package dev.iyanz.sourbycraft.async;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PoolMetricsTest {

    @Test
    void initialSnapshotIsZeroes() {
        PoolMetrics m = new PoolMetrics();
        PoolMetrics.Snapshot s = m.snapshot();
        assertEquals(0L, s.submitted);
        assertEquals(0L, s.completed);
        assertEquals(0L, s.timedOut);
        assertEquals(0L, s.queueDepthHigh);
        assertEquals(0.0, s.avgLatencyMs);
    }

    @Test
    void completionMovingAverage() {
        PoolMetrics m = new PoolMetrics();
        m.recordSubmit();
        m.recordSubmit();
        m.recordCompletion(10);
        m.recordCompletion(20);
        PoolMetrics.Snapshot s = m.snapshot();
        assertEquals(2L, s.submitted);
        assertEquals(2L, s.completed);
        assertEquals(15.0, s.avgLatencyMs, 0.01);
    }

    @Test
    void queueDepthHighWaterTracks() {
        PoolMetrics m = new PoolMetrics();
        m.observeQueueDepth(5);
        m.observeQueueDepth(12);
        m.observeQueueDepth(8);
        assertEquals(12L, m.snapshot().queueDepthHigh);
    }

    @Test
    void timeoutTracked() {
        PoolMetrics m = new PoolMetrics();
        m.recordTimeout();
        m.recordTimeout();
        assertEquals(2L, m.snapshot().timedOut);
    }
}
