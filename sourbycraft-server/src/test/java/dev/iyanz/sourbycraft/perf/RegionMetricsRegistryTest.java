package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.RegionTickMetrics;
import ca.spottedleaf.common.time.TickTime;
import ca.spottedleaf.common.util.TimeUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionMetricsRegistryTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);
    private static final long RETENTION = TimeUnit.MINUTES.toNanos(15L) + SECOND;

    @Test
    void inactiveThenDestroyRetiresOneGenerationIdempotently() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long generation = registry.activate(registry.newWorldId(), 7L, new RegionTickMetrics(), 0L);

        registry.retire(generation, RegionMetricsRegistry.RetirementReason.INACTIVE, 10L);
        registry.retire(generation, RegionMetricsRegistry.RetirementReason.DESTROYED, 20L);

        final List<RegionMetricsRegistry.GenerationView> views = views(registry, 20L);
        assertEquals(1, views.size());
        assertEquals(generation, views.getFirst().generationId());
        assertFalse(views.getFirst().active());
    }

    @Test
    void splitRetiresParentAndPublishesFreshChildren() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        final RegionTickMetrics parentMetrics = sampledMetrics(2L * SECOND);
        final long parent = registry.activate(world, 1L, parentMetrics, 0L);

        registry.retire(parent, RegionMetricsRegistry.RetirementReason.SPLIT, 3L * SECOND);
        final long firstChild = registry.activate(world, 2L, new RegionTickMetrics(), 3L * SECOND);
        final long secondChild = registry.activate(world, 3L, new RegionTickMetrics(), 3L * SECOND);

        final List<RegionMetricsRegistry.GenerationView> views = views(registry, 3L * SECOND);
        assertEquals(3, views.size());
        assertGeneration(views, parent, false, 1L);
        assertGeneration(views, firstChild, true, 0L);
        assertGeneration(views, secondChild, true, 0L);
    }

    @Test
    void mergeRetiresSourcesAndImmediatelyRotatesActiveTarget() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        final long source = registry.activate(world, 10L, sampledMetrics(SECOND), 0L);
        final long target = registry.activate(world, 20L, sampledMetrics(SECOND), 0L);

        registry.retire(source, RegionMetricsRegistry.RetirementReason.MERGE, 2L * SECOND);
        final long successor = registry.rotateForMerge(
            target, world, 20L, new RegionTickMetrics(), 2L * SECOND);

        final List<RegionMetricsRegistry.GenerationView> views = views(registry, 2L * SECOND);
        assertNotEquals(target, successor);
        assertGeneration(views, source, false, 1L);
        assertGeneration(views, target, false, 1L);
        assertGeneration(views, successor, true, 0L);
    }

    @Test
    void mergePublishesSuccessorWhenTargetWasAlreadyRetired() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        final long target = registry.activate(world, 20L, sampledMetrics(SECOND), 0L);
        registry.retire(target, RegionMetricsRegistry.RetirementReason.INACTIVE, 2L * SECOND);

        final long successor = registry.rotateForMerge(
            target, world, 20L, new RegionTickMetrics(), 3L * SECOND);

        final List<RegionMetricsRegistry.GenerationView> views = views(registry, 3L * SECOND);
        assertGeneration(views, target, false, 1L);
        assertGeneration(views, successor, true, 0L);
    }

    @Test
    void retiredHistoryRemainsThroughMaximumWindowAndGraceThenExpires() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long generation = registry.activate(registry.newWorldId(), 7L, sampledMetrics(SECOND), 0L);
        registry.retire(generation, RegionMetricsRegistry.RetirementReason.INACTIVE, 2L * SECOND);

        assertEquals(1, views(registry, 2L * SECOND + RETENTION).size());
        assertTrue(views(registry, 2L * SECOND + RETENTION + 1L).isEmpty());
    }

    @Test
    void latestSampleExtendsRetiredHistoryLifetime() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final RegionTickMetrics metrics = sampledMetrics(100L * SECOND);
        final long generation = registry.activate(registry.newWorldId(), 7L, metrics, 0L);
        registry.retire(generation, RegionMetricsRegistry.RetirementReason.INACTIVE, SECOND);

        assertEquals(1, views(registry, 100L * SECOND + RETENTION).size());
        assertTrue(views(registry, 100L * SECOND + RETENTION + 1L).isEmpty());
    }

    @Test
    void retiredGenerationsAreExcludedFromActiveTopology() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        final long retired = registry.activate(world, 1L, new RegionTickMetrics(), 0L);
        registry.retire(retired, RegionMetricsRegistry.RetirementReason.INACTIVE, SECOND);
        registry.activate(world, 2L, new RegionTickMetrics(), SECOND);

        assertEquals(1L, views(registry, SECOND).stream().filter(RegionMetricsRegistry.GenerationView::active).count());
    }

    @Test
    void expiryOfOldGenerationCannotRemoveNewGenerationForSameRegion() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        final long oldGeneration = registry.activate(world, 7L, new RegionTickMetrics(), 0L);
        registry.retire(oldGeneration, RegionMetricsRegistry.RetirementReason.INACTIVE, 0L);
        final long currentGeneration = registry.activate(world, 7L, new RegionTickMetrics(), SECOND);

        final List<RegionMetricsRegistry.GenerationView> views = views(registry, RETENTION + 1L);
        assertEquals(1, views.size());
        assertEquals(currentGeneration, views.getFirst().generationId());
        assertTrue(views.getFirst().active());
    }

    @Test
    void transientDestroyWithoutActivationLeavesRegistryEmpty() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();

        registry.retire(0L, RegionMetricsRegistry.RetirementReason.DESTROYED, SECOND);
        registry.retire(Long.MAX_VALUE, RegionMetricsRegistry.RetirementReason.DESTROYED, SECOND);

        assertTrue(views(registry, SECOND).isEmpty());
    }

    @Test
    void worldRetirementUsesPrimitiveIdentityAndRegistryEntriesRetainOnlyTelemetry() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long firstWorld = registry.newWorldId();
        final long secondWorld = registry.newWorldId();
        registry.activate(firstWorld, 1L, new RegionTickMetrics(), 0L);
        registry.activate(secondWorld, 2L, new RegionTickMetrics(), 0L);

        registry.retireWorld(firstWorld, SECOND);

        final List<RegionMetricsRegistry.GenerationView> views = views(registry, SECOND);
        assertEquals(1L, views.stream().filter(RegionMetricsRegistry.GenerationView::active).count());
        assertFalse(views.stream().filter(view -> view.worldId() == firstWorld).findFirst().orElseThrow().active());
        assertTrue(views.stream().filter(view -> view.worldId() == secondWorld).findFirst().orElseThrow().active());

        final Set<Class<?>> allowedReferences = Set.of(RegionTickMetrics.class, RegionMetricsRegistry.RetirementReason.class);
        for (final Class<?> nested : RegionMetricsRegistry.class.getDeclaredClasses()) {
            if (!nested.getSimpleName().equals("Generation")) {
                continue;
            }
            for (final Field field : nested.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                    assertTrue(allowedReferences.contains(field.getType()),
                        () -> "registry generation retains forbidden reference type " + field.getType().getName());
                }
            }
        }
    }

    private static RegionTickMetrics sampledMetrics(final long endNanos) {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        metrics.tickCompleted(new TickTime(TimeUtil.DEADLINE_NOT_SET, endNanos - 1L, endNanos - 1L,
            0L, endNanos, 0L, 0L, 0L, false), 50_000_000L);
        return metrics;
    }

    private static List<RegionMetricsRegistry.GenerationView> views(
        final RegionMetricsRegistry registry, final long nowNanos
    ) {
        final List<RegionMetricsRegistry.GenerationView> views = new ArrayList<>();
        registry.forEachUnexpired(nowNanos, views::add);
        return views;
    }

    private static void assertGeneration(final List<RegionMetricsRegistry.GenerationView> views,
                                         final long generationId, final boolean active,
                                         final long sampleCount) {
        final RegionMetricsRegistry.GenerationView view = views.stream()
            .filter(candidate -> candidate.generationId() == generationId)
            .findFirst().orElseThrow();
        assertEquals(active, view.active());
        assertEquals(sampleCount, view.snapshot().fifteenMinutes().sampleCount());
    }
}
