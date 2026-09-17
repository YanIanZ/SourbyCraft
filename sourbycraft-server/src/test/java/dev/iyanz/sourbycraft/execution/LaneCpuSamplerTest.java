package dev.iyanz.sourbycraft.execution;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.execution.ThreadCpuSource.ThreadCpu;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class LaneCpuSamplerTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);

    @Test
    void theFirstSampleOnlyEstablishesABaseline() {
        // A cumulative counter says how much CPU a thread has used since it started. Over an
        // unknown interval that is not a rate, and reporting it as one would show a server that
        // has been up for hours as permanently saturated.
        final LaneCpuSampler sampler = sampler(reading(SECOND, cpu(1L, "Folia Region Scheduler Thread #0", SECOND)));

        final LaneCpuSampler.LaneLoads first = sampler.sample();

        assertFalse(first.available());
        assertTrue(first.reason().contains("baseline"));
        assertEquals(List.of(), first.lanes());
    }

    @Test
    void aFullyBusyThreadReadsAsOneCore() {
        final LaneCpuSampler sampler = sampler(
            reading(0L, cpu(1L, "Folia Region Scheduler Thread #0", 0L)),
            reading(SECOND, cpu(1L, "Folia Region Scheduler Thread #0", SECOND)));
        sampler.sample();

        final LaneCpuSampler.LaneLoads loads = sampler.sample();

        assertTrue(loads.available(), loads.reason());
        assertEquals(1.0, byLane(loads).get(ExecutionLane.REGION_TICK).cores(), 1.0E-9);
        assertEquals(1.0, loads.totalCores(), 1.0E-9);
    }

    @Test
    void threadsAreSummedWithinALaneAndKeptApartAcrossLanes() {
        // The distinction thread counts cannot make: two saturated chunk workers cost more than
        // four region threads that are mostly parked.
        final LaneCpuSampler sampler = sampler(
            reading(0L,
                cpu(1L, "Paper Common Worker #0", 0L), cpu(2L, "Paper Common Worker #1", 0L),
                cpu(3L, "Folia Region Scheduler Thread #0", 0L)),
            reading(SECOND,
                cpu(1L, "Paper Common Worker #0", SECOND), cpu(2L, "Paper Common Worker #1", SECOND),
                cpu(3L, "Folia Region Scheduler Thread #0", SECOND / 10L)));
        sampler.sample();

        final LaneCpuSampler.LaneLoads loads = sampler.sample();
        final Map<ExecutionLane, LaneCpuSampler.LaneLoad> lanes = byLane(loads);

        assertEquals(2.0, lanes.get(ExecutionLane.CHUNK_WORKER).cores(), 1.0E-9);
        assertEquals(2, lanes.get(ExecutionLane.CHUNK_WORKER).threads());
        assertEquals(0.1, lanes.get(ExecutionLane.REGION_TICK).cores(), 1.0E-9);
        assertEquals(1, lanes.get(ExecutionLane.REGION_TICK).threads());
        assertEquals(2.1, loads.totalCores(), 1.0E-9);
    }

    @Test
    void theHeaviestLaneIsReportedFirst() {
        final LaneCpuSampler sampler = sampler(
            reading(0L, cpu(1L, "Netty Kqueue IO #0", 0L), cpu(2L, "Paper Common Worker #0", 0L)),
            reading(SECOND, cpu(1L, "Netty Kqueue IO #0", SECOND / 4L),
                cpu(2L, "Paper Common Worker #0", SECOND)));
        sampler.sample();

        assertEquals(ExecutionLane.CHUNK_WORKER, sampler.sample().lanes().get(0).lane());
    }

    @Test
    void aThreadSeenForTheFirstTimeIsCountedButContributesNoCpu() {
        // Its counter covers its whole life, not this interval. Charging that to the lane would
        // show a spike every time a pool grew.
        final LaneCpuSampler sampler = sampler(
            reading(0L, cpu(1L, "Paper Common Worker #0", 0L)),
            reading(SECOND, cpu(1L, "Paper Common Worker #0", 0L),
                cpu(2L, "Paper Common Worker #1", 90L * SECOND)));
        sampler.sample();

        final LaneCpuSampler.LaneLoad chunk = byLane(sampler.sample()).get(ExecutionLane.CHUNK_WORKER);
        assertEquals(2, chunk.threads());
        assertEquals(0.0, chunk.cores(), 1.0E-9);
    }

    @Test
    void aCounterThatWentBackwardsContributesNothingRatherThanANegative() {
        // Thread ids are reused. The new thread's history is not the old one's work.
        final LaneCpuSampler sampler = sampler(
            reading(0L, cpu(1L, "Paper Common Worker #0", 10L * SECOND)),
            reading(SECOND, cpu(1L, "Paper Common Worker #0", SECOND)));
        sampler.sample();

        final LaneCpuSampler.LaneLoads loads = sampler.sample();
        assertEquals(0.0, byLane(loads).get(ExecutionLane.CHUNK_WORKER).cores(), 1.0E-9);
        assertTrue(loads.totalCores() >= 0.0);
    }

    @Test
    void aPlatformThatCannotMeasureThreadCpuSaysSoRatherThanReportingZero() {
        final LaneCpuSampler sampler = new LaneCpuSampler(List::of, () -> SECOND);

        final LaneCpuSampler.LaneLoads loads = sampler.sample();

        assertFalse(loads.available());
        assertTrue(loads.reason().contains("per-thread CPU"));
        assertTrue(Double.isNaN(loads.totalCores()));
    }

    @Test
    void theRunningJvmIsMeasurable() {
        // The fixtures above prove the arithmetic; this proves the platform source works at all.
        final LaneCpuSampler sampler = LaneCpuSampler.platform();
        assertFalse(sampler.sample().available(), "first sample is the baseline");
        burnCpuBriefly();

        final LaneCpuSampler.LaneLoads loads = sampler.sample();

        assertTrue(loads.available(), loads.reason());
        assertTrue(loads.totalCores() > 0.0, "a JVM running a test is using some CPU");
        assertTrue(loads.lanes().stream().anyMatch(lane -> lane.threads() > 0));
    }

    private static void burnCpuBriefly() {
        long sink = 0L;
        final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50L);
        while (System.nanoTime() < until) {
            sink += System.nanoTime();
        }
        assertNotEquals(Long.MIN_VALUE, sink);
    }

    private static Map<ExecutionLane, LaneCpuSampler.LaneLoad> byLane(final LaneCpuSampler.LaneLoads loads) {
        return loads.lanes().stream()
            .collect(Collectors.toMap(LaneCpuSampler.LaneLoad::lane, Function.identity()));
    }

    private static ThreadCpu cpu(final long id, final String name, final long cpuNanos) {
        return new ThreadCpu(id, name, cpuNanos);
    }

    private record Reading(long atNanos, List<ThreadCpu> threads) {}

    private static Reading reading(final long atNanos, final ThreadCpu... threads) {
        return new Reading(atNanos, List.of(threads));
    }

    private static LaneCpuSampler sampler(final Reading... readings) {
        final Deque<Reading> queue = new ArrayDeque<>(List.of(readings));
        final long[] clock = {0L};
        return new LaneCpuSampler(
            () -> {
                final Reading next = queue.poll();
                if (next == null) {
                    return List.of();
                }
                clock[0] = next.atNanos();
                return next.threads();
            },
            () -> clock[0]);
    }
}
