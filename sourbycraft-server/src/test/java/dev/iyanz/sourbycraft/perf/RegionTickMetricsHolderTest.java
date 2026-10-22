package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTickMetricsHolderTest {

    private static final long MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final long TARGET_INTERVAL = 50L * MILLISECOND;

    @Test
    void allCompatibilityViewsFollowOwnerRotation() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final RegionTickMetricsHolder holder = new RegionTickMetricsHolder();
        final List<TickData> views = views(holder);
        final long generation = registry.activate(1L, 2L, holder, 0L);
        holder.tickCompleted(tick(TimeUtil.DEADLINE_NOT_SET, 0L), TARGET_INTERVAL);

        final long successor = registry.rotateForMerge(generation, 1L, 2L, holder, SECOND);

        assertTrue(generation != successor);
        for (final TickData view : views) {
            assertNull(view.generateTickReport(null, SECOND, TARGET_INTERVAL));
        }

        holder.tickStarted(SECOND);
        holder.tickCompleted(tick(0L, SECOND), TARGET_INTERVAL);
        for (final TickData view : views) {
            assertEquals(1, view.generateTickReport(null, SECOND + MILLISECOND, TARGET_INTERVAL).collectedTicks());
        }
    }

    @Test
    void multipleSourcesRotateTargetOnceUntilNextTickStarts() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final RegionTickMetricsHolder holder = new RegionTickMetricsHolder();
        final long original = registry.activate(1L, 2L, holder, 0L);

        final long firstSuccessor = registry.rotateForMerge(original, 1L, 2L, holder, SECOND);
        final var firstOwner = holder.current();
        final long duplicateSuccessor = registry.rotateForMerge(firstSuccessor, 1L, 2L, holder, SECOND + 1L);

        assertEquals(firstSuccessor, duplicateSuccessor);
        assertSame(firstOwner, holder.current());

        holder.tickStarted(2L * SECOND);
        final long nextWave = registry.rotateForMerge(firstSuccessor, 1L, 2L, holder, 2L * SECOND + 1L);
        assertNotSame(firstOwner, holder.current());
        assertEquals(3, views(registry, 2L * SECOND + 1L).size());
        assertEquals(nextWave, holder.generationId());
    }

    @Test
    void inactiveMergeDefersPublicationButStillStartsWithFreshOwner() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final RegionTickMetricsHolder holder = new RegionTickMetricsHolder();
        final var initialOwner = holder.current();

        assertEquals(0L, registry.rotateForMerge(0L, 1L, 2L, holder, SECOND));
        assertNotSame(initialOwner, holder.current());
        assertEquals(0L, holder.generationId());
        assertEquals(0, views(registry, SECOND).size());

        final long generation = registry.activate(1L, 2L, holder, 2L * SECOND);
        assertEquals(generation, holder.generationId());
        assertEquals(1, views(registry, 2L * SECOND).size());
    }

    private static List<TickData> views(final RegionTickMetricsHolder holder) {
        return List.of(
            new TickData(holder, TimeUnit.SECONDS.toNanos(5L)),
            new TickData(holder, TimeUnit.SECONDS.toNanos(15L)),
            new TickData(holder, TimeUnit.MINUTES.toNanos(1L)),
            new TickData(holder, TimeUnit.MINUTES.toNanos(5L)),
            new TickData(holder, TimeUnit.MINUTES.toNanos(15L))
        );
    }

    private static List<RegionMetricsRegistry.GenerationView> views(final RegionMetricsRegistry registry,
                                                                    final long nowNanos) {
        final java.util.ArrayList<RegionMetricsRegistry.GenerationView> views = new java.util.ArrayList<>();
        registry.forEachUnexpired(nowNanos, views::add);
        return views;
    }

    private static TickTime tick(final long previousStart, final long start) {
        return new TickTime(previousStart, start, start, 0L, start + MILLISECOND, 0L, 0L, 0L, false);
    }

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);
}
