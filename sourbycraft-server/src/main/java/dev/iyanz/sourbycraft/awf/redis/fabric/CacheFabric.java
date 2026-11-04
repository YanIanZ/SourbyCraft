package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A bounded, TTL-limited cache for routing and metadata, with stampede protection and version-aware
 * invalidation.
 *
 * <p>It is never the source of truth: every value can be reloaded, a missed invalidation is bounded by
 * the TTL, and a Redis failure simply means a miss. Invalidation increments a per-namespace version
 * key in Redis; each cache instance re-reads that version at most once a second, so a node that
 * misses a change serves stale data for at most that second plus the TTL. Version polling is used
 * instead of Pub/Sub on purpose: a lost notification cannot make the cache permanently stale, and
 * no extra blocking subscriber connection is needed.</p>
 *
 * <p>Concurrent misses for one key share a single load ({@code computeIfAbsent}-style single flight).
 * A waiting caller blocks its own I/O worker, never a tick thread; callers are required to be on the
 * storage or control workload.</p>
 */
public final class CacheFabric {

    private static final long VERSION_CHECK_INTERVAL_NANOS = 1_000_000_000L;
    private static final Object NULL = new Object();

    private record Entry(Object value, long expiresAtNanos) {}

    private final RedisClient client;
    private final String prefix;
    private final FabricMetrics metrics;
    private final int maxEntries;
    private final int ttlSeconds;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    /** Guarded by {@link #insertionOrder}; bounded by {@link #maxEntries} because put evicts. */
    private final java.util.ArrayDeque<String> insertionOrder = new java.util.ArrayDeque<>();
    private final ConcurrentHashMap<String, AtomicLong> localVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastVersionCheckNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong evictions = new AtomicLong();

    CacheFabric(final RedisClient client, final String prefix, final FabricMetrics metrics,
                final FabricSettings.CacheSettings settings) {
        this.client = client;
        this.prefix = prefix;
        this.metrics = metrics;
        this.maxEntries = settings.maxEntries();
        this.ttlSeconds = settings.ttlSeconds();
    }

    public int size() {
        return this.entries.size();
    }

    public int maxEntries() {
        return this.maxEntries;
    }

    public int ttlSeconds() {
        return this.ttlSeconds;
    }

    /** Returns a cached value or loads it once for all concurrent callers. {@code null} is cached. */
    @SuppressWarnings("unchecked")
    public <T> T get(final String namespace, final String key, final Supplier<T> loader) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        refreshVersion(namespace);
        final String mapKey = mapKey(namespace, key);
        final Entry entry = this.entries.get(mapKey);
        if (entry != null && entry.expiresAtNanos() - System.nanoTime() > 0L) {
            this.metrics.cacheHit();
            return entry.value() == NULL ? null : (T) entry.value();
        }
        if (entry != null) this.entries.remove(mapKey, entry);
        this.metrics.cacheMiss();
        final CompletableFuture<Object> mine = new CompletableFuture<>();
        final CompletableFuture<Object> running = this.inFlight.putIfAbsent(mapKey, mine);
        if (running != null) {
            try {
                final Object shared = running.join();
                return shared == NULL ? null : (T) shared;
            } catch (final CompletionException failed) {
                throw failed.getCause() instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException(failed.getCause());
            }
        }
        try {
            final T loaded = loader.get();
            put(namespace, key, loaded);
            mine.complete(loaded == null ? NULL : loaded);
            return loaded;
        } catch (final RuntimeException | Error failed) {
            mine.completeExceptionally(failed);
            throw failed;
        } finally {
            this.inFlight.remove(mapKey, mine);
        }
    }

    /** Stores a value, evicting oldest entries when the bound is exceeded. */
    public void put(final String namespace, final String key, final Object value) {
        final String mapKey = mapKey(namespace, key);
        final Entry previous = this.entries.put(mapKey, new Entry(value == null ? NULL : value,
            System.nanoTime() + this.ttlSeconds * 1_000_000_000L));
        if (previous == null) {
            synchronized (this.insertionOrder) {
                this.insertionOrder.addLast(mapKey);
            }
        }
        evictIfNeeded();
    }

    /** The value if present and unexpired, without loading; a miss is counted. */
    @SuppressWarnings("unchecked")
    public <T> T getIfPresent(final String namespace, final String key) {
        refreshVersion(namespace);
        final Entry entry = this.entries.get(mapKey(namespace, key));
        if (entry == null || entry.expiresAtNanos() - System.nanoTime() <= 0L) {
            if (entry != null) this.entries.remove(mapKey(namespace, key), entry);
            this.metrics.cacheMiss();
            return null;
        }
        this.metrics.cacheHit();
        return entry.value() == NULL ? null : (T) entry.value();
    }

    /** Drops one key here and on every other node (version bump). */
    public void invalidate(final String namespace, final String key) {
        this.entries.remove(mapKey(namespace, key));
        bumpVersion(namespace);
        this.metrics.cacheInvalidation();
    }

    /** Drops a whole namespace here and on every other node. */
    public void invalidateAll(final String namespace) {
        final String prefix = namespace + '\u0000';
        this.entries.keySet().removeIf(key -> key.startsWith(prefix));
        synchronized (this.insertionOrder) {
            this.insertionOrder.removeIf(key -> key.startsWith(prefix));
        }
        bumpVersion(namespace);
        this.metrics.cacheInvalidation();
    }

    /** Locally drops a namespace without a Redis round trip; for shutdown and tests. */
    public void clear() {
        this.entries.clear();
        synchronized (this.insertionOrder) {
            this.insertionOrder.clear();
        }
        this.inFlight.clear();
    }

    /** The namespace version this node currently trusts. */
    public long version(final String namespace) {
        return this.localVersions.computeIfAbsent(namespace, ignored -> new AtomicLong()).get();
    }

    /** Evictions performed locally (a subset of the fabric-wide counter). */
    public long evictions() {
        return this.evictions.get();
    }

    private void refreshVersion(final String namespace) {
        final long now = System.nanoTime();
        final AtomicLong last = this.lastVersionCheckNanos.computeIfAbsent(namespace, ignored -> new AtomicLong());
        final long previous = last.get();
        if (previous != 0L && now - previous < VERSION_CHECK_INTERVAL_NANOS) return;
        if (!last.compareAndSet(previous, now)) return;
        try {
            final Object reply = this.client.call("GET", versionKey(namespace));
            final long remote = RedisReplies.number(reply, 0L);
            final AtomicLong local = this.localVersions.computeIfAbsent(namespace, ignored -> new AtomicLong());
            final long current = local.get();
            if (remote > current && local.compareAndSet(current, remote)) {
                final String prefix = namespace + '\u0000';
                this.entries.keySet().removeIf(key -> key.startsWith(prefix));
                this.metrics.cacheInvalidation();
            } else if (remote > current) {
                local.accumulateAndGet(remote, Math::max);
            }
        } catch (final IOException unreachable) {
            // A miss is safe: the cache is an optimisation, not a source of truth.
            this.metrics.connectionFailure();
        }
    }

    private void bumpVersion(final String namespace) {
        try {
            final Object reply = this.client.call("INCR", versionKey(namespace));
            final long version = RedisReplies.number(reply, 0L);
            this.localVersions.computeIfAbsent(namespace, ignored -> new AtomicLong()).accumulateAndGet(version, Math::max);
        } catch (final IOException unreachable) {
            // Bump locally anyway; other nodes converge through TTL.
            this.localVersions.computeIfAbsent(namespace, ignored -> new AtomicLong()).incrementAndGet();
            this.metrics.connectionFailure();
        }
    }

    private void evictIfNeeded() {
        // Drop names of entries that expired or were invalidated; otherwise the deque outlives the map.
        synchronized (this.insertionOrder) {
            while (!this.insertionOrder.isEmpty() && !this.entries.containsKey(this.insertionOrder.peekFirst())) {
                this.insertionOrder.pollFirst();
            }
        }
        while (this.entries.size() > this.maxEntries) {
            final String oldest;
            synchronized (this.insertionOrder) {
                oldest = this.insertionOrder.pollFirst();
            }
            if (oldest == null) return;
            if (this.entries.remove(oldest) != null) {
                this.evictions.incrementAndGet();
                this.metrics.cacheEviction();
            }
        }
    }

    private String versionKey(final String namespace) {
        return this.prefix + "cache-version:" + namespace;
    }

    private static String mapKey(final String namespace, final String key) {
        return namespace + '\u0000' + key;
    }

    /** Visible for diagnostics: the namespaces this node has looked at. */
    public List<String> namespaces() {
        final List<String> names = new ArrayList<>(this.localVersions.keySet());
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    /** Visible for diagnostics. */
    public Map<String, Long> versions() {
        final Map<String, Long> versions = new java.util.LinkedHashMap<>();
        this.localVersions.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> versions.put(entry.getKey(), entry.getValue().get()));
        return versions;
    }
}
