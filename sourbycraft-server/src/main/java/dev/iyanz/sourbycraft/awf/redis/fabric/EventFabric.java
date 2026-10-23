package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis Streams transport for internal AWF events.
 *
 * <p>Delivery is <strong>at-least-once</strong>, never exactly-once: a consumer acknowledges an entry
 * only after its effect is durable, and records the event id first so a redelivery is suppressed.
 * Entries that no consumer acknowledges are reclaimed after an idle time, retried up to
 * {@code max-deliveries}, then copied to a dead-letter stream and acknowledged. The stream is trimmed
 * to an approximate length; when the backlog reaches that length, publishing is refused with
 * {@link FabricBackpressureException} instead of growing memory without bound.</p>
 *
 * <p>No command is executed remotely: an event carries data, and the consumer decides what to do with
 * it. There is no "run this command on node X" event.</p>
 */
public final class EventFabric {

    public static final String DEAD_SUFFIX = ":dead";
    private static final long DELIVERY_CLAIM_IDLE_MILLIS = 60_000L;
    private static final int SEEN_TTL_SECONDS = 86_400;

    /** Applies one event. Returning {@code false} or throwing leaves it pending for a retry. */
    @FunctionalInterface
    public interface Handler {
        boolean onEvent(FabricEvent event) throws Exception;
    }

    /** One stream entry with its Redis id. */
    public record Entry(String redisId, FabricEvent event, String rawType) {}

    private final RedisClient client;
    private final String prefix;
    private final FabricMetrics metrics;
    private final FabricSettings.EventSettings settings;
    private final long claimIdleMillis;

    EventFabric(final RedisClient client, final String prefix, final FabricMetrics metrics,
                final FabricSettings.EventSettings settings) {
        this(client, prefix, metrics, settings, DELIVERY_CLAIM_IDLE_MILLIS);
    }

    /** Test seam: the idle time before a pending entry is reclaimed from a stalled consumer. */
    EventFabric(final RedisClient client, final String prefix, final FabricMetrics metrics,
                final FabricSettings.EventSettings settings, final long claimIdleMillis) {
        this.client = client;
        this.prefix = prefix;
        this.metrics = metrics;
        this.settings = settings;
        this.claimIdleMillis = Math.max(0L, claimIdleMillis);
    }

    public String stream() {
        return this.prefix + "events";
    }

    public String deadStream() {
        return stream() + DEAD_SUFFIX;
    }

    /**
     * Publishes one event, trimmed to the retention window. Refuses with
     * {@link FabricBackpressureException} when the stream already holds {@code retention} entries.
     */
    public void publish(final FabricEvent event) throws IOException {
        final long backlog = backlog();
        this.metrics.streamBacklog(backlog);
        if (backlog >= this.settings.retention()) {
            this.metrics.backpressure();
            throw new FabricBackpressureException("the AWF event stream holds " + backlog
                + " entries (retention " + this.settings.retention() + "); the event was not published");
        }
        final List<Object> args = new ArrayList<>();
        args.add("XADD");
        args.add(stream());
        args.add("MAXLEN");
        args.add("~");
        args.add(Integer.toString(this.settings.retention()));
        args.add("*");
        for (final Map.Entry<String, String> field : event.fields().entrySet()) {
            args.add(field.getKey());
            args.add(field.getValue());
        }
        this.client.call(args.toArray());
        this.metrics.eventPublished();
    }

    /** Approximate stream length. */
    public long backlog() throws IOException {
        final long length = RedisReplies.number(this.client.call("XLEN", stream()), 0L);
        this.metrics.streamBacklog(length);
        return length;
    }

    /** Creates (idempotently) a consumer group that starts at the oldest retained entry. */
    public EventConsumer consumer(final String group, final String consumer) throws IOException {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(consumer, "consumer");
        if (group.isEmpty() || group.length() > 64 || consumer.isEmpty() || consumer.length() > 64) {
            throw new IllegalArgumentException("group and consumer must be 1-64 characters");
        }
        try {
            this.client.call("XGROUP", "CREATE", stream(), group, "0", "MKSTREAM");
        } catch (final RedisClient.RedisError busy) {
            if (busy.getMessage() == null || !busy.getMessage().startsWith("BUSYGROUP")) throw busy;
        }
        return new EventConsumer(group, consumer);
    }

    /**
     * One consumer. Not thread safe: a group/consumer pair has exactly one owner thread, which is what
     * makes the pending-entry recovery deterministic.
     */
    public final class EventConsumer implements AutoCloseable {

        private final String group;
        private final String consumer;
        private volatile boolean closed;

        private EventConsumer(final String group, final String consumer) {
            this.group = group;
            this.consumer = consumer;
        }

        public String group() {
            return this.group;
        }

        public String consumer() {
            return this.consumer;
        }

        /**
         * Reclaims stale entries, reads new ones, and applies the handler with idempotency and a
         * delivery budget. Returns the number of entries that were acknowledged.
         *
         * <p>Blocks for at most {@code read-block-ms} when there is nothing to read; call it only on
         * a dedicated I/O worker.</p>
         */
        public int pollOnce(final Handler handler) throws IOException {
            if (this.closed) return 0;
            int acknowledged = 0;
            for (final Entry entry : reclaimStale()) {
                if (handle(entry, handler)) acknowledged++;
            }
            for (final Entry entry : readNew()) {
                if (handle(entry, handler)) acknowledged++;
            }
            EventFabric.this.metrics.consumerLag(Math.max(0L, backlog()));
            return acknowledged;
        }

        private boolean handle(final Entry entry, final Handler handler) {
            if (this.closed) return false;
            if (entry.event() == null) {
                // An unknown or malformed entry must be acknowledged, or it would block the group forever.
                try {
                    ack(entry.redisId());
                    return true;
                } catch (final IOException failed) {
                    return false;
                }
            }
            final int deliveries = deliveries(entry.redisId());
            if (deliveries > EventFabric.this.settings.maxDeliveries()) {
                try {
                    deadLetter(entry, "deliveries " + deliveries);
                    ack(entry.redisId());
                    return true;
                } catch (final IOException failed) {
                    return false;
                }
            }
            final String seenKey = seenKey(entry.event().id());
            try {
                final Object first = EventFabric.this.client.call("SET", seenKey, "pending", "NX", "EX",
                    Integer.toString(SEEN_TTL_SECONDS));
                if (first == null) {
                    // A previous delivery exists. "done" means the effect completed; "pending" means
                    // that delivery died before acknowledging, so the effect must be attempted again.
                    final String state = RedisReplies.text(EventFabric.this.client.call("GET", seenKey));
                    if ("done".equals(state)) {
                        EventFabric.this.metrics.duplicateSuppressed();
                        ack(entry.redisId());
                        return true;
                    }
                }
            } catch (final IOException failed) {
                return false;
            }
            try {
                if (!handler.onEvent(entry.event())) {
                    EventFabric.this.metrics.eventRetried();
                    return false;      // stays pending, reclaimed after the idle time
                }
            } catch (final Exception failed) {
                EventFabric.this.metrics.eventRetried();
                return false;
            }
            try {
                EventFabric.this.client.call("SET", seenKey, "done", "EX", Integer.toString(SEEN_TTL_SECONDS));
                ack(entry.redisId());
                EventFabric.this.metrics.eventConsumed();
                return true;
            } catch (final IOException failed) {
                return false;
            }
        }

        /** Acknowledges one entry; safe to call twice (the second call is a no-op). */
        public void ack(final String redisId) throws IOException {
            this.client().call("XACK", stream(), this.group, redisId);
        }

        /** Moves an entry to the dead-letter stream and acknowledges it. */
        public void deadLetter(final Entry entry, final String reason) throws IOException {
            final Map<String, String> fields = entry.event() == null
                ? Map.of("id", entry.redisId(), "type", entry.rawType() == null ? "unknown" : entry.rawType(),
                    "raw", entry.redisId())
                : entry.event().fields();
            final List<Object> args = new ArrayList<>(List.of("XADD", deadStream(),
                "MAXLEN", "~", Integer.toString(Math.min(10_000, EventFabric.this.settings.retention())), "*",
                "dead-id", entry.redisId(), "dead-reason", reason, "dead-consumer", this.consumer));
            for (final Map.Entry<String, String> field : fields.entrySet()) {
                args.add(field.getKey());
                args.add(field.getValue());
            }
            this.client().call(args.toArray());
            EventFabric.this.metrics.deadLettered();
        }

        private int deliveries(final String redisId) {
            try {
                final Object reply = this.client().call("XPENDING", stream(), this.group, redisId, redisId, "1");
                final List<Object> rows = RedisReplies.list(reply);
                if (rows.isEmpty()) return 1;
                final List<Object> row = RedisReplies.list(rows.getFirst());
                return row.size() >= 4 ? (int) RedisReplies.number(row.get(3), 1L) : 1;
            } catch (final IOException failed) {
                return 1;
            }
        }

        private List<Entry> readNew() throws IOException {
            // A server-side BLOCK longer than the socket timeout would turn every empty poll into an
            // IOException and close the connection; keep it strictly below the client's timeout.
            final int block = Math.max(1, Math.min(EventFabric.this.settings.readBlockMillis(),
                EventFabric.this.client.timeoutMillis() - 100));
            final Object reply = this.client().call("XREADGROUP", "GROUP", this.group, this.consumer,
                "COUNT", Integer.toString(EventFabric.this.settings.batchSize()),
                "BLOCK", Integer.toString(block),
                "STREAMS", stream(), ">");
            return decodeReadGroup(reply);
        }

        private List<Entry> reclaimStale() throws IOException {
            final Object reply = this.client().call("XAUTOCLAIM", stream(), this.group, this.consumer,
                Long.toString(EventFabric.this.claimIdleMillis), "0-0",
                "COUNT", Integer.toString(EventFabric.this.settings.batchSize()));
            final List<Object> parts = RedisReplies.list(reply);
            if (parts.size() < 2) return List.of();
            return decodeEntries(parts.get(1));
        }

        @Override
        public void close() {
            this.closed = true;
        }

        private RedisClient client() {
            return EventFabric.this.client;
        }

        private String seenKey(final String eventId) {
            return EventFabric.this.prefix + "seen:" + this.group + ":" + eventId;
        }
    }

    private List<Entry> decodeReadGroup(final Object reply) {
        final List<Entry> entries = new ArrayList<>();
        for (final Object stream : RedisReplies.list(reply)) {
            final List<Object> parts = RedisReplies.list(stream);
            if (parts.size() < 2) continue;
            entries.addAll(decodeEntries(parts.get(1)));
        }
        return entries;
    }

    private List<Entry> decodeEntries(final Object reply) {
        final List<Entry> entries = new ArrayList<>();
        for (final Object raw : RedisReplies.list(reply)) {
            final List<Object> parts = RedisReplies.list(raw);
            if (parts.size() < 2) continue;
            final String redisId = RedisReplies.text(parts.get(0));
            final Map<String, String> fields = new LinkedHashMap<>();
            final List<Object> flat = RedisReplies.list(parts.get(1));
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                final String key = RedisReplies.text(flat.get(i));
                if (key != null) fields.put(key, RedisReplies.text(flat.get(i + 1)));
            }
            final FabricEvent event = FabricEvent.from(fields);
            entries.add(new Entry(redisId == null ? "?" : redisId, event, fields.get("type")));
        }
        return entries;
    }
}
