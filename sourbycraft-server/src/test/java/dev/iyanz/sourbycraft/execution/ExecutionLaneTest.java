package dev.iyanz.sourbycraft.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * The lane mapping is a guess about thread names, so it is pinned against names a real run
 * produced rather than names that seemed likely — these are from the certified two-hour soak's
 * thread dump.
 */
public class ExecutionLaneTest {

    @Test
    void everyThreadTheCertifiedSoakRanIsAttributed() {
        assertLane(ExecutionLane.REGION_TICK, "Folia Region Scheduler Thread #3");
        assertLane(ExecutionLane.CHUNK_WORKER, "Paper Common Worker #1");
        assertLane(ExecutionLane.BACKGROUND, "Worker-Main-2");
        assertLane(ExecutionLane.WORLD_IO, "Dimension-Data-IO-Worker-1", "SourbyCraft-IO-4");
        assertLane(ExecutionLane.NETWORK, "Netty Kqueue IO #0");
        assertLane(ExecutionLane.PLUGIN_ASYNC,
            "Paper Async Task Handler Thread - 1", "Paper Async Command Builder Thread Pool - 0");
        assertLane(ExecutionLane.ASYNC_COMPUTE, "SourbyCraft-AsyncPath-1");
        assertLane(ExecutionLane.TELEMETRY,
            "SourbyCraft-PerformanceCollector", "spark-async-sampler-worker-2-thread-1");
        assertLane(ExecutionLane.GARBAGE_COLLECTION, "GC Thread#5", "G1 Conc#0");
    }

    @Test
    void theChunkPoolAndTheEngineBackgroundPoolAreDifferentLanes() {
        // Paper Common Worker is Moonrise's WORKER_POOL, sized by Paper.WorkerThreadCount.
        // Worker-Main is Util.backgroundExecutor(), a deprioritised ForkJoinPool sized from the
        // core count that carries whatever the engine hands it. Counting them together made a
        // chunk-worker A/B read four and twelve threads where the setting said two and six.
        assertNotEquals(ExecutionLane.of("Paper Common Worker #0"), ExecutionLane.of("Worker-Main-1"));
    }

    @Test
    void anUnrecognisedThreadIsNotFoldedIntoANeighbour() {
        // A pool that names itself pool-3-thread-1 is invisible to every thread-attributed
        // measurement we take. Reporting it as OTHER is how that stays visible.
        assertLane(ExecutionLane.OTHER, "pool-3-thread-1", "Yggdrasil Key Fetcher", "Timer hack thread");
    }

    @Test
    void aMissingNameDoesNotThrow() {
        assertEquals(ExecutionLane.OTHER, ExecutionLane.of(null));
        assertEquals(ExecutionLane.OTHER, ExecutionLane.of(""));
    }

    @Test
    void gameplayHasExactlyOneLane() {
        // If a second lane ever claims to run gameplay, the region ownership model has been
        // broken somewhere: region-owned state may only be mutated by its owning thread.
        assertEquals(ExecutionLane.REGION_TICK,
            ExecutionLane.of("Folia Region Scheduler Thread #0"));
        assertNotEquals(ExecutionLane.REGION_TICK,
            ExecutionLane.of("Paper Async Task Handler Thread - 1"));
    }

    @Test
    void everyLaneHasADisplayName() {
        for (final ExecutionLane lane : EnumSet.allOf(ExecutionLane.class)) {
            assertNotNull(lane.display());
            assertFalse(lane.display().isBlank(), lane + " has no display name");
        }
    }

    private static void assertLane(final ExecutionLane expected, final String... threadNames) {
        for (final String name : threadNames) {
            assertEquals(expected, ExecutionLane.of(name), name);
        }
    }
}
