package dev.iyanz.sourbycraft.io;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class ChunkSaveBatcherTest {

    private ExecutorService exec;
    private ConcurrentLinkedQueue<ChunkSaveSnapshot> recorded;
    private ChunkSaveBatcher batcher;

    @BeforeEach
    void setUp() {
        exec = Executors.newVirtualThreadPerTaskExecutor();
        recorded = new ConcurrentLinkedQueue<>();
        batcher = new ChunkSaveBatcher(
            exec,
            /* batchWindowMs */ 50,
            /* queueCap */ 16,
            /* circuitThreshold */ 3,
            /* circuitCooldownMs */ 30_000,
            /* watchdogMultiplier */ 5.0,
            snapshot -> {
                recorded.add(snapshot);
                return ChunkSaveDiff.success(snapshot.worldId(), snapshot.regionX(), snapshot.regionZ(),
                    snapshot.entries().size());
            }
        );
    }

    @AfterEach
    void tearDown() {
        batcher.shutdown();
        exec.shutdownNow();
    }

    @Test
    void coalescesWritesWithinWindow() throws Exception {
        batcher.enqueue("world", 1, 0, 0, 0, new byte[]{1});
        batcher.enqueue("world", 1, 0, 1, 0, new byte[]{2});
        batcher.enqueue("world", 1, 0, 2, 0, new byte[]{3});
        Thread.sleep(300);
        assertEquals(1, recorded.size(), "Three writes to same region should batch into one snapshot");
        assertEquals(3, recorded.peek().entries().size());
    }

    @Test
    void differentRegionsBatchSeparately() throws Exception {
        batcher.enqueue("world", 1, 0, 0, 0, new byte[]{1});
        batcher.enqueue("world", 1, 1, 0, 0, new byte[]{2});
        Thread.sleep(300);
        assertEquals(2, recorded.size());
    }

    @Test
    void drainProducesDiffs() throws Exception {
        batcher.enqueue("world", 1, 0, 0, 0, new byte[]{1});
        Thread.sleep(300);
        List<ChunkSaveDiff> diffs = new java.util.ArrayList<>();
        batcher.drainDiffs(diffs::add);
        assertEquals(1, diffs.size());
        assertTrue(diffs.get(0).success());
    }
}
