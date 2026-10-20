package dev.iyanz.aurora.engine.spark;

import dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver;
import dev.iyanz.aurora.engine.threadedregions.SchedulerUtil;
import dev.iyanz.aurora.engine.threadedregions.profiler.RegionThreadDumper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.lucko.spark.paper.common.sampler.ThreadGrouper;
import me.lucko.spark.paper.common.util.ThreadFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine spark: {@link SparkThreadClass}, the name table behind the spark thread labels. The
 * names are the ones the factories give: {@code TickRegionScheduler}, the Moonrise pool patch
 * ({@code <brand> Common Worker}, {@code <brand> I/O Worker}, {@code ls_wg worker} and
 * {@code ls_wg/io worker}), {@code EventLoopGroupHolder}, {@code ResourceGovernor.Lane}, the async
 * path pool and {@code AwfMaintenanceDriver.THREAD_NAME}.
 */
class SparkThreadClassTest {

    private static final Map<SparkThreadClass, List<String>> NAMES = new EnumMap<>(SparkThreadClass.class);

    static {
        NAMES.put(SparkThreadClass.REGION_TICK, List.of("Folia Region Scheduler Thread #0", "Folia Region Scheduler Thread #15"));
        NAMES.put(SparkThreadClass.CHUNK_WORKER, List.of("Paper Common Worker #0", "Canvas Common Worker #3", "SourbyCraft Common Worker #1", "ls_wg worker #2"));
        NAMES.put(SparkThreadClass.CHUNK_IO, List.of("Paper I/O Worker #0", "Canvas I/O Worker #1", "ls_wg/io worker #0"));
        NAMES.put(SparkThreadClass.SOURBY_LANE, List.of("SourbyCraft-Storage-redis-lease", "SourbyCraft-Storage-redis-watchdog",
            "SourbyCraft-Storage-1", "SourbyCraft-BridgeIO-1", "SourbyCraft-Startup-0"));
        NAMES.put(SparkThreadClass.ASYNC_PATH, List.of("SourbyCraft-AsyncPath-1", "SourbyCraft-AsyncPath-12"));
        NAMES.put(SparkThreadClass.AWF_MAINTENANCE, List.of(AwfMaintenanceDriver.THREAD_NAME));
        NAMES.put(SparkThreadClass.NETWORK, List.of("Netty Epoll IO #0", "Netty Kqueue IO #3", "Netty NIO IO #1", "Netty Unix Domain Socket IO #0"));
    }

    @Test
    void everyKnownThreadNameLandsInItsClass() {
        NAMES.forEach((expected, names) -> names.forEach(name -> assertEquals(expected, SparkThreadClass.of(name), name)));
    }

    @Test
    void everyClassButOtherHasNamesInTheTable() {
        for (final SparkThreadClass threadClass : SparkThreadClass.values()) {
            if (threadClass != SparkThreadClass.OTHER) {
                assertTrue(NAMES.containsKey(threadClass), threadClass + " has no sample names");
                assertFalse(threadClass.patterns().isEmpty());
            }
        }
        assertTrue(SparkThreadClass.OTHER.patterns().isEmpty());
    }

    @Test
    void unrecognisedNamesAreOtherNeverFoldedIntoANeighbour() {
        for (final String name : List.of("Server thread", "main", "Worker-Main-1", "Dimension-Data-IO-Worker-1", "Craft Scheduler Thread - Foo - 0",
            "Folia Region Scheduler Thread", "Folia Region Scheduler Thread #", "Folia Region Scheduler Thread #1 extra",
            "Paper Common Worker", "Paper Common Worker #x", "SourbyCraft-AsyncPath", "SourbyCraft-AWF-Maintenance-2",
            "SourbyCraft-PerformanceCollector", "SourbyCraft-IO-1", "Netty Epoll IO", "prefix Netty Epoll IO #1", "spark-worker-pool-1", "G1 Conc#0")) {
            assertEquals(SparkThreadClass.OTHER, SparkThreadClass.of(name), name);
        }
        assertEquals(SparkThreadClass.OTHER, SparkThreadClass.of(null));
        assertEquals(SparkThreadClass.OTHER, SparkThreadClass.of(""));
    }

    @Test
    void matchingIsCaseInsensitiveLikeTheRegionThreadDumper() {
        assertEquals(SparkThreadClass.REGION_TICK, SparkThreadClass.of("FOLIA REGION SCHEDULER THREAD #4"));
        assertEquals(SparkThreadClass.NETWORK, SparkThreadClass.of("netty epoll io #4"));
    }

    @Test
    void labelsAreDistinctAndReadable() {
        final List<String> labels = new ArrayList<>();
        for (final SparkThreadClass threadClass : SparkThreadClass.values()) {
            assertFalse(threadClass.label().isBlank());
            labels.add(threadClass.label());
        }
        assertEquals(labels.size(), labels.stream().distinct().count());
        assertEquals("Region and global tick", SparkThreadClass.REGION_TICK.label(), "the label says what the thread name cannot separate");
    }

    @Test
    void regionTickIsExactlyWhatTheRegionThreadDumperSelects() {
        // the dumper that decides what is sampled and the class that describes it must never drift apart
        final SchedulerUtil.NullHandler handler = new SchedulerUtil.NullHandler();
        try (var ignored = SparkTestSupport.install(handler)) {
            final RegionThreadDumper dumper = new RegionThreadDumper();
            long id = 5_000_000L;
            for (final List<String> names : NAMES.values()) {
                for (final String name : names) {
                    assertEquals(SparkThreadClass.of(name) == SparkThreadClass.REGION_TICK, dumper.isThreadIncluded(id++, name), name);
                }
            }
            for (final String name : List.of("Folia Region Scheduler Thread #", "folia region scheduler thread #9", "Folia Region Scheduler Thread #1 extra", "main")) {
                assertEquals(SparkThreadClass.of(name) == SparkThreadClass.REGION_TICK, dumper.isThreadIncluded(id++, name), name);
            }
        }
    }

    @Test
    void poolOfRepeatsSparksByPoolRuleOnEveryKnownName() {
        final ThreadGrouper byPool = ThreadGrouper.BY_POOL.get();
        long id = 6_000_000L;
        final List<String> all = new ArrayList<>(List.of("main", "Server thread", "x-1", "x 2", "x#3", "x - 4", "x-", "ab12", "7"));
        NAMES.values().forEach(all::addAll);
        for (final String name : all) {
            assertEquals(byPool.getGroup(id++, name), SparkThreadClass.poolOf(name), name);
        }
    }

    @Test
    void underByPoolEveryClassGetsItsOwnViewerLabelsAndUnderAsOneNoneDoes() {
        final ThreadGrouper byPool = ThreadGrouper.BY_POOL.get();
        final ThreadGrouper asOne = ThreadGrouper.AS_ONE.get();
        final Map<String, SparkThreadClass> groupOwner = new java.util.HashMap<>();
        long id = 7_000_000L;
        for (final Map.Entry<SparkThreadClass, List<String>> entry : NAMES.entrySet()) {
            for (final String name : entry.getValue()) {
                final String label = byPool.getLabel(byPool.getGroup(id++, name));
                final SparkThreadClass previous = groupOwner.putIfAbsent(label, entry.getKey());
                assertTrue(previous == null || previous == entry.getKey(), "label '" + label + "' shared by " + previous + " and " + entry.getKey());
            }
        }
        // a pool is one label: the region threads of any number are a single group
        assertEquals(byPool.getGroup(8_000_001L, "Folia Region Scheduler Thread #0"), byPool.getGroup(8_000_002L, "Folia Region Scheduler Thread #9"));
        assertEquals(asOne.getGroup(8_000_003L, "Netty Epoll IO #0"), asOne.getGroup(8_000_004L, "Folia Region Scheduler Thread #0"), "--combine-all folds every thread into one");
        assertNotEquals(byPool.getGroup(8_000_005L, "Netty Epoll IO #0"), byPool.getGroup(8_000_006L, "Folia Region Scheduler Thread #0"));
    }

    @Test
    @Timeout(60)
    void virtualThreadsAreInvisibleToSparkSoTheSourbyIoLaneCannotBeLabelled() throws Exception {
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread virtual = Thread.ofVirtual().name("SourbyCraft-IO-9001").start(() -> await(started, release));
        final Thread platform = Thread.ofPlatform().daemon().name("SourbyCraft-Storage-9001").start(() -> await(started, release));
        try {
            Thread.sleep(0); // both are scheduled; the latch below makes sure they have run
            while (started.getCount() > 0) {
                Thread.sleep(5);
            }
            final List<String> seen = new ThreadFinder().getThreads().map(Thread::getName).toList();
            assertTrue(seen.contains("SourbyCraft-Storage-9001"), "a platform thread is found");
            assertFalse(seen.contains("SourbyCraft-IO-9001"), "a virtual thread is not: spark cannot sample the Sourby I/O lane");
        } finally {
            release.countDown();
            virtual.join(30_000L);
            platform.join(30_000L);
        }
    }

    private static void await(final CountDownLatch started, final CountDownLatch release) {
        started.countDown();
        try {
            release.await(60, TimeUnit.SECONDS);
        } catch (final InterruptedException ignored) {
            // released
        }
    }
}
