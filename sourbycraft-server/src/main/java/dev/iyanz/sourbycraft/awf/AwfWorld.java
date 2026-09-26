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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One Aurora World Fabric world at runtime: chunks it owns, layered copy-on-write over a base
 * source, persisted through an {@link AwfWorldStore} on a storage executor.
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
 * <p><b>Scope.</b> This is the AWF runtime model with tests. The server's chunk system does not read
 * from or save to it yet; wiring it in means patching chunk load/save in the engine, which has not
 * been done.</p>
 */
public final class AwfWorld {

    private final String name;
    private final WorldRole role;
    private final ChunkSource base;
    private final AwfWorldStore store;
    private final AwfMetrics metrics = new AwfMetrics();
    private final Map<ChunkKey, byte[]> owned = new ConcurrentHashMap<>();
    /** Dirty chunks and the write version that dirtied them. */
    private final Map<ChunkKey, Long> dirty = new ConcurrentHashMap<>();
    private final AtomicLong versions = new AtomicLong();
    private final ConcurrentLinkedDeque<Long> pendingSaves = new ConcurrentLinkedDeque<>();

    /**
     * @param base where unowned chunks are read from, or {@code null} for an empty world
     * @param store where commits go, or {@code null} for a world that is never persisted
     */
    public AwfWorld(final String name, final WorldRole role, final ChunkSource base, final AwfWorldStore store) {
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

    public WorldRole role() {
        return this.role;
    }

    /** The chunk's bytes: world-owned if written, else from the base; a fresh copy either way. */
    public Optional<byte[]> read(final ChunkKey key) throws IOException {
        final byte[] mine = this.owned.get(key);
        if (mine != null) {
            return Optional.of(mine.clone());
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
        final Set<ChunkKey> keys = new TreeSet<>(this.owned.keySet());
        if (this.base != null) keys.addAll(this.base.keys());
        return keys;
    }

    /** Takes ownership of a chunk's new bytes. Called by the chunk's owning region. */
    public void write(final ChunkKey key, final byte[] serialized) {
        if (!this.role.mutable()) {
            throw new IllegalStateException("a " + this.role + " world is not mutable");
        }
        this.owned.put(key, serialized.clone());
        this.dirty.put(key, this.versions.incrementAndGet());
    }

    /** Whether a chunk has been written in this world rather than read through from the base. */
    public boolean owns(final ChunkKey key) {
        return this.owned.containsKey(key);
    }

    /**
     * Commits the dirty chunks on {@code storageLane}. Returns at once.
     *
     * <p>A chunk rewritten while the save is in flight stays dirty for the next save: only entries
     * whose version is unchanged are cleared. An {@link IOException} is retried up to
     * {@code maxAttempts} times in total; after that the stage fails and the chunks stay dirty.</p>
     */
    public CompletionStage<AwfWorldStore.CommitResult> save(final PersistenceMode mode, final Executor storageLane,
                                                            final int maxAttempts) {
        if (this.store == null || !this.role.acceptsCommits() || mode == PersistenceMode.READ_ONLY) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "a " + this.role + " world in " + mode + " mode does not save"));
        }
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        final Long enqueued = System.nanoTime();
        this.pendingSaves.addLast(enqueued);
        final CompletableFuture<AwfWorldStore.CommitResult> result;
        try {
            result = CompletableFuture.supplyAsync(() -> commit(mode, maxAttempts), storageLane);
        } catch (final RuntimeException rejected) {
            this.pendingSaves.remove(enqueued);
            this.metrics.failures.incrementAndGet();
            return CompletableFuture.failedFuture(rejected);
        }
        return result.whenComplete((ok, failed) -> this.pendingSaves.remove(enqueued));
    }

    private AwfWorldStore.CommitResult commit(final PersistenceMode mode, final int maxAttempts) {
        final long prepared = System.nanoTime();
        final Map<ChunkKey, Long> taken = new HashMap<>(this.dirty);
        final Map<ChunkKey, byte[]> changed = new HashMap<>();
        for (final ChunkKey key : taken.keySet()) {
            changed.put(key, this.owned.get(key));
        }
        this.metrics.serialization.record(System.nanoTime() - prepared);
        for (int attempt = 1; ; attempt++) {
            final long begun = System.nanoTime();
            try {
                final AwfWorldStore.CommitResult r = this.store.commit(changed, Set.of(), mode);
                this.metrics.backend.record(System.nanoTime() - begun);
                this.metrics.bytesWritten.addAndGet(r.bytesWritten());
                taken.forEach(this.dirty::remove);
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

    public AwfMetrics.Snapshot metrics() {
        final Long oldest = this.pendingSaves.peekFirst();
        return new AwfMetrics.Snapshot(this.owned.size(), this.dirty.size(), this.pendingSaves.size(),
            oldest == null ? 0L : (System.nanoTime() - oldest) / 1_000_000L,
            this.metrics.serialization.percentiles(), this.metrics.backend.percentiles(),
            this.metrics.retries.get(), this.metrics.failures.get(), this.metrics.bytesRead.get(),
            this.metrics.bytesWritten.get(), this.metrics.materialized.get());
    }
}
