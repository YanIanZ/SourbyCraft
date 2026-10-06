package dev.iyanz.sourbycraft.awf.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.api.world.WorldOperationBusyException;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class WorldSaveBarrierTest {
    @Test
    void regionFailureKeepsLifecycleAdmissionUntilEveryRegionFinishes() {
        final WorldSaveBarrier barrier = new WorldSaveBarrier();
        final WorldOperationGate gate = new WorldOperationGate();
        final var world = new WorldOperationGate.Resource("world:survival", false);
        final var save = gate.run("save", List.of(world), barrier::completion);
        barrier.admitted();
        barrier.admitted();
        barrier.finished(); // fan-out completed
        final RuntimeException failed = new RuntimeException("region save failed");
        barrier.failed(failed);
        assertFalse(save.isDone(), "failure callback is followed by the region's finally callback");
        barrier.finished(); // failed region's finally callback
        assertFalse(save.isDone(), "another admitted region is still saving");
        assertThrows(WorldOperationBusyException.class, () -> gate.acquire("unload", List.of(world)));
        barrier.finished();
        assertSame(failed, assertThrows(CompletionException.class, save::join).getCause());
        gate.acquire("unload", List.of(world)).close();
    }

    @Test
    void rejectedTicketAndFanoutFailureKeepTheFirstCauseAndDrainAdmittedWork() {
        final WorldSaveBarrier barrier = new WorldSaveBarrier();
        barrier.admitted(); // successful propagation
        barrier.admitted(); // propagation rejected
        final RuntimeException first = new RuntimeException("ticket already present");
        barrier.failed(first);
        barrier.finished(); // rejected propagation balanced locally
        barrier.failed(new RuntimeException("fan-out failed"));
        barrier.finished(); // fan-out completion
        assertFalse(barrier.completion().isDone());
        barrier.finished(); // admitted region completion
        assertSame(first, assertThrows(CompletionException.class, () -> barrier.completion().join()).getCause());
    }

    @Test
    void emptyWorldAndSuccessfulRegionsCompleteOnlyAfterFanout() {
        final WorldSaveBarrier empty = new WorldSaveBarrier();
        empty.finished();
        assertTrue(empty.completion().isDone());
        assertFalse(empty.completion().isCompletedExceptionally());
        final WorldSaveBarrier regions = new WorldSaveBarrier();
        regions.admitted();
        regions.finished(); // region can finish while fan-out is still running
        assertFalse(regions.completion().isDone());
        regions.finished();
        assertDoesNotThrow(() -> regions.completion().join());
    }
}
