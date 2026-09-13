package dev.iyanz.sourbycraft.perf;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSamplerTest {
    @Test void unsupportedCpuIsUnavailableInsteadOfNegativePercent() {
        assertTrue(Double.isNaN(RuntimeSampler.percent(-1)));
        assertTrue(Double.isNaN(RuntimeSampler.percent(Double.NaN)));
        assertTrue(Double.isNaN(RuntimeSampler.percent(2)));
        assertEquals(0, RuntimeSampler.percent(0));
        assertEquals(37.5, RuntimeSampler.percent(0.375));
    }

    @Test void rssUsesResidentFieldAndChecksUnitsAndOverflow() {
        assertEquals(102400, RuntimeSampler.parseRss("VmSize: 99999 kB\nVmRSS:\t100 kB\n"));
        for (String invalid : new String[]{"VmSize: 100 kB", "VmRSS: -1 kB", "VmRSS: 30 MB",
            "VmRSS: 9223372036854775807 kB", "VmRSS: nope kB"}) {
            assertEquals(-1, RuntimeSampler.parseRss(invalid));
        }
    }

    @Test void runtimeSnapshotUsesStableJava25Metrics() {
        final var sample = RuntimeSampler.sample();
        assertTrue(sample.heapUsedBytes() >= 0);
        assertTrue(sample.heapCommittedBytes() >= sample.heapUsedBytes());
        assertTrue(sample.nonHeapUsedBytes() >= 0);
        assertTrue(sample.availableProcessors() > 0);
        assertTrue(sample.uptimeMillis() >= 0);
    }

    @Test void gcWorkerStopsAndCanRestart() throws InterruptedException {
        GcTracker.start();
        GcTracker.stop();
        assertFalse(GcTracker.snapshot().hasData());
        assertTrue(Thread.getAllStackTraces().keySet().stream()
            .noneMatch(t -> t.isAlive() && t.getName().equals("SourbyCraft-GcTracker")));
        GcTracker.start();
        GcTracker.stop();
    }
}
