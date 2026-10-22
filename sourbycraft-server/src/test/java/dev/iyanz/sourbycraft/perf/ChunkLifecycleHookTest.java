package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.spottedleaf.moonrise.paper.PaperHooks;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;

/**
 * Minecraft feature patch 0051: every chunk-system lifecycle callback of
 * {@code BaseChunkSystemHooks} counts its transition into {@link ChunkLifecycleCounters#GLOBAL}
 * first, before the upstream body. No world exists here, so each upstream body fails on its null
 * level/chunk right after the count; that failure is expected and is what proves the order.
 */
class ChunkLifecycleHookTest {

    private static void assertCounts(final String name, final Consumer<PaperHooks> callback,
                                     final ToLongFunction<ChunkLifecycleCounters.Snapshot> counter) {
        final PaperHooks hooks = new PaperHooks();
        final long before = counter.applyAsLong(ChunkLifecycleCounters.GLOBAL.snapshot());
        assertThrows(NullPointerException.class, () -> callback.accept(hooks), name);
        assertEquals(before + 1, counter.applyAsLong(ChunkLifecycleCounters.GLOBAL.snapshot()), name);
    }

    @Test
    void holderCreationAndDeletionAreCounted() {
        assertCounts("create", h -> h.onChunkHolderCreate(null, null), ChunkLifecycleCounters.Snapshot::holdersCreated);
        assertCounts("delete", h -> h.onChunkHolderDelete(null, null), ChunkLifecycleCounters.Snapshot::holdersDeleted);
    }

    @Test
    void fullLoadAndUnloadAreCounted() {
        assertCounts("border", h -> h.onChunkBorder(null, null), ChunkLifecycleCounters.Snapshot::fullLoads);
        assertCounts("not border", h -> h.onChunkNotBorder(null, null), ChunkLifecycleCounters.Snapshot::fullUnloads);
    }

    @Test
    void tickingTransitionsAreCounted() {
        assertCounts("ticking", h -> h.onChunkTicking(null, null), ChunkLifecycleCounters.Snapshot::tickingStarts);
        assertCounts("not ticking", h -> h.onChunkNotTicking(null, null), ChunkLifecycleCounters.Snapshot::tickingStops);
        assertCounts("entity ticking", h -> h.onChunkEntityTicking(null, null), ChunkLifecycleCounters.Snapshot::entityTickingStarts);
        assertCounts("not entity ticking", h -> h.onChunkNotEntityTicking(null, null), ChunkLifecycleCounters.Snapshot::entityTickingStops);
    }
}
