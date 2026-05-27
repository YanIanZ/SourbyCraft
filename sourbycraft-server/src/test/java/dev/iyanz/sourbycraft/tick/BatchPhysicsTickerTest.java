package dev.iyanz.sourbycraft.tick;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import net.minecraft.world.entity.Entity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BatchPhysicsTickerTest {

    private ExecutorService exec;
    private BatchPhysicsTicker ticker;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        exec = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (ticker != null) ticker.shutdown();
        if (exec != null) exec.shutdownNow();
    }

    @Test
    void physicsPhaseInvokedPerEntity() throws Exception {
        AtomicInteger physicsCalls = new AtomicInteger();
        ticker = new BatchPhysicsTicker(exec, 64, 3, 30_000, 5.0,
            e -> { physicsCalls.incrementAndGet(); return true; });
        Entity a = Mockito.mock(Entity.class);
        Entity b = Mockito.mock(Entity.class);
        ticker.submit(new ParallelTickSnapshot(a, EntityClass.ITEM));
        ticker.submit(new ParallelTickSnapshot(b, EntityClass.EXP_ORB));
        Thread.sleep(200);
        assertEquals(2, physicsCalls.get());
    }

    @Test
    void diffsCarryNeedsInteractFlag() throws Exception {
        ticker = new BatchPhysicsTicker(exec, 64, 3, 30_000, 5.0, e -> true);
        Entity a = Mockito.mock(Entity.class);
        ticker.submit(new ParallelTickSnapshot(a, EntityClass.ITEM));
        Thread.sleep(200);
        List<ParallelTickDiff> diffs = new ArrayList<>();
        ticker.drainDiffs(diffs::add);
        assertEquals(1, diffs.size());
        assertTrue(diffs.get(0).needsInteract());
    }

    @Test
    void physicsPhaseFalseProducesDoneDiff() throws Exception {
        ticker = new BatchPhysicsTicker(exec, 64, 3, 30_000, 5.0, e -> false);
        Entity a = Mockito.mock(Entity.class);
        ticker.submit(new ParallelTickSnapshot(a, EntityClass.ITEM));
        Thread.sleep(200);
        List<ParallelTickDiff> diffs = new ArrayList<>();
        ticker.drainDiffs(diffs::add);
        assertFalse(diffs.get(0).needsInteract());
    }
}
