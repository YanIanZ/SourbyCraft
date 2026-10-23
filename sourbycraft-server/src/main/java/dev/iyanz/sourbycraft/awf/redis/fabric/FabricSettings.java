package dev.iyanz.sourbycraft.awf.redis.fabric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings of {@code aurora.awf.redis.fabric.*} and the Multiprofile keys that select it.
 *
 * <p>Every key is RESTART_REQUIRED: pools, consumers and scripts are created once, when the fabric
 * starts, and a reload cannot resize a live connection pool or move a consumer group. The parser is
 * deliberately independent of the configuration system so the values can be read early, before the
 * server configuration is initialised, exactly as {@link dev.iyanz.sourbycraft.awf.redis.RedisSettings}
 * is.</p>
 *
 * <p>The fabric itself is disabled by default. Disabled means no socket is opened, no thread is
 * started and no Redis key is read or written.</p>
 */
public record FabricSettings(boolean enabled, ServiceToggles services, PoolSizes pools, CacheSettings cache,
                             SessionSettings sessions, EventSettings events, CoordinationSettings coordination) {

    public static final String ENABLED_KEY = "aurora.awf.redis.fabric.enabled";
    public static final String SERVICES_SESSIONS_KEY = "aurora.awf.redis.fabric.services.sessions";
    public static final String SERVICES_COORDINATION_KEY = "aurora.awf.redis.fabric.services.coordination";
    public static final String SERVICES_EVENTS_KEY = "aurora.awf.redis.fabric.services.events";
    public static final String SERVICES_CACHE_KEY = "aurora.awf.redis.fabric.services.cache";
    public static final String SERVICES_ROUTING_KEY = "aurora.awf.redis.fabric.services.routing";
    public static final String SERVICES_TELEMETRY_KEY = "aurora.awf.redis.fabric.services.telemetry";
    public static final String POOL_STORAGE_KEY = "aurora.awf.redis.fabric.pools.storage";
    public static final String POOL_COORDINATION_KEY = "aurora.awf.redis.fabric.pools.coordination";
    public static final String POOL_EVENTS_KEY = "aurora.awf.redis.fabric.pools.events";
    public static final String POOL_CONTROL_KEY = "aurora.awf.redis.fabric.pools.control";
    public static final String CACHE_MAX_ENTRIES_KEY = "aurora.awf.redis.fabric.cache.max-entries";
    public static final String CACHE_TTL_SECONDS_KEY = "aurora.awf.redis.fabric.cache.ttl-seconds";
    public static final String SESSION_HEARTBEAT_SECONDS_KEY = "aurora.awf.redis.fabric.sessions.heartbeat-seconds";
    public static final String SESSION_TTL_SECONDS_KEY = "aurora.awf.redis.fabric.sessions.ttl-seconds";
    public static final String EVENTS_RETENTION_KEY = "aurora.awf.redis.fabric.events.retention";
    public static final String EVENTS_BATCH_KEY = "aurora.awf.redis.fabric.events.batch-size";
    public static final String EVENTS_MAX_DELIVERIES_KEY = "aurora.awf.redis.fabric.events.max-deliveries";
    public static final String EVENTS_READ_BLOCK_MS_KEY = "aurora.awf.redis.fabric.events.read-block-ms";
    public static final String COORDINATION_CLAIM_SECONDS_KEY = "aurora.awf.redis.fabric.coordination.claim-seconds";

    public static final List<String> KEYS = List.of(ENABLED_KEY,
        SERVICES_SESSIONS_KEY, SERVICES_COORDINATION_KEY, SERVICES_EVENTS_KEY, SERVICES_CACHE_KEY,
        SERVICES_ROUTING_KEY, SERVICES_TELEMETRY_KEY,
        POOL_STORAGE_KEY, POOL_COORDINATION_KEY, POOL_EVENTS_KEY, POOL_CONTROL_KEY,
        CACHE_MAX_ENTRIES_KEY, CACHE_TTL_SECONDS_KEY,
        SESSION_HEARTBEAT_SECONDS_KEY, SESSION_TTL_SECONDS_KEY,
        EVENTS_RETENTION_KEY, EVENTS_BATCH_KEY, EVENTS_MAX_DELIVERIES_KEY, EVENTS_READ_BLOCK_MS_KEY,
        COORDINATION_CLAIM_SECONDS_KEY);

    /** Which fabric services are constructed and allowed to touch Redis. */
    public record ServiceToggles(boolean sessions, boolean coordination, boolean events, boolean cache,
                                 boolean routing, boolean telemetry) {
        public ServiceToggles {
            // At least one service must be on for an enabled fabric to make sense; the runtime
            // refuses an enabled fabric whose services are all off.
        }
    }

    /** Connections per workload. Bounded: a misconfiguration cannot exhaust file descriptors. */
    public record PoolSizes(int storage, int coordination, int events, int control) {
        public static final int MAX = 64;

        public PoolSizes {
            require("storage", storage);
            require("coordination", coordination);
            require("events", events);
            require("control", control);
        }

        private static void require(final String name, final int value) {
            if (value < 1 || value > MAX) throw new IllegalArgumentException("pool " + name + " must be 1-" + MAX);
        }
    }

    /** Bounded, TTL-limited cache of metadata; never the source of truth for durable state. */
    public record CacheSettings(int maxEntries, int ttlSeconds) {
        public CacheSettings {
            if (maxEntries < 1 || maxEntries > 1_000_000) {
                throw new IllegalArgumentException("cache.max-entries must be 1-1000000");
            }
            if (ttlSeconds < 1 || ttlSeconds > 86_400) {
                throw new IllegalArgumentException("cache.ttl-seconds must be 1-86400");
            }
        }
    }

    /**
     * @param heartbeatSeconds how often a node renews its session claims
     * @param ttlSeconds how long a claim survives without renewal; must outlive one heartbeat
     */
    public record SessionSettings(int heartbeatSeconds, int ttlSeconds) {
        public SessionSettings {
            if (heartbeatSeconds < 1 || heartbeatSeconds > 3600) {
                throw new IllegalArgumentException("sessions.heartbeat-seconds must be 1-3600");
            }
            if (ttlSeconds < 5 || ttlSeconds > 86_400) {
                throw new IllegalArgumentException("sessions.ttl-seconds must be 5-86400");
            }
            if (ttlSeconds <= heartbeatSeconds) {
                throw new IllegalArgumentException("sessions.ttl-seconds must exceed heartbeat-seconds");
            }
        }
    }

    /**
     * @param retention approximate maximum stream length ({@code MAXLEN ~}); Redis trims whole nodes
     * @param batchSize events per {@code XREADGROUP}
     * @param maxDeliveries deliveries before an event is moved to the dead-letter stream
     * @param readBlockMillis server-side block for one read; bounds how long a consumer thread parks
     */
    public record EventSettings(int retention, int batchSize, int maxDeliveries, int readBlockMillis) {
        public EventSettings {
            if (retention < 100 || retention > 10_000_000) {
                throw new IllegalArgumentException("events.retention must be 100-10000000");
            }
            if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("events.batch-size must be 1-500");
            if (maxDeliveries < 1 || maxDeliveries > 100) {
                throw new IllegalArgumentException("events.max-deliveries must be 1-100");
            }
            if (readBlockMillis < 1 || readBlockMillis > 5000) {
                throw new IllegalArgumentException("events.read-block-ms must be 1-5000");
            }
        }
    }

    /**
     * @param claimSeconds how long another node's claim is honoured before it can be taken over;
     *     a takeover always increments the epoch, so a stale writer still fails its fence check
     */
    public record CoordinationSettings(int claimSeconds) {
        public CoordinationSettings {
            if (claimSeconds < 5 || claimSeconds > 86_400) {
                throw new IllegalArgumentException("coordination.claim-seconds must be 5-86400");
            }
        }
    }

    public static final FabricSettings DEFAULT = new FabricSettings(false,
        // Events ship off: the stream has no consumer in this build, and an unconsumed stream grows
        // to its retention limit and then refuses every publish. Enable only with a consumer.
        new ServiceToggles(true, true, false, false, true, true),
        new PoolSizes(8, 4, 2, 2),
        new CacheSettings(10_000, 60),
        new SessionSettings(10, 45),
        new EventSettings(10_000, 32, 5, 500),
        new CoordinationSettings(45));

    public FabricSettings {
        if (services == null || pools == null || cache == null || sessions == null || events == null
            || coordination == null) {
            throw new NullPointerException("fabric settings");
        }
    }

    /** Any service enabled. */
    public boolean anyService() {
        return this.services.sessions() || this.services.coordination() || this.services.events()
            || this.services.cache() || this.services.routing() || this.services.telemetry();
    }

    /** Settings and the keys that were present but unusable (each falls back to its default). */
    public record Parsed(FabricSettings settings, List<String> invalidKeys) {
        public Parsed {
            invalidKeys = List.copyOf(invalidKeys);
        }
    }

    public static Parsed parse(final Map<String, Object> values) {
        final List<String> invalid = new ArrayList<>();
        final boolean enabled = flag(values, ENABLED_KEY, DEFAULT.enabled(), invalid);
        final ServiceToggles services = new ServiceToggles(
            flag(values, SERVICES_SESSIONS_KEY, DEFAULT.services().sessions(), invalid),
            flag(values, SERVICES_COORDINATION_KEY, DEFAULT.services().coordination(), invalid),
            flag(values, SERVICES_EVENTS_KEY, DEFAULT.services().events(), invalid),
            flag(values, SERVICES_CACHE_KEY, DEFAULT.services().cache(), invalid),
            flag(values, SERVICES_ROUTING_KEY, DEFAULT.services().routing(), invalid),
            flag(values, SERVICES_TELEMETRY_KEY, DEFAULT.services().telemetry(), invalid));
        final PoolSizes pools = new PoolSizes(
            whole(values, POOL_STORAGE_KEY, DEFAULT.pools().storage(), 1, PoolSizes.MAX, invalid),
            whole(values, POOL_COORDINATION_KEY, DEFAULT.pools().coordination(), 1, PoolSizes.MAX, invalid),
            whole(values, POOL_EVENTS_KEY, DEFAULT.pools().events(), 1, PoolSizes.MAX, invalid),
            whole(values, POOL_CONTROL_KEY, DEFAULT.pools().control(), 1, PoolSizes.MAX, invalid));
        final int heartbeat = whole(values, SESSION_HEARTBEAT_SECONDS_KEY, DEFAULT.sessions().heartbeatSeconds(), 1, 3600, invalid);
        int ttl = whole(values, SESSION_TTL_SECONDS_KEY, DEFAULT.sessions().ttlSeconds(), 5, 86_400, invalid);
        if (ttl <= heartbeat) {
            invalid.add(SESSION_TTL_SECONDS_KEY);
            ttl = Math.max(DEFAULT.sessions().ttlSeconds(), heartbeat + 5);
        }
        final CacheSettings cache = new CacheSettings(
            whole(values, CACHE_MAX_ENTRIES_KEY, DEFAULT.cache().maxEntries(), 1, 1_000_000, invalid),
            whole(values, CACHE_TTL_SECONDS_KEY, DEFAULT.cache().ttlSeconds(), 1, 86_400, invalid));
        final EventSettings events = new EventSettings(
            whole(values, EVENTS_RETENTION_KEY, DEFAULT.events().retention(), 100, 10_000_000, invalid),
            whole(values, EVENTS_BATCH_KEY, DEFAULT.events().batchSize(), 1, 500, invalid),
            whole(values, EVENTS_MAX_DELIVERIES_KEY, DEFAULT.events().maxDeliveries(), 1, 100, invalid),
            whole(values, EVENTS_READ_BLOCK_MS_KEY, DEFAULT.events().readBlockMillis(), 1, 5000, invalid));
        final CoordinationSettings coordination = new CoordinationSettings(
            whole(values, COORDINATION_CLAIM_SECONDS_KEY, DEFAULT.coordination().claimSeconds(), 5, 86_400, invalid));
        return new Parsed(new FabricSettings(enabled, services, pools, cache, new SessionSettings(heartbeat, ttl), events,
            coordination), invalid);
    }

    /** Reads the fabric keys from a flattened map; unusable keys fall back individually. */
    public static FabricSettings from(final Map<String, Object> values) {
        return parse(values).settings();
    }

    /** Prefix used for every key the fabric owns; never collides with the world store's keys. */
    public static String namespace(final String keyPrefix) {
        return keyPrefix + "fabric:";
    }

    private static boolean flag(final Map<String, Object> values, final String key, final boolean fallback,
                                final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        invalid.add(key);
        return fallback;
    }

    private static int whole(final Map<String, Object> values, final String key, final int fallback, final int min,
                             final int max, final List<String> invalid) {
        final Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number && number.doubleValue() == Math.floor(number.doubleValue())
            && number.longValue() >= min && number.longValue() <= max) {
            return number.intValue();
        }
        invalid.add(key);
        return fallback;
    }

    /** Convenience for tests and callers that already hold a flat map. */
    public static Map<String, Object> defaultsAsMap() {
        final Map<String, Object> values = new HashMap<>();
        values.put(ENABLED_KEY, DEFAULT.enabled());
        values.put(SERVICES_SESSIONS_KEY, DEFAULT.services().sessions());
        values.put(SERVICES_COORDINATION_KEY, DEFAULT.services().coordination());
        values.put(SERVICES_EVENTS_KEY, DEFAULT.services().events());
        values.put(SERVICES_CACHE_KEY, DEFAULT.services().cache());
        values.put(SERVICES_ROUTING_KEY, DEFAULT.services().routing());
        values.put(SERVICES_TELEMETRY_KEY, DEFAULT.services().telemetry());
        values.put(POOL_STORAGE_KEY, DEFAULT.pools().storage());
        values.put(POOL_COORDINATION_KEY, DEFAULT.pools().coordination());
        values.put(POOL_EVENTS_KEY, DEFAULT.pools().events());
        values.put(POOL_CONTROL_KEY, DEFAULT.pools().control());
        values.put(CACHE_MAX_ENTRIES_KEY, DEFAULT.cache().maxEntries());
        values.put(CACHE_TTL_SECONDS_KEY, DEFAULT.cache().ttlSeconds());
        values.put(SESSION_HEARTBEAT_SECONDS_KEY, DEFAULT.sessions().heartbeatSeconds());
        values.put(SESSION_TTL_SECONDS_KEY, DEFAULT.sessions().ttlSeconds());
        values.put(EVENTS_RETENTION_KEY, DEFAULT.events().retention());
        values.put(EVENTS_BATCH_KEY, DEFAULT.events().batchSize());
        values.put(EVENTS_MAX_DELIVERIES_KEY, DEFAULT.events().maxDeliveries());
        values.put(EVENTS_READ_BLOCK_MS_KEY, DEFAULT.events().readBlockMillis());
        values.put(COORDINATION_CLAIM_SECONDS_KEY, DEFAULT.coordination().claimSeconds());
        return values;
    }
}
