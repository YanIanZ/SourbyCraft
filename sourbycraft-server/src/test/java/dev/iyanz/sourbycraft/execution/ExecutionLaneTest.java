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

    // ---- threads that used to land in OTHER (found by the spark thread classification) ----

    @Test
    void theAwfMaintenanceThreadIsAttributedToTheStorageLane() {
        // The driver's own thread, exactly as AwfMaintenanceDriver names it. It starts commits and
        // watches world operations; the I/O itself runs on the SourbyCraft-Storage-* lane, so this is
        // the same subsystem's lane rather than a new one.
        assertEquals("SourbyCraft-AWF-Maintenance", dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver.THREAD_NAME);
        assertLane(ExecutionLane.WORLD_IO, dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver.THREAD_NAME);
    }

    @Test
    void theAggressiveChunkSystemsThreadsAreChunkWorkersAndChunkIo() {
        // MoonriseCommon.setupAggressiveChunkSystem (paper-patches/files/.../MoonriseCommon.java.patch)
        // names its two factories "ls_wg worker #n" and "ls_wg/io worker #n". They are the same pools
        // as "<brand> Common Worker" and "<brand> I/O Worker", so they take the same lanes.
        assertLane(ExecutionLane.CHUNK_WORKER, "ls_wg worker #0", "ls_wg worker #15");
        assertLane(ExecutionLane.WORLD_IO, "ls_wg/io worker #0", "ls_wg/io worker #3");
    }

    @Test
    void theChunkPoolsKeepTheirLaneWhateverTheBrandIs() {
        // The name is PlatformHooks.get().getBrand() + " Common Worker #n". The brand is not ours to
        // pin: the test JVM and a production boot must agree because the rule is "any one word".
        assertLane(ExecutionLane.CHUNK_WORKER, "Paper Common Worker #0", "Canvas Common Worker #3",
            "SourbyCraft Common Worker #1");
        assertLane(ExecutionLane.WORLD_IO, "Paper I/O Worker #0", "Canvas I/O Worker #1", "SourbyCraft I/O Worker #2");
    }

    @Test
    void theBrandTheEngineActuallyReportsGivesAChunkWorkerAndAnIoWorkerName() {
        final String brand = ca.spottedleaf.moonrise.common.PlatformHooks.get().getBrand();
        assertNotNull(brand);
        assertEquals(ExecutionLane.CHUNK_WORKER, ExecutionLane.of(brand + " Common Worker #0"), brand);
        assertEquals(ExecutionLane.WORLD_IO, ExecutionLane.of(brand + " I/O Worker #0"), brand);
    }

    @Test
    void everyNettyEventLoopTheServerCanCreateIsNetwork() throws Exception {
        // EventLoopGroupHolder names its threads "Netty <type> IO #n" with type NIO, Epoll, Kqueue,
        // Local and (Paper) "Unix Domain Socket". Built from the real factories, not typed in.
        final Class<?> holder = Class.forName("net.minecraft.server.network.EventLoopGroupHolder");
        final java.lang.reflect.Method factory = holder.getDeclaredMethod("createThreadFactory");
        factory.setAccessible(true);
        final java.util.Set<String> types = new java.util.TreeSet<>();
        for (final java.lang.reflect.Field field : holder.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != holder) continue;
            field.setAccessible(true);
            final java.util.concurrent.ThreadFactory threads =
                (java.util.concurrent.ThreadFactory) factory.invoke(field.get(null));
            final String name = threads.newThread(() -> { }).getName();
            types.add(name);
            assertEquals(ExecutionLane.NETWORK, ExecutionLane.of(name), name);
        }
        assertTrue(types.contains("Netty Unix Domain Socket IO #0"), "the set under test: " + types);
        assertTrue(types.size() >= 5, "the set under test: " + types);
    }

    @Test
    void newClassificationsDoNotMakeAnythingARegionThread() {
        // AwfRegionStorage, GenerationStore and RedisStore refuse to block or sleep on REGION_TICK.
        // Only the region scheduler's threads may be that lane.
        for (final String name : new String[] {"SourbyCraft-AWF-Maintenance", "ls_wg worker #0", "ls_wg/io worker #0",
            "Netty Unix Domain Socket IO #0", "Netty Local IO #0", "Paper Common Worker #0"}) {
            assertNotEquals(ExecutionLane.REGION_TICK, ExecutionLane.of(name), name);
        }
    }

    @Test
    void lookalikesOfTheNewNamesStayOther() {
        assertLane(ExecutionLane.OTHER, "ls_wg", "ls_wg-1", "ls_wgworker #0", "ls_wg/ioworker #0", "ls_wg/io #0",
            "AWF-Maintenance", "SourbyCraft-AWF", "Common Worker #0", " Common Worker #0", "Netty IO #0",
            "my plugin Common Worker #0");
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
