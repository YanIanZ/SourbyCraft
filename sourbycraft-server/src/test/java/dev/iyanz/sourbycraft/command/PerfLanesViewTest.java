package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.execution.ExecutionLane;
import dev.iyanz.sourbycraft.execution.LaneCpuSampler.LaneLoad;
import dev.iyanz.sourbycraft.execution.LaneCpuSampler.LaneLoads;
import dev.iyanz.sourbycraft.execution.LanePortions;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PerfLanesViewTest {

    @Test
    void anIdleMachineIsNotDescribedAsSaturatedNoMatterHowBusyOneLaneIs() {
        // The state this server is actually in at ten players, and the one where adding threads
        // to the busy lane looks obvious and measures worse.
        final LanePortions.Report report = LanePortions.of(loads(
            new LaneLoad(ExecutionLane.CHUNK_WORKER, 2, 1.06),
            new LaneLoad(ExecutionLane.REGION_TICK, 4, 0.21)), 8);

        assertTrue(report.isConcentrated());
        assertFalse(report.isSaturated());
        assertEquals(ExecutionLane.CHUNK_WORKER, report.dominant());
    }

    @Test
    void anUnavailableReadingSaysWhyRatherThanShowingAnIdleServer() {
        final LanePortions.Report report = LanePortions.notMeasured("metrics are not running");

        assertFalse(report.available());
        assertEquals("metrics are not running", report.reason());
        assertEquals(List.of(), report.portions());
        assertNull(report.dominant());
        assertFalse(report.isSaturated());
        assertFalse(report.isConcentrated());
    }

    @Test
    void lanesIsAcceptedAndOffered() {
        // VIEWS drives both argument validation and tab completion, so a view missing from it is
        // one an operator cannot reach even though the renderer handles it.
        assertTrue(PerfCommand.VIEWS.contains("lanes"));
    }

    private static LaneLoads loads(final LaneLoad... lanes) {
        double total = 0.0;
        for (final LaneLoad lane : lanes) {
            total += lane.cores();
        }
        return new LaneLoads(true, "", 1_000_000_000L, List.of(lanes), total);
    }
}
