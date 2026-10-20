package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.execution.ResourceGovernor;
import org.junit.jupiter.api.Test;

/** The live incident readings count each refusal once. */
class LiveIncidentSourcesTest {

    @Test
    void pathfindingLaneRejectionsAreNotCountedTwice() {
        // A refused solve is already the async path's `refused` reading; the same refusal on the
        // governed lane must not raise a second, lane-rejection incident.
        AsyncPathProcessor.ensureStarted();
        try {
            final IncidentDetector.External external = new LiveIncidentSources().read();
            assertTrue(external.laneRejections().stream()
                    .noneMatch(c -> c.subject().equals(ResourceGovernor.Lane.PATHFINDING.display())),
                external.laneRejections().toString());
            assertTrue(external.asyncPathRefused() >= 0L);
        } finally {
            AsyncPathProcessor.shutdown();
        }
    }
}
