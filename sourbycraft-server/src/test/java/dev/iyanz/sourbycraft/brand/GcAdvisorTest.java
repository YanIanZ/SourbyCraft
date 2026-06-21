package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GcAdvisorTest {

    @Test
    void zgcAccepted() {
        var r = GcAdvisor.evaluate(List.of("ZGC Cycles", "ZGC Pauses"), List.of("-XX:+AlwaysPreTouch"), 4096, 4096);
        assertTrue(r.acceptable());
    }

    @Test
    void g1Accepted() {
        var r = GcAdvisor.evaluate(List.of("G1 Young Generation", "G1 Old Generation"), List.of("-XX:+AlwaysPreTouch"), 4096, 4096);
        assertTrue(r.acceptable());
    }

    @Test
    void parallelGcWarns() {
        var r = GcAdvisor.evaluate(List.of("PS Scavenge", "PS MarkSweep"), List.of(), 4096, 4096);
        assertFalse(r.acceptable());
    }

    @Test
    void mismatchedXmsXmxWarns() {
        var r = GcAdvisor.evaluate(List.of("G1 Young Generation"), List.of("-XX:+AlwaysPreTouch"), 2048, 4096);
        assertFalse(r.acceptable());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Xms")));
    }

    @Test
    void missingAlwaysPreTouchWarns() {
        var r = GcAdvisor.evaluate(List.of("G1 Young Generation"), List.of(), 4096, 4096);
        assertFalse(r.acceptable());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("PreTouch")));
    }
}
