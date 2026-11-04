package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Owner of the Aurora Redis Fabric: the workload-isolated clients and the services built on them.
 *
 * <p>Lifecycle: created once during startup from {@code aurora.awf.redis.fabric.*}, never resized.
 * Each workload has its own bounded {@link RedisClient}; the STORAGE client is the one the AWF Redis
 * world backend may share ({@link #storageClient()}), so a stream consumer cannot take the last world
 * connection. Every operation is blocking by nature (RESP2 over blocking sockets), which is why this
 * class is documented as I/O-worker-only: no region, entity, global-tick or Netty thread may call it.
 * That boundary is enforced by the callers, not by this class, and is stated again in
 * {@code docs/awf/multiprofile/REDIS-FABRIC.md}.</p>
 *
 * <p>{@link #close()} sets one shutdown deadline on every client, so a final call cannot hold the
 * process open past the AWF shutdown budget, and then closes them. Repeated close is safe.</p>
 */
public final class RedisFabric implements AutoCloseable {

    private static volatile RedisFabric running;

    private final FabricSettings settings;
    private final RedisSettings redisSettings;
    private final String nodeId;
    private final String prefix;
    private final Map<RedisWorkload, RedisClient> clients;
    private final FabricMetrics metrics;
    private final SessionFabric sessions;
    private final CoordinationFabric coordination;
    private final EventFabric events;
    private final CacheFabric cache;
    private final RoutingFabric routing;
    private volatile boolean closed;

    private RedisFabric(final FabricSettings settings, final RedisSettings redisSettings, final String nodeId,
                        final String incarnation, final String previousIncarnation,
                        final Map<RedisWorkload, RedisClient> clients) {
        this.settings = settings;
        this.redisSettings = redisSettings;
        this.nodeId = nodeId;
        this.prefix = FabricSettings.namespace(redisSettings.keyPrefix());
        this.clients = clients;
        this.metrics = new FabricMetrics();
        this.sessions = new SessionFabric(clients.get(RedisWorkload.COORDINATION), this.prefix, this.metrics,
            settings.sessions().ttlSeconds(), incarnation, previousIncarnation);
        this.coordination = new CoordinationFabric(clients.get(RedisWorkload.COORDINATION), this.prefix, this.metrics,
            settings.coordination().claimSeconds());
        this.events = new EventFabric(clients.get(RedisWorkload.EVENTS), this.prefix, this.metrics, settings.events());
        this.cache = new CacheFabric(clients.get(RedisWorkload.CONTROL), this.prefix, this.metrics, settings.cache());
        final int nodeTtl = Math.max(15, settings.sessions().heartbeatSeconds() * 3);
        this.routing = new RoutingFabric(clients.get(RedisWorkload.CONTROL), this.prefix, this.metrics, this.sessions,
            this.coordination, nodeTtl);
        // Every command goes through RedisClient#pipeline, so one observer per pool is enough to make
        // /awf redis status report measured operations instead of constants.
        for (final RedisClient client : clients.values()) {
            client.observer(new RedisClient.Observer() {
                @Override
                public void call(final long nanos, final boolean ok) {
                    RedisFabric.this.metrics.operation(nanos, ok);
                }

                @Override
                public void timeout() {
                    RedisFabric.this.metrics.timeout();
                }

                @Override
                public void poolSaturation() {
                    RedisFabric.this.metrics.poolSaturation();
                }
            });
        }
    }

    /**
     * Builds the fabric for this process's {@link NodeIdentity}: its node id, and its incarnation (and
     * proven-stopped predecessor) for session claims. No connection is opened here; the first call
     * does that. An enabled fabric with every service disabled, or with unusable settings (an invalid
     * Redis URI included), is refused.
     *
     * @throws IllegalArgumentException when the settings cannot describe a usable fabric
     */
    public static RedisFabric start(final FabricSettings settings, final RedisSettings redisSettings,
                                    final NodeIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return start(settings, redisSettings, identity.nodeId(), identity.incarnation(), identity.previousIncarnation());
    }

    /**
     * {@link #start(FabricSettings, RedisSettings, NodeIdentity)} for an explicit node id, with this
     * JVM's incarnation and no predecessor; reads no file.
     */
    public static RedisFabric start(final FabricSettings settings, final RedisSettings redisSettings,
                                    final String nodeId) {
        return start(settings, redisSettings, nodeId, NodeIdentity.processIncarnation(), null);
    }

    private static RedisFabric start(final FabricSettings settings, final RedisSettings redisSettings,
                                     final String nodeId, final String incarnation, final String previousIncarnation) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(redisSettings, "redisSettings");
        Objects.requireNonNull(nodeId, "nodeId");
        if (!settings.enabled()) throw new IllegalArgumentException("the Aurora Redis Fabric is disabled");
        if (!settings.anyService()) {
            throw new IllegalArgumentException("the Aurora Redis Fabric is enabled but every service is disabled");
        }
        if (redisSettings.invalidReason().isPresent()) {
            throw new IllegalArgumentException("the Redis connection is misconfigured: "
                + redisSettings.invalidReason().get());
        }
        if (running != null) {
            throw new IllegalStateException("the Aurora Redis Fabric is already running; close it before starting another");
        }
        final RedisClient.Endpoint endpoint = RedisClient.Endpoint.parse(redisSettings.uri());
        final Map<RedisWorkload, RedisClient> clients = new EnumMap<>(RedisWorkload.class);
        clients.put(RedisWorkload.STORAGE, new RedisClient(endpoint, settings.pools().storage(), redisSettings.timeoutMillis()));
        clients.put(RedisWorkload.COORDINATION,
            new RedisClient(endpoint, settings.pools().coordination(), redisSettings.timeoutMillis()));
        clients.put(RedisWorkload.EVENTS, new RedisClient(endpoint, settings.pools().events(), redisSettings.timeoutMillis()));
        clients.put(RedisWorkload.CONTROL, new RedisClient(endpoint, settings.pools().control(), redisSettings.timeoutMillis()));
        final RedisFabric fabric = new RedisFabric(settings, redisSettings, nodeId, incarnation, previousIncarnation,
            clients);
        running = fabric;
        return fabric;
    }

    /** The running fabric, or {@code null}. Used by the AWF Redis backend to share the storage pool. */
    public static RedisFabric running() {
        return running;
    }

    /** Probes Redis and records reachability. Never throws: a probe failure is a state, not an exception. */
    public FabricHealth probe() {
        long began = System.nanoTime();
        boolean reachable = false;
        String version = "unknown";
        try {
            final Object pong = client(RedisWorkload.CONTROL).call("PING");
            reachable = "PONG".equalsIgnoreCase(RedisReplies.text(pong));
            if (reachable) {
                final Object info = client(RedisWorkload.CONTROL).call("INFO", "server");
                final String text = RedisReplies.text(info);
                if (text != null) {
                    for (final String line : text.split("\n")) {
                        if (line.startsWith("redis_version:")) {
                            version = line.substring("redis_version:".length()).trim();
                            break;
                        }
                    }
                }
            }
        } catch (final IOException unreachable) {
            this.metrics.connectionFailure();
        } finally {
            this.metrics.connected(reachable);
        }
        final long micros = (System.nanoTime() - began) / 1000L;
        return new FabricHealth(reachable, micros, version, this.nodeId, this.prefix, poolStats(), this.metrics.snapshot());
    }

    /** Per-workload pool size and current use, for diagnostics. */
    public Map<RedisWorkload, FabricHealth.PoolStat> poolStats() {
        final Map<RedisWorkload, FabricHealth.PoolStat> stats = new EnumMap<>(RedisWorkload.class);
        this.clients.forEach((kind, client) -> stats.put(kind,
            new FabricHealth.PoolStat(client.poolSize(), client.inUse())));
        return stats;
    }

    /** A workload client. Storage is shared with the AWF Redis world backend; the rest are fabric-only. */
    public RedisClient client(final RedisWorkload workload) {
        final RedisClient client = this.clients.get(workload);
        if (client == null) throw new IllegalStateException("no " + workload + " client");
        if (this.closed) throw new IllegalStateException("the Aurora Redis Fabric is closed");
        return client;
    }

    /** The bounded storage client, for {@code RedisBackend.sharing}. */
    public RedisClient storageClient() {
        return client(RedisWorkload.STORAGE);
    }

    public FabricSettings settings() {
        return this.settings;
    }

    public RedisSettings redisSettings() {
        return this.redisSettings;
    }

    public String nodeId() {
        return this.nodeId;
    }

    /** Every fabric key starts with this namespace; it never overlaps the world store's keys. */
    public String prefix() {
        return this.prefix;
    }

    public FabricMetrics metrics() {
        return this.metrics;
    }

    public SessionFabric sessions() {
        require("sessions", this.settings.services().sessions());
        return this.sessions;
    }

    public CoordinationFabric coordination() {
        require("coordination", this.settings.services().coordination());
        return this.coordination;
    }

    public EventFabric events() {
        require("events", this.settings.services().events());
        return this.events;
    }

    public CacheFabric cache() {
        require("cache", this.settings.services().cache());
        return this.cache;
    }

    public RoutingFabric routing() {
        require("routing", this.settings.services().routing());
        return this.routing;
    }

    private void require(final String service, final boolean enabled) {
        if (!enabled) throw new IllegalStateException("the Aurora Redis Fabric service '" + service + "' is disabled");
        if (this.closed) throw new IllegalStateException("the Aurora Redis Fabric is closed");
    }

    /**
     * Closes every pool within one budget measured from now. In-flight calls are aborted, which is the
     * documented behaviour of {@link RedisClient#deadline(long)} and {@link RedisClient#close()}.
     */
    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        if (running == this) running = null;
        final long deadline = System.nanoTime() + this.settings.sessions().heartbeatSeconds() * 1_000_000_000L;
        for (final RedisClient client : this.clients.values()) {
            try {
                client.deadline(deadline);
                client.close();
            } catch (final RuntimeException failed) {
                SourbyLogger.warn("Aurora Redis Fabric: closing a pool failed: " + failed.getMessage());
            }
        }
    }

    /** Closes every pool within {@code budgetMillis}; see {@link #close()}. */
    public void close(final long budgetMillis) {
        if (this.closed) return;
        this.closed = true;
        if (running == this) running = null;
        final long deadline = System.nanoTime() + Math.max(1L, budgetMillis) * 1_000_000L;
        for (final RedisClient client : this.clients.values()) {
            try {
                client.deadline(deadline);
                client.close();
            } catch (final RuntimeException failed) {
                SourbyLogger.warn("Aurora Redis Fabric: closing a pool failed: " + failed.getMessage());
            }
        }
    }

    /** A one-line description for logs; never contains credentials. */
    public String describe() {
        return "Aurora Redis Fabric node=" + this.nodeId + " " + RedisClient.Endpoint.redact(this.redisSettings.uri())
            + " keys=" + this.prefix + "* pools=" + this.settings.pools().storage() + "/"
            + this.settings.pools().coordination() + "/" + this.settings.pools().events() + "/"
            + this.settings.pools().control();
    }
}
