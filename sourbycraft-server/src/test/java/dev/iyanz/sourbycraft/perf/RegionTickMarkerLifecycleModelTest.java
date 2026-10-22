package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Randomised model of the region telemetry lifecycle that drives the "running tick" marker: tick
 * start, tick completion, merge rotation, inactive/active retirement with a fresh handle, and a
 * deliberately lost completion. The invariant behind the "Recent maximum pinned at 5000 ms"
 * report: the registry never shows a running tick for a region that is not running one, and a
 * lost completion is gone after the next tick of that region completes.
 */
class RegionTickMarkerLifecycleModelTest {

    private static final long MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final long TARGET = 50L * MILLISECOND;

    private static final class Region {
        RegionTickMetricsHolder holder = new RegionTickMetricsHolder();
        long generation;
        long runningSince = TimeUtil.DEADLINE_NOT_SET;   // model of "a tick is in progress"
        boolean lostCompletion;                          // an earlier start that never completed
        long lastStart = TimeUtil.DEADLINE_NOT_SET;
    }

    @Test
    void theRegistryNeverReportsARunningTickForARegionThatIsNotRunningOne() {
        for (long seed = 1L; seed <= 40L; ++seed) {
            run(seed);
        }
    }

    private static void run(final long seed) {
        final Random random = new Random(seed);
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        final List<Region> regions = new ArrayList<>();
        long clock = 10L * 1_000_000_000L;
        for (int i = 0; i < 6; ++i) {
            final Region region = new Region();
            region.generation = registry.activate(world, i + 1, region.holder, clock);
            regions.add(region);
        }

        for (int step = 0; step < 4_000; ++step) {
            clock += 1L + random.nextInt(3) * MILLISECOND;
            final int index = random.nextInt(regions.size());
            final Region region = regions.get(index);
            final boolean running = region.runningSince != TimeUtil.DEADLINE_NOT_SET;
            switch (random.nextInt(8)) {
                case 0, 1, 2 -> {
                    if (!running) {
                        if (region.lostCompletion || random.nextInt(25) == 0) {
                            // A start whose completion never arrives, then the region moves on.
                            region.holder.tickStarted(clock);
                            region.lostCompletion = true;
                            clock += MILLISECOND;
                        }
                        region.runningSince = clock;
                        region.holder.tickStarted(clock);
                    }
                }
                case 3, 4, 5 -> {
                    if (running) {
                        final long start = region.runningSince;
                        region.holder.tickCompleted(new TickTime(region.lastStart, start, start, 0L,
                            start + 3L * MILLISECOND, 0L, 0L, 0L, false), TARGET);
                        region.lastStart = start;
                        region.runningSince = TimeUtil.DEADLINE_NOT_SET;
                        region.lostCompletion = false;
                    }
                }
                case 6 -> {
                    if (!running) {
                        // Merge rotation: only ever applied to a region that is not mid-tick.
                        region.generation = registry.rotateForMerge(
                            region.holder.generationId(), world, index + 1, region.holder, clock);
                        // The merge target then runs and finishes a whole tick (tickStarted also
                        // re-arms the next merge wave).
                        region.holder.tickStarted(clock);
                        region.holder.tickCompleted(new TickTime(region.lastStart, clock, clock, 0L,
                            clock + MILLISECOND, 0L, 0L, 0L, false), TARGET);
                        region.lastStart = clock;
                        region.lostCompletion = false;
                    }
                }
                default -> {
                    if (!running) {
                        // Region inactive then active again: retire, fresh handle (fresh holder), activate.
                        registry.retire(region.holder, RegionMetricsRegistry.RetirementReason.INACTIVE, clock);
                        final RegionTickMetricsHolder fresh = new RegionTickMetricsHolder();
                        region.holder = fresh;
                        region.generation = registry.activate(world, index + 1, fresh, clock);
                        region.lostCompletion = false;
                        region.lastStart = TimeUtil.DEADLINE_NOT_SET;
                    }
                }
            }
            if (step % 7 == 0) {
                check(registry, regions, clock, seed, step);
            }
        }
    }

    private static void check(final RegionMetricsRegistry registry, final List<Region> regions,
                              final long now, final long seed, final int step) {
        registry.forEachUnexpired(now, view -> {
            if (!view.active()) {
                assertEquals(RegionTickMetrics.INACTIVE, view.snapshot().activeTickStartNanos(),
                    "retired generation reports a running tick, seed " + seed + " step " + step);
                return;
            }
            Region owner = null;
            for (final Region region : regions) {
                if (region.holder.generationId() == view.generationId()) {
                    owner = region;
                }
            }
            assertTrue(owner != null, "active generation without an owner, seed " + seed);
            final long marker = view.snapshot().activeTickStartNanos();
            if (owner.runningSince == TimeUtil.DEADLINE_NOT_SET) {
                // Not running: the only tolerated marker is a lost completion not yet covered by a
                // completed tick (it is replaced by the next start, and covered by its completion).
                assertTrue(marker == RegionTickMetrics.INACTIVE || owner.lostCompletion,
                    "stale marker " + marker + " with no tick running, seed " + seed + " step " + step);
            } else {
                assertEquals(owner.runningSince, marker,
                    "running tick must carry its own start, seed " + seed + " step " + step);
            }
        });
    }
}
