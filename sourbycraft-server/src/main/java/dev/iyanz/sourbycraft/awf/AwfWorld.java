package dev.iyanz.sourbycraft.awf;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One Aurora World Fabric world at runtime: chunks it owns, layered copy-on-write over a base
 * source, persisted through an {@link AwfStore} on a storage executor.
 *
 * <p><b>Ownership.</b> {@link #write} is called by the region that owns the chunk, with bytes it has
 * already serialized; the array is copied, so the region may reuse its buffer at once. Stored arrays
 * are never mutated afterwards — a later write replaces the reference — which is what lets the
 * storage lane read them without locking the region out. {@link #save} never blocks its caller.</p>
 *
 * <p><b>Copy-on-write.</b> Reads fall through to the base (a template {@link AwfFile}, a store, or
 * both via {@link LayeredSource}) until the first write, which makes the chunk world-owned. The base
 * is never written, so any number of instances can share one template and stay isolated.</p>
 *
 * <p><b>Memory bound.</b> With a resident limit, chunks beyond it that are clean — committed to the
 * store and not rewritten since — are dropped after a save, least recently used first. A dropped
 * chunk is read back from the store. Dirty chunks are never dropped, so the limit can be exceeded
 * by unsaved work; that is the price of never losing a write.</p>
 *
 * <p><b>Deletion.</b> {@link #delete} records that the world no longer has a chunk. The deletion
 * shadows the base exactly as a write does, and is committed as a tombstone, so the chunk does not
 * reappear from the base after a save or a restart.</p>
 *
 * <p><b>Scope.</b> The engine reaches this through {@link AwfRegionStorage}, for the worlds an
 * operator lists in {@code aurora.awf.worlds}. Nothing else creates one.</p>
 */
public final class AwfWorld {

    private final String name;
    private final WorldRole role;
    private final ChunkSource base;
    private final AwfStore store;
    private final AwfMetrics metrics = new AwfMetrics();
    private final Map<ChunkKey, byte[]> owned = new ConcurrentHashMap<>();
    /** Dirty chunks and the write version that dirtied them. */
    private final Map<ChunkKey, Long> dirty = new ConcurrentHashMap<>();
    private final AtomicLong versions = new AtomicLong();
    /** Saves queued or running, by enqueue time; bounded so a stalled backend cannot pile them up. */
    static final int MAX_PENDING_SAVES = 16;
    private final java.util.concurrent.ArrayBlockingQueue<Long> pendingSaves =
        new java.util.concurrent.ArrayBlockingQueue<>(MAX_PENDING_SAVES);
    /** Last access, by a monotonic counter, for least-recently-used eviction. */
    private final Map<ChunkKey, Long> lastAccess = new ConcurrentHashMap<>();
    private final AtomicLong accessClock = new AtomicLong();
    private final int residentLimit;
    private final AtomicLong evicted = new AtomicLong();
    /** Stands in for a deleted chunk in {@link #owned}; compared by identity, never exposed. */
    private static final byte[] DELETED = new byte[0];

    /**
     * @param base where unowned chunks are read from, or {@code null} for an empty world
     * @param store where commits go, or {@code null} for a world that is never persisted
     */
    public AwfWorld(final String name, final WorldRole role, final ChunkSource base, final AwfStore store) {
        this(name, role, base, store, 0);
    }

    /**
     * @param residentLimit world-owned chunks kept in memory after a save; 0 means unlimited
     */
    public AwfWorld(final String name, final WorldRole role, final ChunkSource base, final AwfStore store,
                    final int residentLimit) {
        if (residentLimit < 0) throw new IllegalArgumentException("residentLimit must be >= 0");
        this.residentLimit = residentLimit;
        this.name = Objects.requireNonNull(name, "name");
        this.role = Objects.requireNonNull(role, "role");
        this.base = base;
        this.store = store;
        if (store == null && role.acceptsCommits()) {
            throw new IllegalArgumentException("a " + role + " world needs a store");
        }
    }

    public String name() {
        return this.name;
    }

    /** Closes the store after the last commit; see {@link AwfStore#close()}. */
    void closeStore() throws IOException {
        if (this.store != null) this.store.close();
    }

    public WorldRole role() {
        return this.role;
    }

    /** The chunk's bytes: world-owned if written, else from the base; a fresh copy either way. */
    public Optional<byte[]> read(final ChunkKey key) throws IOException {
        final byte[] mine = this.owned.get(key);
        if (mine != null) {
            this.lastAccess.put(key, this.accessClock.incrementAndGet());
            return mine == DELETED ? Optional.empty() : Optional.of(mine.clone());
        }
        // A chunk this world wrote (or deleted), committed and then dropped from memory lives in
        // the store; a committed deletion reads as absent rather than falling through to the base.
        if (this.store != null && this.store.has(key)) {
            final Optional<byte[]> committed = this.store.read(key);
            committed.ifPresent(bytes -> this.metrics.bytesRead.addAndGet(bytes.length));
            return committed;
        }
        if (this.base == null) return Optional.empty();
        final Optional<byte[]> fromBase = this.base.read(key);
        fromBase.ifPresent(bytes -> {
            this.metrics.materialized.incrementAndGet();
            this.metrics.bytesRead.addAndGet(bytes.length);
        });
        return fromBase;
    }

    /** Every chunk this world can return. */
    public Set<ChunkKey> keys() {
        final Set<ChunkKey> keys = new TreeSet<>();
        if (this.base != null) keys.addAll(this.base.keys());
        if (this.store != null) {
            keys.addAll(this.store.keys());
            keys.removeAll(this.store.deleted());
        }
        this.owned.forEach((key, bytes) -> {
            if (bytes == DELETED) keys.remove(key);
            else keys.add(key);
        });
        return keys;
    }

    /** Takes ownership of a chunk's new bytes. Called by the chunk's owning region. */
    public void write(final ChunkKey key, final byte[] serialized) {
        if (!this.role.mutable()) {
            throw new IllegalStateException("a " + this.role + " world is not mutable");
        }
        this.owned.put(key, serialized.clone());
        this.dirty.put(key, this.versions.incrementAndGet());
        this.lastAccess.put(key, this.accessClock.incrementAndGet());
    }

    /**
     * Records that the world no longer has a chunk. Called by the chunk's owning region. The
     * deletion shadows the base until the chunk is written again.
     */
    public void delete(final ChunkKey key) {
        if (!this.role.mutable()) {
            throw new IllegalStateException("a " + this.role + " world is not mutable");
        }
        this.owned.put(key, DELETED);
        this.dirty.put(key, this.versions.incrementAndGet());
        this.lastAccess.put(key, this.accessClock.incrementAndGet());
    }

    /**
     * Whether this world has written or deleted a chunk rather than reading it through from the
     * base. A chunk it does not own is the base's business.
     */
    public boolean owns(final ChunkKey key) {
        return this.owned.containsKey(key) || (this.store != null && this.store.has(key));
    }

    /** Chunks written or deleted since the last successful save. */
    public int dirtyCount() {
        return this.dirty.size();
    }

    /** Saves queued or running. */
    public int pendingSaveCount() {
        return this.pendingSaves.size();
    }

    /** Whether a chunk's bytes are currently held in memory. */
    public boolean resident(final ChunkKey key) {
        return this.owned.containsKey(key);
    }

    /**
     * Commits the dirty chunks on {@code storageLane}. Returns at once.
     *
     * <p>A chunk rewritten while the save is in flight stays dirty for the next save: only entries
     * whose version is unchanged are cleared. An {@link IOException} is retried up to
     * {@code maxAttempts} times in total; after that the stage fails and the chunks stay dirty.</p>
     */
    public CompletionStage<AwfStore.CommitResult> save(final PersistenceMode mode, final Executor storageLane,
                                                            final int maxAttempts) {
        if (this.store == null || !this.role.acceptsCommits() || mode == PersistenceMode.READ_ONLY) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "a " + this.role + " world in " + mode + " mode does not save"));
        }
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        // Removal is by equals: two saves enqueued in the same nanosecond share a value, and each
        // completion removes one of them, which keeps the count right.
        final Long enqueued = System.nanoTime();
        if (!this.pendingSaves.offer(enqueued)) {
            this.metrics.failures.incrementAndGet();
            return CompletableFuture.failedFuture(new java.util.concurrent.RejectedExecutionException(
                MAX_PENDING_SAVES + " saves already pending for " + this.name
                    + "; the backend is not keeping up, refusing rather than queueing more"));
        }
        final CompletableFuture<AwfStore.CommitResult> result;
        try {
            result = CompletableFuture.supplyAsync(() -> commit(mode, maxAttempts), storageLane);
        } catch (final RuntimeException rejected) {
            this.pendingSaves.remove(enqueued);
            this.metrics.failures.incrementAndGet();
            return CompletableFuture.failedFuture(rejected);
        }
        return result.whenComplete((ok, failed) -> this.pendingSaves.remove(enqueued));
    }

    /**
     * Commits the dirty chunks on the calling thread and waits for it. For flushes and shutdown,
     * where the caller is already off the region threads and must know the data is durable before
     * it continues; the store refuses to run on a region thread.
     *
     * @throws IOException when every attempt failed; the chunks stay dirty
     */
    public AwfStore.CommitResult saveNow(final PersistenceMode mode, final int maxAttempts) throws IOException {
        if (this.store == null || !this.role.acceptsCommits() || mode == PersistenceMode.READ_ONLY) {
            throw new IllegalStateException("a " + this.role + " world in " + mode + " mode does not save");
        }
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        try {
            return commit(mode, maxAttempts);
        } catch (final CompletionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            throw failed;
        }
    }

    /**
     * Held from the snapshot of dirty chunks until the store has the commit, so commits reach the
     * store in snapshot order. Without it a periodic commit that snapshotted a chunk could lose
     * the race for the store to a flush that snapshotted the chunk's newer bytes, land last, and
     * leave the store with the older bytes while the chunk was no longer dirty.
     */
    private final Object commitLock = new Object();

    private AwfStore.CommitResult commit(final PersistenceMode mode, final int maxAttempts) {
        synchronized (this.commitLock) {
            return commitInOrder(mode, maxAttempts);
        }
    }

    private AwfStore.CommitResult commitInOrder(final PersistenceMode mode, final int maxAttempts) {
        final long prepared = System.nanoTime();
        final Map<ChunkKey, Long> taken = new HashMap<>(this.dirty);
        final Map<ChunkKey, byte[]> changed = new HashMap<>();
        final Set<ChunkKey> deleted = new java.util.HashSet<>();
        for (final Map.Entry<ChunkKey, Long> entry : taken.entrySet()) {
            final byte[] bytes = this.owned.get(entry.getKey());
            // A chunk dirtied after the snapshot of versions may carry newer bytes than its
            // version says; that is harmless: its newer version keeps it dirty for the next save.
            if (bytes == DELETED) deleted.add(entry.getKey());
            else if (bytes != null) changed.put(entry.getKey(), bytes);
        }
        this.metrics.serialization.record(System.nanoTime() - prepared);
        for (int attempt = 1; ; attempt++) {
            final long begun = System.nanoTime();
            try {
                final AwfStore.CommitResult r = this.store.commit(changed, Set.of(), deleted, mode);
                this.metrics.backend.record(System.nanoTime() - begun);
                this.metrics.bytesWritten.addAndGet(r.bytesWritten());
                // Only entries whose version is unchanged: a chunk rewritten meanwhile stays dirty.
                taken.forEach(this.dirty::remove);
                evictCleanBeyondLimit();
                return r;
            } catch (final IOException failure) {
                if (attempt < maxAttempts) {
                    this.metrics.retries.incrementAndGet();
                    continue;
                }
                this.metrics.failures.incrementAndGet();
                throw new CompletionException(failure);
            } catch (final RuntimeException failure) {
                this.metrics.failures.incrementAndGet();
                throw failure;
            }
        }
    }

    /**
     * Drops clean world-owned chunks, least recently used first, until at most
     * {@code residentLimit} remain. Runs on the storage lane after a successful commit.
     */
    private void evictCleanBeyondLimit() {
        if (this.residentLimit == 0 || this.owned.size() <= this.residentLimit) return;
        final java.util.List<Map.Entry<ChunkKey, byte[]>> clean = new java.util.ArrayList<>();
        for (final Map.Entry<ChunkKey, byte[]> e : this.owned.entrySet()) {
            if (!this.dirty.containsKey(e.getKey())) clean.add(Map.entry(e.getKey(), e.getValue()));
        }
        clean.sort(java.util.Comparator.comparingLong(e -> this.lastAccess.getOrDefault(e.getKey(), 0L)));
        for (final Map.Entry<ChunkKey, byte[]> e : clean) {
            if (this.owned.size() <= this.residentLimit) break;
            // Only the exact bytes observed as clean: a write since then replaced the array and
            // re-dirtied the chunk, and must stay.
            if (!this.dirty.containsKey(e.getKey()) && this.owned.remove(e.getKey(), e.getValue())) {
                this.lastAccess.remove(e.getKey());
                this.evicted.incrementAndGet();
            }
        }
    }

    /** Clean chunks dropped from memory by the resident limit. */
    public long evicted() {
        return this.evicted.get();
    }

    public AwfMetrics.Snapshot metrics() {
        final Long oldest = this.pendingSaves.peek();
        return new AwfMetrics.Snapshot(this.owned.size(), this.dirty.size(), this.pendingSaves.size(),
            oldest == null ? 0L : (System.nanoTime() - oldest) / 1_000_000L,
            this.metrics.serialization.percentiles(), this.metrics.backend.percentiles(),
            this.metrics.retries.get(), this.metrics.failures.get(), this.metrics.bytesRead.get(),
            this.metrics.bytesWritten.get(), this.metrics.materialized.get());
    }
}
