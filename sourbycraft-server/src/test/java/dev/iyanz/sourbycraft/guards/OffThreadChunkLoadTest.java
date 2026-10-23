package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.bridge.ViolationClassifier;
import dev.iyanz.sourbycraft.entity.teleport.OwnedTeleportPatchShapeTestAccess;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@code World#getChunkAt(x, z)} from a thread that ticks nothing waits for the chunk as on Paper, bounded; every tick
 * thread keeps the refusal (paper 0028, SuperiorSkyblock2 2026.3 island preparation on the bridge I/O lane).
 */
class OffThreadChunkLoadTest {

    private static final long SHORT = TimeUnit.MILLISECONDS.toNanos(200);

    @Test
    void appliesOnlyToThreadsThatTickNothing() throws Exception {
        assertTrue(OffThreadChunkLoad.applies(), "a plain thread (test runner, bridge I/O lane, plugin executor)");
        final AtomicBoolean onTickThread = new AtomicBoolean(true);
        final ca.spottedleaf.moonrise.common.util.TickThread tick = new ca.spottedleaf.moonrise.common.util.TickThread(() -> onTickThread.set(OffThreadChunkLoad.applies()), "test tick thread");
        tick.start();
        tick.join();
        assertFalse(onTickThread.get(), "a tick thread keeps the base's refusal");
    }

    @Test
    void aLoadedChunkIsReturnedWithoutSchedulingAnything() {
        final Object loaded = new Object();
        final AtomicInteger scheduled = new AtomicInteger();
        assertSame(loaded, OffThreadChunkLoad.await(loaded, cb -> scheduled.incrementAndGet(), "c", SHORT));
        assertEquals(0, scheduled.get());
    }

    @Test
    void anUnloadedChunkIsScheduledOnceAndAwaited() {
        final Object chunk = new Object();
        final AtomicInteger scheduled = new AtomicInteger();
        final Object got = OffThreadChunkLoad.await(null, cb -> {
            scheduled.incrementAndGet();
            final Thread owner = new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                cb.accept(chunk);
            }, "owning region");
            owner.start();
        }, "c", TimeUnit.SECONDS.toNanos(10));
        assertSame(chunk, got);
        assertEquals(1, scheduled.get());
    }

    @Test
    void aLoadThatNeverCompletesFailsClearlyAndIsNotARegionViolation() {
        final IllegalStateException failed = assertThrows(IllegalStateException.class,
            () -> OffThreadChunkLoad.await(null, cb -> { }, "chunk (1, 2) of world 'w'", SHORT));
        assertTrue(failed.getMessage().contains("Timed out"), failed.getMessage());
        assertTrue(failed.getMessage().contains("chunk (1, 2) of world 'w'"), failed.getMessage());
        assertTrue(failed.getMessage().contains("getChunkAtAsync"), failed.getMessage());
        assertFalse(ViolationClassifier.isRegionViolation(failed), "a slow load must not count toward quarantine");
    }

    @Test
    void aChunkThatCouldNotBeLoadedFails() {
        final IllegalStateException failed = assertThrows(IllegalStateException.class,
            () -> OffThreadChunkLoad.await(null, cb -> cb.accept(null), "c", SHORT));
        assertTrue(failed.getMessage().contains("Chunk not loaded"), failed.getMessage());
        assertFalse(ViolationClassifier.isRegionViolation(failed));
    }

    @Test
    void anInterruptedWaitKeepsTheInterruptFlag() throws Exception {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicBoolean flag = new AtomicBoolean();
        final Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                OffThreadChunkLoad.await(null, cb -> { }, "c", TimeUnit.SECONDS.toNanos(30));
            } catch (final Throwable t) {
                thrown.set(t);
                flag.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        waiter.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(thrown.get() instanceof IllegalStateException, String.valueOf(thrown.get()));
        assertTrue(flag.get());
    }

    @Test
    void theTimeoutIsBounded() {
        assertTrue(OffThreadChunkLoad.TIMEOUT_SECONDS > 0 && OffThreadChunkLoad.TIMEOUT_SECONDS <= 60);
    }

    @Test
    void onlyGetChunkAtGainsTheOffThreadBranchAndItsRefusalStays() throws IOException {
        final String patch = OwnedTeleportPatchShapeTestAccess.patch("paper-patches/features",
            "-SourbyCraft-World-getChunkAt-from-a-thread-that-ticks-nothing-waits-for-the-chunk");
        final String world = OwnedTeleportPatchShapeTestAccess.file(patch, "src/main/java/org/bukkit/craftbukkit/CraftWorld.java");
        assertEquals(0, OwnedTeleportPatchShapeTestAccess.removed(world), "the base's refusal line stays for tick threads");
        final int branch = world.indexOf("+        if (dev.iyanz.sourbycraft.guards.OffThreadChunkLoad.applies())");
        final int refusal = world.indexOf("TickThread.ensureTickThread(this.getHandle(), x, z, \"Async chunk retrieval\")");
        assertTrue(branch >= 0, "branch added");
        assertTrue(refusal > branch, "the branch comes before the unchanged refusal");
        assertTrue(world.contains("OffThreadChunkLoad.fullChunk(this.getHandle(), x, z, ca.spottedleaf.concurrentutil.util.Priority.BLOCKING)"));
        assertEquals(1, world.split("OffThreadChunkLoad.applies\\(\\)", -1).length - 1, "one call site: getChunkAt(int, int)");
    }
}
