package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkLifecycleCountersTest {

    @Test
    void eachTransitionCountsOnItsOwnAndTheCurrentLevelsAreInMinusOut() {
        final ChunkLifecycleCounters counters = new ChunkLifecycleCounters();
        for (int i = 0; i < 5; i++) counters.record(ChunkLifecycleCounters.Transition.HOLDER_CREATED);
        for (int i = 0; i < 2; i++) counters.record(ChunkLifecycleCounters.Transition.HOLDER_DELETED);
        for (int i = 0; i < 4; i++) counters.record(ChunkLifecycleCounters.Transition.FULL);
        counters.record(ChunkLifecycleCounters.Transition.NOT_FULL);
        for (int i = 0; i < 3; i++) counters.record(ChunkLifecycleCounters.Transition.TICKING);
        counters.record(ChunkLifecycleCounters.Transition.ENTITY_TICKING);
        counters.record(ChunkLifecycleCounters.Transition.ENTITY_TICKING);
        counters.record(ChunkLifecycleCounters.Transition.NOT_ENTITY_TICKING);

        final ChunkLifecycleCounters.Snapshot snapshot = counters.snapshot();
        assertEquals(5, snapshot.holdersCreated());
        assertEquals(2, snapshot.holdersDeleted());
        assertEquals(4, snapshot.fullLoads());
        assertEquals(1, snapshot.fullUnloads());
        assertEquals(3, snapshot.tickingStarts());
        assertEquals(0, snapshot.tickingStops());
        assertEquals(2, snapshot.entityTickingStarts());
        assertEquals(1, snapshot.entityTickingStops());
        assertEquals(3, snapshot.holders());
        assertEquals(3, snapshot.fullChunks());
        assertEquals(3, snapshot.tickingChunks());
        assertEquals(1, snapshot.entityTickingChunks());
    }

    @Test
    void aFreshCounterIsAllZero() {
        final ChunkLifecycleCounters.Snapshot snapshot = new ChunkLifecycleCounters().snapshot();
        assertEquals(new ChunkLifecycleCounters.Snapshot(0, 0, 0, 0, 0, 0, 0, 0), snapshot);
    }
}
