package dev.iyanz.sourbycraft.awf.redis.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.RedisTestServer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real-Redis integration for the stream transport: at-least-once, idempotency and dead letters. */
class EventFabricTest {

    private RedisTestServer server;
    private RedisClient client;
    private FabricMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        this.server = RedisTestServer.start();
        this.client = new RedisClient(RedisClient.Endpoint.parse(this.server.uri()), 2, 2_000);
        this.metrics = new FabricMetrics();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.client != null) this.client.close();
        if (this.server != null) this.server.close();
    }

    private EventFabric events(final int maxDeliveries) {
        return new EventFabric(this.client, "t:events:", this.metrics,
            new FabricSettings.EventSettings(1_000, 10, maxDeliveries, 50), 0L);
    }

    @Test
    void publishConsumeAndAcknowledge() throws Exception {
        final EventFabric events = events(3);
        final EventFabric.EventConsumer consumer = events.consumer("awf", "c1");
        events.publish(FabricEvent.of(FabricEventType.PROFILE_CREATED, "node-a", Map.of("profile", "p1")));
        final AtomicInteger handled = new AtomicInteger();
        assertEquals(1, consumer.pollOnce(event -> {
            assertEquals(FabricEventType.PROFILE_CREATED, event.type());
            assertEquals("p1", event.payload().get("profile"));
            handled.incrementAndGet();
            return true;
        }));
        assertEquals(1, handled.get());
        assertEquals(0, consumer.pollOnce(event -> true), "an acknowledged entry is not delivered again");
        assertEquals(1L, this.metrics.eventsPublished());
        assertEquals(1L, this.metrics.snapshot().eventsConsumed());
    }

    @Test
    void aHandlerFailureLeavesTheEventPendingForRetry() throws Exception {
        final EventFabric events = events(3);
        final EventFabric.EventConsumer consumer = events.consumer("awf", "c1");
        events.publish(FabricEvent.of(FabricEventType.PROFILE_ACTIVATED, "node-a", Map.of("profile", "p1")));
        assertEquals(0, consumer.pollOnce(event -> false));
        assertEquals(1L, this.metrics.snapshot().eventsRetried());
        final AtomicInteger attempts = new AtomicInteger();
        assertEquals(1, consumer.pollOnce(event -> {
            attempts.incrementAndGet();
            return true;
        }));
        assertEquals(1, attempts.get());
    }

    @Test
    void aRedeliveredEventDoesNotApplyItsEffectTwice() throws Exception {
        final EventFabric events = events(3);
        final EventFabric.EventConsumer consumer = events.consumer("awf", "c1");
        final FabricEvent event = FabricEvent.of(FabricEventType.PROFILE_SWITCH_COMPLETED, "node-a",
            Map.of("profile", "p1"));
        events.publish(event);
        final AtomicInteger effects = new AtomicInteger();
        assertEquals(1, consumer.pollOnce(delivered -> {
            effects.incrementAndGet();
            return true;
        }));
        // Re-deliver the same event id under a new stream entry, as a producer retry would.
        final List<Object> args = new java.util.ArrayList<>(List.of("XADD", events.stream(), "*"));
        event.fields().forEach((key, value) -> {
            args.add(key);
            args.add(value);
        });
        this.client.call(args.toArray());
        assertEquals(1, consumer.pollOnce(delivered -> {
            effects.incrementAndGet();
            return true;
        }));
        assertEquals(1, effects.get(), "the effect ran once");
        assertEquals(1L, this.metrics.snapshot().duplicatesSuppressed());
    }

    @Test
    void anEventOverItsDeliveryBudgetIsDeadLettered() throws Exception {
        final EventFabric events = events(1);
        final EventFabric.EventConsumer consumer = events.consumer("awf", "c1");
        events.publish(FabricEvent.of(FabricEventType.WORLD_LOAD_REQUESTED, "node-a", Map.of("world", "w1")));
        assertEquals(0, consumer.pollOnce(event -> false));      // delivery 1, left pending
        assertEquals(1, consumer.pollOnce(event -> false));      // delivery 2 > budget -> dead letter + ack
        assertEquals(1L, this.metrics.snapshot().deadLettered());
        assertEquals(1L, RedisReplies.number(this.client.call("XLEN", events.deadStream()), 0L));
        assertEquals(0, consumer.pollOnce(event -> true), "the dead-lettered entry was acknowledged");
    }

    @Test
    void reachingTheRetentionBoundRefusesNewPublishes() throws Exception {
        final EventFabric events = new EventFabric(this.client, "t:events:", this.metrics,
            new FabricSettings.EventSettings(100, 10, 3, 50), 0L);
        for (int i = 0; i < 100; i++) {
            events.publish(FabricEvent.of(FabricEventType.NODE_HEALTH_CHANGED, "node-a", Map.of("i", Integer.toString(i))));
        }
        final FabricBackpressureException refused = assertThrows(FabricBackpressureException.class,
            () -> events.publish(FabricEvent.of(FabricEventType.NODE_HEALTH_CHANGED, "node-a", Map.of())));
        assertTrue(refused.getMessage().contains("retention"));
        assertEquals(1L, this.metrics.snapshot().backpressureRejections());
    }

    @Test
    void anUnknownEventTypeIsAcknowledgedInsteadOfBlockingTheGroup() throws Exception {
        final EventFabric events = events(3);
        final EventFabric.EventConsumer consumer = events.consumer("awf", "c1");
        this.client.call("XADD", events.stream(), "*", "id", "x", "type", "SOMETHING_NEW", "schema", "1",
            "at", "0", "source", "node-future");
        assertEquals(1, consumer.pollOnce(event -> {
            throw new AssertionError("an unknown type must not reach the handler");
        }));
    }
}
