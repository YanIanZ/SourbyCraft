package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RegionLocatorTest {

    @Test
    void blockCoordinatesFloorToChunkCoordinatesForNegatives() {
        assertEquals(0, RegionLocator.chunkOf(0));
        assertEquals(0, RegionLocator.chunkOf(15));
        assertEquals(1, RegionLocator.chunkOf(16));
        assertEquals(-1, RegionLocator.chunkOf(-1));
        assertEquals(-1, RegionLocator.chunkOf(-16));
        assertEquals(-2, RegionLocator.chunkOf(-17));
    }

    @Test
    void locateWithoutAServerReportsNoServerRatherThanTouchingWorldState() {
        final RegionLocator.Result result = RegionLocator.locate("world", 16, -32, 0L);
        assertEquals(RegionLocator.Status.NO_SERVER, result.status());
    }

    @Test
    void newestGenerationWinsWhenARegionRotatesAfterRetirement() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        final long old = registry.activate(world, 7L, new RegionTickMetricsHolder(), 0L);
        registry.retire(old, RegionMetricsRegistry.RetirementReason.INACTIVE, 1L);
        final long current = registry.activate(world, 7L, new RegionTickMetricsHolder(), 2L);

        final RegionLocator.Generation generation = RegionLocator.bestGeneration(registry, world, 7L, 2L);

        assertEquals(current, generation.generationId());
        assertTrue(generation.active());
        assertNotNull(generation.snapshot());
    }

    @Test
    void bestGenerationReturnsNullWhenNoGenerationMatches() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        registry.activate(world, 7L, new RegionTickMetricsHolder(), 0L);

        assertNull(RegionLocator.bestGeneration(registry, world, 99L, 0L));
        assertNull(RegionLocator.bestGeneration(registry, 12_345L, 7L, 0L));
    }

    @Test
    void aLookupRefreshesNoGenerationNotEvenTheOneItReturns() {
        // Codex review P2: the lookup used to call the synchronized refreshSnapshot() on every
        // retained generation, rebuilding unrelated windows and contending with tick completion.
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long world = registry.newWorldId();
        final RegionTickMetricsHolder target = new RegionTickMetricsHolder();
        final RegionTickMetricsHolder unrelated = new RegionTickMetricsHolder();
        registry.activate(world, 7L, target, 0L);
        registry.activate(world, 8L, unrelated, 0L);
        final long later = 5_000_000_000L;   // a later publication epoch: a refresh would publish

        final RegionLocator.Generation found = RegionLocator.bestGeneration(registry, world, 7L, later);

        assertNotNull(found);
        assertEquals(0L, unrelated.current().snapshot(later).sequence(), "unrelated generation refreshed");
        assertEquals(0L, target.current().snapshot(later).sequence(), "target generation refreshed");
        assertEquals(0L, found.snapshot().sequence(), "the collector-published snapshot is returned as is");
    }

    // ---- injectable resolution: world -> region -> telemetry ----

    private static final long RETENTION = TimeUnit.MINUTES.toNanos(15L) + TimeUnit.SECONDS.toNanos(1L);

    /** A world whose region lookups answer from a script, one id per call (last one repeats). */
    private record FakeWorld(String name, long telemetryWorldId, Deque<Long> answers, int[] calls)
            implements RegionLocator.WorldHandle {
        static FakeWorld of(final String name, final long worldId, final Long... ids) {
            return new FakeWorld(name, worldId, new ArrayDeque<>(Arrays.asList(ids)), new int[1]);
        }

        @Override
        public long regionIdAt(final int chunkX, final int chunkZ) {
            this.calls[0]++;
            return this.answers.size() > 1 ? this.answers.poll() : this.answers.peek();
        }
    }

    private static RegionLocator.Topology server(final RegionLocator.WorldHandle... worlds) {
        return () -> List.of(worlds);
    }

    @Test
    void noServerAndAnUnknownWorldAreReportedWithoutTouchingTheRegistry() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        assertEquals(RegionLocator.Status.NO_SERVER,
            RegionLocator.locate(() -> null, registry, "world", 0, 0, 0L).status());
        assertEquals(RegionLocator.Status.UNKNOWN_WORLD,
            RegionLocator.locate(server(FakeWorld.of("world", 1L, 7L)), registry, "nether", 0, 0, 0L).status());
    }

    @Test
    void worldNamesResolveCaseInsensitivelyLikeBukkit() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long worldId = registry.newWorldId();
        registry.activate(worldId, 7L, new RegionTickMetricsHolder(), 0L);
        final RegionLocator.Topology topology = server(FakeWorld.of("world_nether", 99L, 1L),
            FakeWorld.of("World", worldId, 7L));

        for (final String typed : List.of("World", "world", "WORLD", "wOrLd")) {
            final RegionLocator.Result result = RegionLocator.locate(topology, registry, typed, 16, -32, 0L);
            assertEquals(RegionLocator.Status.FOUND, result.status(), typed);
            assertEquals(worldId, result.worldId(), typed);
        }
        assertEquals(RegionLocator.Status.UNKNOWN_WORLD,
            RegionLocator.locate(topology, registry, "worl", 0, 0, 0L).status(), "no prefix matching");
    }

    @Test
    void aFoundRegionCarriesItsIdentityChunkAndPublishedSnapshot() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long worldId = registry.newWorldId();
        final long generation = registry.activate(worldId, 7L, new RegionTickMetricsHolder(), 0L);

        final RegionLocator.Result result = RegionLocator.locate(
            server(FakeWorld.of("world", worldId, 7L)), registry, "world", 16, -32, 0L);

        assertEquals(RegionLocator.Status.FOUND, result.status());
        assertEquals(7L, result.regionId());
        assertEquals(generation, result.generationId());
        assertTrue(result.active());
        assertNotNull(result.snapshot());
        assertEquals(1, result.chunkX());
        assertEquals(-2, result.chunkZ());
    }

    @Test
    void aTransitionThatUnpublishesTheOwnerIsReadAgainOnce() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long worldId = registry.newWorldId();
        registry.activate(worldId, 7L, new RegionTickMetricsHolder(), 0L);
        final FakeWorld world = FakeWorld.of("world", worldId, 0L, 7L);   // null owner, then the region

        final RegionLocator.Result result = RegionLocator.locate(server(world), registry, "world", 0, 0, 0L);

        assertEquals(RegionLocator.Status.FOUND, result.status());
        assertEquals(2, world.calls()[0]);
    }

    @Test
    void anOwnerStillMissingAfterTheRereadIsNoRegionNotAnError() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final FakeWorld world = FakeWorld.of("world", 3L, 0L);

        final RegionLocator.Result result = RegionLocator.locate(server(world), registry, "world", 16, -32, 0L);

        assertEquals(RegionLocator.Status.NO_REGION_AT_CHUNK, result.status());
        assertEquals(3L, result.worldId());
        assertEquals(1, result.chunkX());
        assertEquals(-2, result.chunkZ());
        assertEquals(2, world.calls()[0], "exactly one re-read");
    }

    @Test
    void aRegionWithoutAnyGenerationHasNoTelemetry() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final RegionLocator.Result result = RegionLocator.locate(
            server(FakeWorld.of("world", 3L, 7L)), registry, "world", 0, 0, 0L);

        assertEquals(RegionLocator.Status.NO_TELEMETRY, result.status());
        assertEquals(7L, result.regionId());
    }

    @Test
    void aRetainedOnlyGenerationIsReportedInactiveUntilItExpires() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long worldId = registry.newWorldId();
        final long retired = registry.activate(worldId, 7L, new RegionTickMetricsHolder(), 0L);
        registry.retire(retired, RegionMetricsRegistry.RetirementReason.INACTIVE, 1L);
        final RegionLocator.Topology topology = server(FakeWorld.of("world", worldId, 7L));

        final RegionLocator.Result retained = RegionLocator.locate(topology, registry, "world", 0, 0, 2L);
        assertEquals(RegionLocator.Status.FOUND, retained.status());
        assertEquals(retired, retained.generationId());
        assertFalse(retained.active());

        final RegionLocator.Result expired =
            RegionLocator.locate(topology, registry, "world", 0, 0, 1L + RETENTION + 1L);
        assertEquals(RegionLocator.Status.NO_TELEMETRY, expired.status());
    }

    @Test
    void theReadOnlyLookupDoesNotRemoveAnExpiredGeneration() {
        final RegionMetricsRegistry registry = new RegionMetricsRegistry();
        final long worldId = registry.newWorldId();
        final long retired = registry.activate(worldId, 7L, new RegionTickMetricsHolder(), 0L);
        registry.retire(retired, RegionMetricsRegistry.RetirementReason.INACTIVE, 1L);
        final long late = 1L + RETENTION + 1L;

        assertNull(RegionLocator.bestGeneration(registry, worldId, 7L, late));
        final int[] stillThere = new int[1];
        registry.forEachUnexpiredOf(worldId, 7L, 0L, view -> stillThere[0]++);   // as of before expiry
        assertEquals(1, stillThere[0], "expiry removal stays with the collector's sweep");
    }
}
