package dev.iyanz.sourbycraft.awf.redis;

import dev.iyanz.sourbycraft.awf.AwfBackend;
import dev.iyanz.sourbycraft.awf.AwfStore;
import dev.iyanz.sourbycraft.awf.WorldRole;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The {@code redis} AWF backend: every store in Redis, so several servers can share one world
 * storage and a world opens by reading its chunk coordinates only.
 *
 * <p><b>One writer per world.</b> Opening a store for writing takes a lease
 * ({@code SET lock <token> NX PX}) that this server renews every third of the lease time; a second
 * server opening the same world is refused until the lease is released (store closed, world
 * unloaded, clean stop) or has expired (crash). Every commit checks the lease inside its script,
 * so a server that lost its lease cannot overwrite the new owner's chunks.</p>
 *
 * <p>A lease names its server: a hash of the host name and the server directory. The same server
 * restarting after a crash takes its own lease over at once instead of waiting for it to expire;
 * any other server still has to wait. Two processes cannot share a server directory (the world's
 * {@code session.lock} prevents it), so the name only repeats for a restart. A copied server
 * directory on the same host is a different directory; a container recreated with a new host name
 * waits for the lease like any other server.</p>
 *
 * <p>Requirements: Redis 7.0 or newer ({@code #!lua} scripts), an eviction policy that never
 * evicts these keys ({@code noeviction} or a {@code volatile-*} policy — the keys have no expiry),
 * and for durable commits {@code appendonly yes} (Redis 7.2+ for {@code WAITAOF}).</p>
 */
public final class RedisBackend implements AwfBackend {

    static final String RELEASE_SCRIPT = """
        #!lua
        if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
        return 0
        """;
    /** KEYS: lock. ARGV: new token, this server's id, lease ms. OK, or the current holder. */
    static final String ACQUIRE_SCRIPT = """
        #!lua
        local holder = redis.call('GET', KEYS[1])
        if holder and string.sub(holder, 1, string.len(ARGV[2]) + 1) ~= ARGV[2] .. '/' then return holder end
        redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
        if holder then return 'TAKEN_OVER' end
        return 'OK'
        """;
    /**
     * KEYS: lock, gen. ARGV: token, lease ms, the generation the store last saw. 1 renewed, 2 taken
     * back after it expired unclaimed with no commit since, 0 lost.
     */
    static final String RENEW_SCRIPT = """
        #!lua
        local holder = redis.call('GET', KEYS[1])
        if holder == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
        if not holder and (redis.call('GET', KEYS[2]) or '0') == ARGV[3] then
          redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
          return 2
        end
        return 0
        """;

    private final RedisSettings settings;
    private final RedisClient client;
    /** This server's identity in leases; see the class comment. */
    private final String owner;
    private final Map<String, RedisStore> leased = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renewals;
    private volatile boolean waitForAof;
    private volatile boolean novaluesUnsupported;

    public RedisBackend(final RedisSettings settings) {
        this(settings, new RedisClient(RedisClient.Endpoint.parse(settings.uri()), settings.poolSize(),
            settings.timeoutMillis()));
    }

    RedisBackend(final RedisSettings settings, final RedisClient client) {
        this(settings, client, serverId());
    }

    RedisBackend(final RedisSettings settings, final RedisClient client, final String owner) {
        this.owner = owner;
        this.settings = settings;
        this.client = client;
        this.waitForAof = settings.waitForAof();
        this.renewals = Executors.newSingleThreadScheduledExecutor(task -> {
            // Named into the world I/O lane, where ExecutionLane attributes it.
            final Thread thread = new Thread(task, "SourbyCraft-Storage-redis-lease");
            thread.setDaemon(true);
            return thread;
        });
        final long period = Math.max(1000L, settings.leaseSeconds() * 1000L / 3);
        this.renewals.scheduleAtFixedRate(this::renewAll, period, period, TimeUnit.MILLISECONDS);
    }

    /** A hash of this host's name ({@code HOSTNAME} or {@code /etc/hostname}) and the server directory. */
    static String serverId() {
        // No DNS lookup here: resolving the local host name can block for seconds.
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = java.nio.file.Files.readString(java.nio.file.Path.of("/etc/hostname")).trim();
            } catch (final IOException | RuntimeException none) {
                host = "";
            }
        }
        final String identity = host + "|" + java.nio.file.Path.of("").toAbsolutePath().normalize();
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    RedisSettings settings() {
        return this.settings;
    }

    RedisClient client() {
        return this.client;
    }

    /**
     * Checks the connection and the server's settings, logging what would make stored worlds
     * unsafe. Called once when the backend is registered.
     */
    public void checkServer() throws IOException {
        if (!"PONG".equals(RedisClient.text(this.client.call("PING")))) {
            throw new IOException("Redis at " + this.client.endpoint() + " did not answer PING");
        }
        try {
            final Object policy = this.client.call("CONFIG", "GET", "maxmemory-policy");
            if (policy instanceof List<?> pair && pair.size() == 2) {
                final String value = RedisClient.text(pair.get(1));
                if (value != null && value.startsWith("allkeys-")) {
                    SourbyLogger.warn("Redis maxmemory-policy is " + value + ": Redis may evict AWF world data under"
                        + " memory pressure. Use noeviction for a server that stores worlds.");
                }
            }
        } catch (final RedisClient.RedisError restricted) {
            // Managed Redis often disables CONFIG; nothing to check then.
        }
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public boolean exists(final String storageId) throws IOException {
        final RedisStore probe = new RedisStore(this, storageId, WorldRole.READ_ONLY, null);
        final Object count = this.client.call("EXISTS", probe.genKey, probe.chunksKey, probe.deletedKey);
        return count instanceof Long n && n > 0;
    }

    @Override
    public AwfStore open(final String storageId, final WorldRole role, final int retainedGenerations) throws IOException {
        final String token = role.acceptsCommits() ? this.owner + "/" + UUID.randomUUID() : null;
        final RedisStore store = new RedisStore(this, storageId, role, token);
        if (token != null) {
            final String claimed = RedisClient.text(this.client.call("EVAL", ACQUIRE_SCRIPT, "1", store.lockKey, token,
                this.owner, Long.toString(this.settings.leaseSeconds() * 1000L)));
            if ("TAKEN_OVER".equals(claimed)) {
                SourbyLogger.info("Aurora World Fabric: took over this server's Redis lease on " + storageId
                    + " from a previous run that did not release it");
            } else if (!"OK".equals(claimed)) {
                final Object ttl = this.client.call("PTTL", store.lockKey);
                throw new IOException("AWF store " + storageId + " is held by another server (lease "
                    + (ttl instanceof Long ms && ms > 0 ? "expires in " + (ms / 1000 + 1) + " s" : "held")
                    + "); it is open there, or that server stopped without releasing it");
            }
            this.leased.put(token, store);
        }
        try {
            store.loadIndex();
        } catch (final IOException | RuntimeException failed) {
            release(store);
            throw failed;
        }
        return store;
    }

    /** Field names of a hash, without values, in batches. */
    List<byte[]> hashFields(final String key) throws IOException {
        final List<byte[]> fields = new ArrayList<>();
        if (!this.novaluesUnsupported) {
            try {
                String cursor = "0";
                do {
                    final List<?> reply = (List<?>) this.client.call("HSCAN", key, cursor, "COUNT", "1000", "NOVALUES");
                    cursor = RedisClient.text(reply.get(0));
                    for (final Object field : (List<?>) reply.get(1)) fields.add((byte[]) field);
                } while (!"0".equals(cursor));
                return fields;
            } catch (final RedisClient.RedisError older) {
                // NOVALUES arrived in Redis 7.4.
                this.novaluesUnsupported = true;
                fields.clear();
            }
        }
        final Object reply = this.client.call("HKEYS", key);
        for (final Object field : (List<?>) reply) fields.add((byte[]) field);
        return fields;
    }

    /** Waits for the last write to reach Redis's append-only file, if configured and possible. */
    void awaitDurable() throws IOException {
        if (!this.waitForAof) return;
        try {
            final Object reply = this.client.call("WAITAOF", "1", "0", Integer.toString(this.settings.timeoutMillis()));
            if (reply instanceof List<?> counts && !counts.isEmpty() && counts.getFirst() instanceof Long local && local < 1) {
                throw new IOException("Redis did not write the commit to its append-only file within "
                    + this.settings.timeoutMillis() + " ms");
            }
        } catch (final RedisClient.RedisError unsupported) {
            this.waitForAof = false;
            SourbyLogger.warn("Redis cannot confirm writes to its append-only file (" + unsupported.getMessage()
                + "); AWF commits are acknowledged from Redis's memory and are as durable as its persistence"
                + " settings. Enable appendonly on Redis 7.2+, or set aurora.awf.redis.wait-for-aof = false.");
        }
    }

    void release(final RedisStore store) throws IOException {
        if (store.token() == null) return;
        this.leased.remove(store.token());
        this.client.call("EVAL", RELEASE_SCRIPT, "1", store.lockKey, store.token());
    }

    private void renewAll() {
        for (final RedisStore store : this.leased.values()) {
            try {
                final Object renewed = this.client.call("EVAL", RENEW_SCRIPT, "2", store.lockKey, store.genKey,
                    store.token(), Long.toString(this.settings.leaseSeconds() * 1000L),
                    Long.toString(store.knownGeneration()));
                if (renewed instanceof Long n && n == 2) {
                    SourbyLogger.warn("Aurora World Fabric: the Redis lease on " + store.id() + " had expired (this"
                        + " server stalled for longer than lease-seconds?); nobody else took it or committed, so it"
                        + " was taken back");
                } else if (!(renewed instanceof Long n && n == 1)) {
                    this.leased.remove(store.token());
                    SourbyLogger.error("Aurora World Fabric lost its Redis lease on " + store.id()
                        + "; commits to it are refused until the world is opened again");
                }
            } catch (final IOException | RuntimeException failed) {
                SourbyLogger.warn("Aurora World Fabric could not renew its Redis lease on " + store.id() + ": "
                    + failed.getMessage());
            }
        }
    }

    @Override
    public String retire(final String storageId) throws IOException {
        final RedisStore store = new RedisStore(this, storageId, WorldRole.READ_ONLY, null);
        final String suffix = ":exported-" + System.currentTimeMillis();
        for (final String key : List.of(store.chunksKey, store.deletedKey, store.genKey)) {
            if (this.client.call("EXISTS", key) instanceof Long n && n > 0) {
                this.client.call("RENAME", key, key + suffix);
            }
        }
        return "Redis keys " + store.chunksKey + suffix + " (and :deleted, :gen)";
    }

    @Override
    public void delete(final String storageId) throws IOException {
        final RedisStore store = new RedisStore(this, storageId, WorldRole.READ_ONLY, null);
        if (this.client.call("EXISTS", store.lockKey) instanceof Long n && n > 0) {
            throw new IOException("AWF store " + storageId + " is open on a server; unload it there first");
        }
        this.client.call("DEL", store.chunksKey, store.deletedKey, store.genKey);
    }

    /** Releases every lease and closes the connections. */
    public void shutdown() {
        this.renewals.shutdownNow();
        for (final RedisStore store : List.copyOf(this.leased.values())) {
            try {
                release(store);
            } catch (final IOException failed) {
                SourbyLogger.warn("Aurora World Fabric could not release its Redis lease on " + store.id()
                    + "; it expires in " + this.settings.leaseSeconds() + " s");
            }
        }
        this.client.close();
    }

    @Override
    public String describe() {
        return "redis " + this.client.endpoint() + " prefix " + this.settings.keyPrefix()
            + (this.waitForAof ? ", commits wait for AOF" : ", commits acknowledged from memory");
    }
}
