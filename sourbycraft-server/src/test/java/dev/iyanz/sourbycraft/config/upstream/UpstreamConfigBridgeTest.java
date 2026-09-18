package dev.iyanz.sourbycraft.config.upstream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The reload path must survive an engine that cannot re-read its own configuration. */
public class UpstreamConfigBridgeTest {

    @Test
    void aBridgeReportsFailuresRatherThanThrowing() {
        // A configuration file the engine cannot re-read must not abort the SourbyCraft reload
        // around it, and the previous values stay in force.
        final UpstreamConfigBridge broken = new UpstreamConfigBridge() {
            @Override public String name() { return "Fixture"; }
            @Override public List<String> reload() { return List.of("part one failed"); }
        };
        assertEquals(List.of("part one failed"), broken.reload());
        assertEquals("Fixture", broken.name());
    }

    @Test
    void aHealthyBridgeReportsNothing() {
        final UpstreamConfigBridge healthy = new UpstreamConfigBridge() {
            @Override public String name() { return "Fixture"; }
            @Override public List<String> reload() { return List.of(); }
        };
        assertTrue(healthy.reload().isEmpty(), "silence is success");
    }

    @Test
    void theCanvasBridgeNamesWhatItReloads() {
        // The name reaches the operator's log line, so it has to say which engine failed.
        assertEquals("Canvas", new CanvasConfigBridge().name());
    }
}
