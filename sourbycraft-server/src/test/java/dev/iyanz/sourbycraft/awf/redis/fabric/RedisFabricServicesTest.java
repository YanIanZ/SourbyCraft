package dev.iyanz.sourbycraft.awf.redis.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.RedisTestServer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real-Redis integration for sessions, coordination, cache and routing. */
class RedisFabricServicesTest {

    private RedisTestServer server;
    private RedisClient client;
    private FabricMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        this.server = RedisTestServer.start();
        this.client = new RedisClient(RedisClient.Endpoint.parse(this.server.uri()), 4, 2_000);
        this.metrics = new FabricMetrics();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.client != null) this.client.close();
        if (this.server != null) this.server.close();
    }

    // ------------------------------------------------------------------ settings

    @Test
    void settingsDefaultsAreSafeAndParsedIndividually() {
        final FabricSettings defaults = FabricSettings.DEFAULT;
        assertFalse(defaults.enabled(), "the fabric is off by default");
        assertTrue(defaults.anyService());
        final FabricSettings.Parsed parsed = FabricSettings.parse(Map.of(
            FabricSettings.ENABLED_KEY, true,
            FabricSettings.POOL_EVENTS_KEY, 9999,
            FabricSettings.SESSION_TTL_SECONDS_KEY, 3));
        assertTrue(parsed.settings().enabled());
        assertEquals(defaults.pools().events(), parsed.settings().pools().events(), "an invalid pool falls back");
        assertEquals(2, parsed.invalidKeys().size());
        assertTrue(parsed.settings().sessions().ttlSeconds() > parsed.settings().sessions().heartbeatSeconds());
    }

    @Test
    void aSessionTtlNotLongerThanTheHeartbeatIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new FabricSettings.SessionSettings(10, 10));
        assertThrows(IllegalArgumentException.class, () -> new FabricSettings.EventSettings(50, 32, 5, 500));
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void aSessionIsExclusiveAndATakeoverRaisesTheEpoch() throws Exception {
        final SessionFabric sessions = new SessionFabric(this.client, "t:session:", this.metrics, 30);
        final UUID profile = UUID.randomUUID();
        final ClaimResult first = sessions.claim(profile, "node-a", UUID.randomUUID());
        assertTrue(first.granted());
        final long epoch = first.claim().epoch();
        final ClaimResult duplicate = sessions.claim(profile, "node-b", UUID.randomUUID());
        assertFalse(duplicate.granted());
        assertEquals("duplicate", duplicate.reason());
        assertEquals("node-a", duplicate.claim().node());

        // Takeover only with the exact current epoch; it must increase it.
        assertFalse(sessions.reclaim(profile, epoch + 5L, "node-b", UUID.randomUUID()).granted());
        final ClaimResult takeover = sessions.reclaim(profile, epoch, "node-b", UUID.randomUUID());
        assertTrue(takeover.granted());
        assertTrue(takeover.claim().epoch() > epoch, "the epoch never resets");
        assertFalse(sessions.valid(profile, "node-a", UUID.fromString(first.claim().owner()), epoch),
            "the stale owner cannot validate after a takeover");
    }

    @Test
    void refreshAndReleaseRequireTheSameIncarnation() throws Exception {
        final SessionFabric sessions = new SessionFabric(this.client, "t:session:", this.metrics, 30);
        final UUID profile = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final ClaimResult claim = sessions.claim(profile, "node-a", owner);
        assertTrue(sessions.refresh(profile, "node-a", owner, claim.claim().epoch()));
        assertTrue(sessions.valid(profile, "node-a", owner, claim.claim().epoch()));
        assertFalse(sessions.refresh(profile, "node-b", UUID.randomUUID(), claim.claim().epoch()));
        assertFalse(sessions.release(profile, "node-b", UUID.randomUUID(), claim.claim().epoch()));
        assertTrue(sessions.release(profile, "node-a", owner, claim.claim().epoch()));
        assertFalse(sessions.valid(profile, "node-a", owner, claim.claim().epoch()));
        // The epoch counter is not deleted with the session: the next claim is strictly higher.
        final ClaimResult next = sessions.claim(profile, "node-a", owner);
        assertTrue(next.claim().epoch() > claim.claim().epoch());
    }

    @Test
    void theSameNodeMayReplaceItsOwnSessionToken() throws Exception {
        final SessionFabric sessions = new SessionFabric(this.client, "t:session:", this.metrics, 30);
        final UUID profile = UUID.randomUUID();
        final UUID firstOwner = UUID.randomUUID();
        final ClaimResult first = sessions.claim(profile, "node-a", firstOwner);
        assertTrue(first.granted());
        final ClaimResult second = sessions.claim(profile, "node-a", UUID.randomUUID());
        assertTrue(second.granted(), "a new token on the same node is not a duplicate session");
        assertEquals(first.claim().epoch(), second.claim().epoch(), "no epoch bump for the same process");
        assertFalse(sessions.claim(profile, "node-b", UUID.randomUUID()).granted(),
            "another node is still refused while the same-node claim is alive");
    }

    @Test
    void aLeaseTtlIsSecondsNotMilliseconds() throws Exception {
        final SessionFabric sessions = new SessionFabric(this.client, "t:session:", this.metrics, 30);
        final ClaimResult claim = sessions.claim(UUID.randomUUID(), "node-a", UUID.randomUUID());
        assertTrue(claim.granted());
        final long pttl = RedisReplies.number(this.client.call("PTTL", sessions.key(
            UUID.fromString(claim.claim().id()))), 0L);
        assertTrue(pttl > 20_000L, "a 30 s lease must be tens of seconds, not milliseconds: " + pttl);

        final CoordinationFabric coordination = new CoordinationFabric(this.client, "t:coord:", this.metrics, 30);
        final ClaimResult owned = coordination.claim("world", "ttl", "node-a", "owner-a");
        assertTrue(owned.granted());
        final long claimTtl = RedisReplies.number(this.client.call("PTTL", coordination.key("world", "ttl")), 0L);
        assertTrue(claimTtl > 20_000L, "a 30 s claim must be tens of seconds, not milliseconds: " + claimTtl);
    }

    // ------------------------------------------------------------------ coordination

    @Test
    void aCoordinationClaimIsFencedAndMonotonic() throws Exception {
        final CoordinationFabric coordination = new CoordinationFabric(this.client, "t:coord:", this.metrics, 30);
        final ClaimResult first = coordination.claim("profile", "p1", "node-a", "owner-a");
        assertTrue(first.granted());
        final OwnerClaim stale = first.claim();
        final ClaimResult conflict = coordination.claim("profile", "p1", "node-b", "owner-b");
        assertFalse(conflict.granted());
        assertEquals("held", conflict.reason());
        assertTrue(coordination.renew(stale));
        assertTrue(coordination.validate(stale));
        assertTrue(coordination.release(stale));
        assertFalse(coordination.validate(stale));
        final ClaimResult next = coordination.claim("profile", "p1", "node-b", "owner-b");
        assertTrue(next.granted());
        assertTrue(next.claim().fence() > stale.fence(), "the fence rises across releases");
        assertFalse(coordination.validate(stale), "a stale fence cannot commit");
        final OwnerClaim current = coordination.current("profile", "p1");
        assertEquals(next.claim().node(), current.node());
        assertEquals(next.claim().owner(), current.owner());
        assertEquals(next.claim().fence(), current.fence());
        assertEquals(next.claim().fence(), coordination.fence("profile", "p1"));
    }

    @Test
    void anExpiredClaimCanBeTakenOverButItsFenceIsRejected() throws Exception {
        final CoordinationFabric quick = new CoordinationFabric(this.client, "t:coord:", this.metrics, 5);
        final ClaimResult first = quick.claim("world", "w1", "node-a", "owner-a");
        assertTrue(first.granted());
        // Expire the lease key without touching the fence counter.
        this.client.call("DEL", quick.key("world", "w1"));
        final ClaimResult replacement = quick.claim("world", "w1", "node-b", "owner-b");
        assertTrue(replacement.granted());
        assertTrue(replacement.claim().fence() > first.claim().fence());
        assertFalse(quick.validate(first.claim()), "TTL expiry alone does not authorise the old writer");
    }

    // ------------------------------------------------------------------ cache

    @Test
    void theCacheIsBoundedVersionedAndStampedeProtected() throws Exception {
        final CacheFabric cache = new CacheFabric(this.client, "t:cache:", this.metrics,
            new FabricSettings.CacheSettings(2, 1));
        final AtomicInteger loads = new AtomicInteger();
        assertEquals("v1", cache.get("profiles", "a", () -> {
            loads.incrementAndGet();
            return "v1";
        }));
        assertEquals("v1", cache.get("profiles", "a", () -> {
            loads.incrementAndGet();
            return "v2";
        }));
        assertEquals(1, loads.get(), "the second lookup was served from cache");
        cache.put("profiles", "b", "b");
        cache.put("profiles", "c", "c");
        assertTrue(cache.size() <= 2, "the entry bound is enforced");

        // A second cache instance sees the invalidation through the version key.
        final CacheFabric other = new CacheFabric(this.client, "t:cache:", this.metrics,
            new FabricSettings.CacheSettings(16, 60));
        assertEquals("c", other.get("profiles", "c", () -> "c"));
        cache.invalidate("profiles", "c");
        // The other instance re-reads the version at most once a second; force the check.
        Thread.sleep(1_100L);
        assertNull(other.getIfPresent("profiles", "c"), "the version bump invalidated the remote copy");
    }

    @Test
    void concurrentMissesShareOneLoad() throws Exception {
        final CacheFabric cache = new CacheFabric(this.client, "t:cache:", this.metrics,
            new FabricSettings.CacheSettings(16, 60));
        final AtomicInteger loads = new AtomicInteger();
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            final java.util.List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> cache.get("routing", "n", () -> {
                    loads.incrementAndGet();
                    try {
                        Thread.sleep(100L);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return "node";
                })));
            }
            for (final var result : results) assertEquals("node", result.get());
            assertEquals(1, loads.get(), "one load for four concurrent misses");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ routing

    @Test
    void routingFindsTheLeastLoadedHealthyNodeAndTheProfileOwner() throws Exception {
        final SessionFabric sessions = new SessionFabric(this.client, "t:route:", this.metrics, 30);
        final CoordinationFabric coordination = new CoordinationFabric(this.client, "t:route:", this.metrics, 30);
        final RoutingFabric routing = new RoutingFabric(this.client, "t:route:", this.metrics, sessions, coordination, 30);
        routing.register(new RoutingFabric.NodeInfo("node-a", "10.0.0.1:25565", 100, 80, "HEALTHY", 1L));
        routing.register(new RoutingFabric.NodeInfo("node-b", "10.0.0.2:25565", 100, 10, "HEALTHY", 1L));
        routing.register(new RoutingFabric.NodeInfo("node-c", "10.0.0.3:25565", 100, 0, "DRAINING", 1L));
        assertEquals(3, routing.nodes().size());
        assertEquals("node-b", routing.availableNode(1).orElseThrow().id());
        assertEquals("node-b", routing.availableNode(85).orElseThrow().id());
        assertTrue(routing.availableNode(95).isEmpty());

        final UUID profile = UUID.randomUUID();
        final ClaimResult claim = sessions.claim(profile, "node-b", UUID.randomUUID());
        assertTrue(claim.granted());
        assertEquals("node-b", routing.profileOwner(profile).orElseThrow().node());

        routing.unregister("node-a");
        assertFalse(routing.alive("node-a"));
        assertEquals(2, routing.nodes().size());
    }

    @Test
    void theFabricProbeReportsHealthAndPoolSizes() throws Exception {
        final RedisSettings redis = new RedisSettings(this.server.uri(), "t:fabric:", 2, 2_000, 30, false);
        final FabricSettings settings = FabricSettings.parse(Map.of(
            FabricSettings.ENABLED_KEY, true,
            FabricSettings.POOL_STORAGE_KEY, 3,
            FabricSettings.POOL_COORDINATION_KEY, 2,
            FabricSettings.EVENTS_READ_BLOCK_MS_KEY, 50)).settings();
        try (RedisFabric fabric = RedisFabric.start(settings, redis, "node-test")) {
            final FabricHealth health = fabric.probe();
            assertTrue(health.reachable());
            assertEquals(3, health.pools().get(RedisWorkload.STORAGE).size());
            assertEquals(2, health.pools().get(RedisWorkload.COORDINATION).size());
            assertTrue(!"unknown".equals(health.redisVersion()), "the server names its version");
            assertTrue(fabric.describe().contains("t:fabric:"));
            assertFalse(fabric.describe().contains("password"));
        }
    }
}
