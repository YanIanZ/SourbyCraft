package dev.iyanz.sourbycraft.execution;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.execution.LaneCpuSampler.LaneLoad;
import dev.iyanz.sourbycraft.execution.LaneCpuSampler.LaneLoads;
import java.util.List;
import org.junit.jupiter.api.Test;

public class LanePortionsTest {

    @Test
    void portionsDivideWhatWasUsedAndNameWhatWasNot() {
        // The measured shape on an eight-core box at ten players: one lane doing nearly
        // everything while most of the machine is untouched.
        final LanePortions.Report report = LanePortions.of(loads(
            new LaneLoad(ExecutionLane.CHUNK_WORKER, 2, 1.06),
            new LaneLoad(ExecutionLane.REGION_TICK, 4, 0.21),
            new LaneLoad(ExecutionLane.NETWORK, 4, 0.02)), 8);

        assertTrue(report.available());
        assertEquals(1.29, report.usedCores(), 1.0E-9);
        assertEquals(6.71, report.idleCores(), 1.0E-9);
        assertEquals(ExecutionLane.CHUNK_WORKER, report.dominant());
        assertEquals(0.82, report.portions().get(0).shareOfUsed(), 0.01);
        assertEquals(0.13, report.portions().get(0).shareOfMachine(), 0.01);
    }

    @Test
    void oneLaneHoldingMostOfTheWorkIsCalledConcentrated() {
        // Concentration is what makes a lane the thing to fix. Spreading work that is already
        // spread does nothing.
        assertTrue(LanePortions.of(loads(
            new LaneLoad(ExecutionLane.CHUNK_WORKER, 2, 1.06),
            new LaneLoad(ExecutionLane.REGION_TICK, 4, 0.21)), 8).isConcentrated());

        assertFalse(LanePortions.of(loads(
            new LaneLoad(ExecutionLane.CHUNK_WORKER, 2, 0.5),
            new LaneLoad(ExecutionLane.REGION_TICK, 4, 0.5),
            new LaneLoad(ExecutionLane.NETWORK, 4, 0.4)), 8).isConcentrated());
    }

    @Test
    void anIdleMachineIsNotSaturatedHoweverBusyItsBusiestLaneIs() {
        // The distinction the chunk-worker A/B turned on: a lane can be the whole of what the
        // server used while the machine still has most of itself spare, and in that state
        // redistributing threads is not what is wrong.
        final LanePortions.Report report = LanePortions.of(loads(
            new LaneLoad(ExecutionLane.CHUNK_WORKER, 2, 1.27)), 8);

        assertTrue(report.isConcentrated());
        assertFalse(report.isSaturated());
        assertEquals(6.73, report.idleCores(), 1.0E-9);
    }

    @Test
    void aSpentMachineSaysSo() {
        final LanePortions.Report report = LanePortions.of(loads(
            new LaneLoad(ExecutionLane.CHUNK_WORKER, 4, 4.0),
            new LaneLoad(ExecutionLane.REGION_TICK, 4, 3.8)), 8);

        assertTrue(report.isSaturated());
        assertEquals(0.2, report.idleCores(), 1.0E-9);
    }

    @Test
    void idleCoresNeverGoNegativeWhenTheProcessOutrunsItsCoreCount() {
        // Process CPU can exceed the core count over a sampling interval. Reporting -0.4 idle
        // cores would be arithmetic leaking into a number an operator reads.
        assertEquals(0.0, LanePortions.of(loads(
            new LaneLoad(ExecutionLane.CHUNK_WORKER, 4, 4.4)), 4).idleCores(), 1.0E-9);
    }

    @Test
    void aLaneThatUsedNoneOfNoCpuHasNoShareRatherThanZero() {
        // Zero percent would read as "idle while the others worked". Nothing worked.
        final LanePortions.Report report = LanePortions.of(loads(
            new LaneLoad(ExecutionLane.REGION_TICK, 4, 0.0)), 8);

        assertTrue(Double.isNaN(report.portions().get(0).shareOfUsed()));
        assertEquals(0.0, report.portions().get(0).shareOfMachine(), 1.0E-9);
    }

    @Test
    void anUnusableReadingStaysUnusableRatherThanBecomingZeros() {
        final LanePortions.Report report =
            LanePortions.of(LaneLoads.unavailable("the first sample establishes the baseline"), 8);

        assertFalse(report.available());
        assertTrue(report.reason().contains("baseline"));
        assertTrue(Double.isNaN(report.usedCores()));
        assertNull(report.dominant());
        assertFalse(report.isSaturated());
    }

    @Test
    void aMachineReportingNoCoresIsRefusedRatherThanDividedBy() {
        assertFalse(LanePortions.of(loads(new LaneLoad(ExecutionLane.REGION_TICK, 1, 1.0)), 0).available());
    }

    private static LaneLoads loads(final LaneLoad... lanes) {
        double total = 0.0;
        for (final LaneLoad lane : lanes) {
            total += lane.cores();
        }
        return new LaneLoads(true, "", 1_000_000_000L, List.of(lanes), total);
    }
}
