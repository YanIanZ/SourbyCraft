package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.retention.Reachability;
import dev.iyanz.sourbycraft.retention.Reachability.Tracker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Region telemetry generations are keyed by a primitive generation id and carry a world id and a region
 * id, never a world or region object ({@code RegionMetricsRegistryTest} pins that by field type). What is
 * left to prove is the release path itself: a generation retired by a region merge/split/destroy or by a
 * world unload is held for the 15-minute history window and then the registry lets go of its
 * {@link RegionTickMetrics}, without the collector having to run.
 *
 * <p>Each case first shows the registry really holds the metrics (the holder, which belongs to the
 * region, is dropped), so "collected" cannot come from the metrics never having been retained.</p>
 */
class RegionMetricsRetentionTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);
    /** Window plus grace: the point after which a retired generation may be removed. */
    private static final long RETENTION = TimeUnit.MINUTES.toNanos(15L) + SECOND;

    private record Tracked(long generationId, List<Tracker> trackers) {}

    /** Activates a generation for a region whose own objects are dropped before this returns. */
    private static Tracked activateAndForgetTheRegion(final RegionMetricsRegistry registry, final long worldId,
                                                      final long regionId) {
        final RegionTickMetricsHolder holder = new RegionTickMetricsHolder();
        final long generation = registry.activate(worldId, regionId, holder, 0L);
        final List<Tracker> trackers = new ArrayList<>();
        trackers.add(Reachability.track("tick metrics of region " + regionId, holder.current()));
        return new Tracked(generation, trackers);
    }

    private static int visible(final RegionMetricsRegistry registry, final long nowNanos) {
        final int[] count = {0};
        registry.forEachUnexpired(nowNanos, view -> count[0]++);
        return count[0];
    }

    @Test
    void aRetiredGenerationIsHeldForTheHistoryWindowThenReleased() throws Exception {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final Tracked region = activateAndForgetTheRegion(registry, registry.newWorldId(), 5L);
        region.trackers().getFirst().assertStillReachable();

        registry.retire(region.generationId(), RegionMetricsRegistry.RetirementReason.DESTROYED, 0L);
        assertEquals(1, visible(registry, RETENTION - 1L), "still inside the window: history is kept");
        region.trackers().getFirst().assertStillReachable();

        assertEquals(0, visible(registry, RETENTION + 1L), "past window and grace: dropped by the read path");
        Reachability.assertAllCollected(region.trackers());
    }

    @Test
    void anUnloadedWorldReleasesEveryGenerationItHadAndOnlyThose() throws Exception {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long unloaded = registry.newWorldId();
        final long surviving = registry.newWorldId();
        final List<Tracker> goneWithTheWorld = new ArrayList<>();
        for (long region = 1; region <= 5; region++) {
            goneWithTheWorld.addAll(activateAndForgetTheRegion(registry, unloaded, region).trackers());
        }
        final Tracked kept = activateAndForgetTheRegion(registry, surviving, 100L);
        goneWithTheWorld.forEach(Tracker::assertStillReachable);

        registry.retireWorld(unloaded, SECOND);

        assertEquals(6, visible(registry, 2L * SECOND), "retired generations stay readable for the window");
        assertEquals(1, visible(registry, SECOND + RETENTION + 1L), "only the surviving world's generation remains");
        Reachability.assertAllCollected(goneWithTheWorld);
        kept.trackers().getFirst().assertStillReachable();
    }

    @Test
    void activatingNewRegionsSweepsExpiredOnesEvenIfTheCollectorNeverRuns() throws Exception {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        final Tracked old = activateAndForgetTheRegion(registry, world, 1L);
        registry.retire(old.generationId(), RegionMetricsRegistry.RetirementReason.MERGE, 0L);
        old.trackers().getFirst().assertStillReachable();

        // A later region activates long after the window; nobody calls forEachUnexpired.
        registry.activate(world, 2L, new RegionTickMetricsHolder(), RETENTION + SECOND);

        Reachability.assertAllCollected(old.trackers());
    }

    @Test
    void retiringThroughTheHolderClearsItsGenerationSoTheRegionCanBeRetiredOnlyOnce() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final RegionTickMetricsHolder holder = new RegionTickMetricsHolder();
        final long generation = registry.activate(registry.newWorldId(), 9L, holder, 0L);
        assertEquals(generation, holder.generationId());

        registry.retire(holder, RegionMetricsRegistry.RetirementReason.DESTROYED, SECOND);

        assertEquals(0L, holder.generationId(), "the holder no longer points at a retired generation");
        registry.retire(holder, RegionMetricsRegistry.RetirementReason.DESTROYED, 2L * SECOND);
        assertEquals(1, visible(registry, 2L * SECOND));
        assertEquals(0, visible(registry, SECOND + RETENTION + 1L));
    }
}
