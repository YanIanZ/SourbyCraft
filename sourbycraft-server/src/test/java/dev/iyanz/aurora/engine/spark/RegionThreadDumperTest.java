package dev.iyanz.aurora.engine.spark;

import dev.iyanz.aurora.engine.threadedregions.SchedulerUtil;
import dev.iyanz.aurora.engine.threadedregions.profiler.RegionThreadDumper;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.lucko.spark.paper.proto.SparkSamplerProtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine spark: {@link RegionThreadDumper}, the default thread dumper of the spark module.
 * Outside a region profile it includes exactly the region tick threads, by name
 * ({@code Folia Region Scheduler Thread #<n>}, the name {@code TickRegionScheduler} gives them); the
 * global tick runs on those same threads. Chunk workers, I/O, netty, the console and plugin threads
 * are not included. During a region profile the scheduler handle alone decides.
 */
class RegionThreadDumperTest {

    private static long nextId = 1_000_000L;

    private static boolean included(final RegionThreadDumper dumper, final String name) {
        return dumper.isThreadIncluded(nextId++, name);
    }

    @Test
    void regionTickThreadsAreIncludedByNameCaseInsensitively() {
        try (var ignored = SparkTestSupport.install(new SchedulerUtil.NullHandler())) {
            final RegionThreadDumper dumper = new RegionThreadDumper();
            assertTrue(included(dumper, "Folia Region Scheduler Thread #0"));
            assertTrue(included(dumper, "Folia Region Scheduler Thread #17"));
            assertTrue(included(dumper, "folia region scheduler thread #3"));
            assertTrue(included(dumper, "FOLIA REGION SCHEDULER THREAD #123456"));
        }
    }

    @Test
    void everyOtherThreadIsExcluded() {
        try (var ignored = SparkTestSupport.install(new SchedulerUtil.NullHandler())) {
            final RegionThreadDumper dumper = new RegionThreadDumper();
            for (final String name : List.of(
                "Server thread", "main", "Paper Common Worker #0", "Netty Epoll Server IO #1", "Craft Scheduler Thread - Foo - 1",
                "Folia Region Scheduler Thread", "Folia Region Scheduler Thread #", "Folia Region Scheduler Thread #x",
                "Folia Region Scheduler Thread #1 extra", "prefix Folia Region Scheduler Thread #1",
                "Region Scheduler Thread #1", "Folia Region Scheduler ThreadGroup", "", "spark-worker-pool-1"
            )) {
                assertFalse(included(dumper, name), "'" + name + "'");
            }
        }
    }

    @Test
    void theAnswerForAThreadIsCachedByItsId() {
        try (var ignored = SparkTestSupport.install(new SchedulerUtil.NullHandler())) {
            final RegionThreadDumper dumper = new RegionThreadDumper();
            assertTrue(dumper.isThreadIncluded(42L, "Folia Region Scheduler Thread #1"));
            assertTrue(dumper.isThreadIncluded(42L, "renamed"), "cached by thread id, which the JVM never reuses");
            assertFalse(dumper.isThreadIncluded(43L, "renamed"));
        }
    }

    @Test
    void duringARegionProfileTheHandleDecidesAndTheNameIsIgnored() {
        final SchedulerUtil.AffinityHandler handler = mock(SchedulerUtil.AffinityHandler.class);
        when(handler.isRunningRegionProfiler()).thenReturn(true);
        when(handler.isRunningRegionProfilerOnThread(anyLong(), anyString())).thenAnswer(call ->
            (long) call.getArgument(0) == 7L && "Pinned Runner".equals(call.getArgument(1)));
        try (var ignored = SparkTestSupport.install(handler)) {
            final RegionThreadDumper dumper = new RegionThreadDumper();
            assertTrue(dumper.isThreadIncluded(7L, "Pinned Runner"));
            assertFalse(dumper.isThreadIncluded(8L, "Folia Region Scheduler Thread #1"), "a region thread that is not the pinned one");
        }
    }

    @Test
    void metadataSaysTheThreadSetIsSpecific() {
        final SparkSamplerProtos.SamplerMetadata.ThreadDumper metadata = new RegionThreadDumper().getMetadata();
        assertEquals(SparkSamplerProtos.SamplerMetadata.ThreadDumper.Type.SPECIFIC, metadata.getType());
    }

    @Test
    void asksTheSchedulerHandleSoItNeedsAServerToHaveChosenOne() {
        try (var ignored = SparkTestSupport.install(null)) {
            assertThrows(IllegalStateException.class, () -> new RegionThreadDumper().isThreadIncluded(1L, "x"));
        }
    }

    @Test
    @Timeout(60)
    void dumpThreadsReturnsLiveRegionThreadsAndNothingElse() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(2);
        final List<Thread> threads = new ArrayList<>();
        for (final String name : List.of("Folia Region Scheduler Thread #901", "Unrelated Worker #901")) {
            final Thread thread = new Thread(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (final InterruptedException ignored) {
                    // released
                }
            }, name);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        try (var ignored = SparkTestSupport.install(new SchedulerUtil.NullHandler())) {
            assertTrue(started.await(30, TimeUnit.SECONDS));

            final ThreadInfo[] dumped = new RegionThreadDumper().dumpThreads(ManagementFactory.getThreadMXBean());

            final List<String> names = new ArrayList<>();
            for (final ThreadInfo info : dumped) names.add(info.getThreadName());
            assertTrue(names.contains("Folia Region Scheduler Thread #901"), names.toString());
            assertFalse(names.contains("Unrelated Worker #901"));
            for (final String name : names) {
                assertTrue(name.matches("(?i)Folia Region Scheduler Thread #\\d+"), "only region threads: " + name);
            }
        } finally {
            release.countDown();
            for (final Thread thread : threads) thread.join(30_000L);
        }
    }
}
