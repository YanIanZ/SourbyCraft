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
     * KEYS: lock, gen, writer. ARGV: token, lease ms, the generation the store last saw. 1 renewed,
     * 2 taken back after it expired unclaimed with no other server's commit since (the generation is
     * unchanged, or the last commit was this store's own), 0 refused.
     */
    static final String RENEW_SCRIPT = """
        #!lua
        local holder = redis.call('GET', KEYS[1])
        if holder == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
        if not holder and ((redis.call('GET', KEYS[2]) or '0') == ARGV[3] or redis.call('GET', KEYS[3]) == ARGV[1]) then
          redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
          return 2
        end
        return 0
        """;

    private final RedisSettings settings;
    private final RedisClient client;
    /** This server's identity in leases; see the class comment. */
    private final String owner;
    /** How long an acquired or renewed lease lasts; {@code lease-seconds} in production. */
    private final long leaseMillis;
    /** The renewal cadence; a third of the lease (at least a second) in production. */
    private final long renewalMillis;
    /**
     * Writable stores, by token, from open to close. A store stays here when its lease is lost, so
     * renewal keeps checking whether it may take the lease back; see {@link #renewAll}.
     */
    private final Map<String, RedisStore> leased = new ConcurrentHashMap<>();
    /** Tokens whose lease is currently lost, so the loss is logged once rather than every renewal. */
    private final java.util.Set<String> lost = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService renewals;
    private volatile boolean waitForAof;
    private volatile boolean novaluesUnsupported;

    public RedisBackend(final RedisSettings settings) {
        this(settings, new RedisClient(RedisClient.Endpoint.parse(settings.uri()), settings.poolSize(),
            settings.timeoutMillis()));
    }

    /**
     * A backend over a caller-owned, independently bounded client, for example the Aurora Redis
     * Fabric's STORAGE workload. The fabric keeps ownership of the client's lifetime; closing this
     * backend closes it too, which is safe to repeat.
     */
    public static RedisBackend sharing(final RedisSettings settings, final RedisClient client) {
        return new RedisBackend(settings, client);
    }

    RedisBackend(final RedisSettings settings, final RedisClient client) {
        this(settings, client, serverId());
    }

    RedisBackend(final RedisSettings settings, final RedisClient client, final String owner) {
        this(settings, client, owner, settings.leaseSeconds() * 1000L, renewalPeriodMillis(settings));
    }

    /**
     * Test seam: a lease TTL and renewal cadence shorter than {@code lease-seconds} allows (its
     * minimum is 5 s), so lease expiry and renewal can be exercised in milliseconds. The
     * constructors above keep the production values.
     */
    RedisBackend(final RedisSettings settings, final RedisClient client, final String owner,
                 final long leaseMillis, final long renewalMillis) {
        this.leaseMillis = leaseMillis;
        this.renewalMillis = renewalMillis;
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
        this.renewals.scheduleAtFixedRate(this::renewAll, renewalMillis, renewalMillis, TimeUnit.MILLISECONDS);
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
            final String claimed = RedisClient.text(this.client.eval(ACQUIRE_SCRIPT, "1", store.lockKey, token,
                this.owner, Long.toString(this.leaseMillis)));
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
        // The commit already ran on Redis: a deadline here leaves its durability unknown, never "not applied".
        final int waitMillis;
        try {
            waitMillis = this.client.remainingTimeoutMillis();
        } catch (final dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded passed) {
            throw new dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded("the AWF shutdown deadline passed after Redis ran the"
                + " commit and before it confirmed the append-only file write", true, passed);
        }
        try {
            final Object reply = this.client.call("WAITAOF", "1", "0", Integer.toString(waitMillis));
            if (reply instanceof List<?> counts && !counts.isEmpty() && counts.getFirst() instanceof Long local && local < 1) {
                throw new IOException("Redis did not write the commit to its append-only file within "
                    + waitMillis + " ms");
            }
        } catch (final RedisClient.RedisError unsupported) {
            this.waitForAof = false;
            SourbyLogger.warn("Redis cannot confirm writes to its append-only file (" + unsupported.getMessage()
                + "); AWF commits are acknowledged from Redis's memory and are as durable as its persistence"
                + " settings. Enable appendonly on Redis 7.2+, or set aurora.awf.redis.wait-for-aof = false.");
        }
    }

    /** A commit took the store's lapsed lease back: renewal continues for it (it never stopped). */
    void retaken(final RedisStore store) {
        this.lost.remove(store.token());
        this.leased.putIfAbsent(store.token(), store);
        SourbyLogger.warn("Aurora World Fabric: the Redis lease on " + store.id() + " had lapsed with no other holder"
            + " and no other server's commit since; a commit took it back and renewal continues");
    }

    void release(final RedisStore store) throws IOException {
        if (store.token() == null) return;
        this.leased.remove(store.token());
        this.lost.remove(store.token());
        this.client.eval(RELEASE_SCRIPT, "1", store.lockKey, store.token());
    }

    /**
     * Renews every open store's lease. A store whose lease was lost stays registered: if the lease
     * later becomes free and no other server committed since, renewal (or the store's next commit)
     * takes it back; while another server holds it or has committed, every commit is refused.
     *
     * <p>All renewals of a pass go out as one pipeline, one round trip whatever the number of open
     * stores: renewing them one by one, a slow Redis (each call up to {@code timeout-ms}) could make
     * a pass with many stores outlast the lease itself. A pass slower than half the renewal period
     * is logged, since a few of those in a row are how a lease is lost. The renewals call the script
     * by its SHA-1 ({@code EVALSHA}); only a pass that finds Redis without it cached (after a restart
     * or {@code SCRIPT FLUSH}) takes a second round trip, sending the text once.</p>
     *
     * @return how long the pass took, in nanoseconds
     */
    long renewAll() {
        final long started = System.nanoTime();
        final List<RedisStore> stores = List.copyOf(this.leased.values());
        if (stores.isEmpty()) return 0L;
        final String leaseMillis = Long.toString(this.leaseMillis);
        final List<Object[]> commands = new ArrayList<>(stores.size());
        for (final RedisStore store : stores) {
            commands.add(new Object[] {"3", store.lockKey, store.genKey, store.writerKey,
                store.token(), leaseMillis, Long.toString(store.knownGeneration())});
        }
        List<Object> replies = null;
        try {
            replies = this.client.evalPipeline(RENEW_SCRIPT, commands);
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("Aurora World Fabric could not renew its Redis leases on " + stores.size()
                + " store(s): " + failed.getMessage());
        }
        if (replies != null) {
            for (int i = 0; i < stores.size(); i++) renewed(stores.get(i), replies.get(i));
        }
        final long elapsed = System.nanoTime() - started;
        if (slowPass(elapsed, this.renewalMillis)) {
            SourbyLogger.warn("Aurora World Fabric: renewing " + stores.size() + " Redis lease(s) took "
                + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms, over half the " + this.renewalMillis
                + " ms renewal period; the leases expire after " + this.leaseMillis / 1000L + " s");
        }
        return elapsed;
    }

    /** The lease TTL in milliseconds that acquisition, renewal and commits all use. */
    long leaseMillis() {
        return this.leaseMillis;
    }

    /** The renewal period: a third of the lease, at least a second. */
    static long renewalPeriodMillis(final RedisSettings settings) {
        return Math.max(1000L, settings.leaseSeconds() * 1000L / 3);
    }

    /** Whether a renewal pass took more than half the renewal period. */
    static boolean slowPass(final long elapsedNanos, final long periodMillis) {
        return elapsedNanos > TimeUnit.MILLISECONDS.toNanos(periodMillis) / 2;
    }

    private void renewed(final RedisStore store, final Object renewed) {
        if (renewed instanceof RedisClient.RedisError error) {
            SourbyLogger.warn("Aurora World Fabric could not renew its Redis lease on " + store.id() + ": "
                + error.getMessage());
        } else if (renewed instanceof Long n && n == 2) {
            this.lost.remove(store.token());
            SourbyLogger.warn("Aurora World Fabric: the Redis lease on " + store.id() + " had lapsed (this"
                + " server stalled or Redis was unreachable for longer than lease-seconds, or another holder"
                + " released it); nobody else held it or committed since, so it was taken back");
        } else if (renewed instanceof Long n && n == 1) {
            this.lost.remove(store.token());
        } else if (this.lost.add(store.token())) {
            SourbyLogger.error("Aurora World Fabric lost its Redis lease on " + store.id() + ": another server"
                + " holds it or has committed to the world since. Commits to it are refused and its unsaved"
                + " chunks stay in memory; this server takes the lease back only if it becomes free with no"
                + " other server's commit since");
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
        this.client.call("DEL", store.writerKey);
        return "Redis keys " + store.chunksKey + suffix + " (and :deleted, :gen)";
    }

    @Override
    public void delete(final String storageId) throws IOException {
        final RedisStore store = new RedisStore(this, storageId, WorldRole.READ_ONLY, null);
        if (this.client.call("EXISTS", store.lockKey) instanceof Long n && n > 0) {
            throw new IOException("AWF store " + storageId + " is open on a server; unload it there first");
        }
        this.client.call("DEL", store.chunksKey, store.deletedKey, store.genKey, store.writerKey);
    }

    /**
     * Shutdown began: every Redis call from now on is bounded by the budget's deadline
     * ({@link RedisClient#deadline}); when it passes lease renewal stops and every connection in use
     * is aborted, so no call (a commit, a renewal, a read) keeps the server waiting.
     */
    @Override
    public void beginShutdown(final dev.iyanz.sourbycraft.awf.AwfShutdownBudget budget) {
        this.client.deadline(budget.deadlineNanos());
        budget.onExpiry(() -> {
            this.renewals.shutdownNow();
            this.client.expire();
        });
    }

    /** Whether lease renewal has stopped (shutdown, or the shutdown deadline passed). */
    boolean renewalsStopped() {
        return this.renewals.isShutdown();
    }

    /**
     * Releases every lease still held, in one pipeline (one round trip, bounded by the shutdown
     * deadline when one is set), and closes every connection, also those in use.
     */
    @Override
    public void shutdown() {
        this.renewals.shutdownNow();
        final List<RedisStore> stores = List.copyOf(this.leased.values());
        if (!stores.isEmpty()) {
            final List<Object[]> calls = new ArrayList<>(stores.size());
            for (final RedisStore store : stores) calls.add(new Object[] {"1", store.lockKey, store.token()});
            List<Object> replies = null;
            try {
                replies = this.client.evalPipeline(RELEASE_SCRIPT, calls);
            } catch (final IOException | RuntimeException failed) {
                SourbyLogger.warn("Aurora World Fabric could not release its Redis leases on " + stores.size()
                    + " store(s) (" + failed.getMessage() + "); they expire in " + this.settings.leaseSeconds() + " s");
            }
            for (int i = 0; i < stores.size(); i++) {
                final RedisStore store = stores.get(i);
                this.leased.remove(store.token());
                this.lost.remove(store.token());
                if (replies != null && replies.get(i) instanceof RedisClient.RedisError error) {
                    SourbyLogger.warn("Aurora World Fabric could not release its Redis lease on " + store.id() + " ("
                        + error.getMessage() + "); it expires in " + this.settings.leaseSeconds() + " s");
                }
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
