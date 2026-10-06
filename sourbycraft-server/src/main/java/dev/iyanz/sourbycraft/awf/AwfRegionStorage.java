package dev.iyanz.sourbycraft.awf;

import dev.iyanz.sourbycraft.execution.ExecutionLane;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aurora World Fabric under one of the engine's region storages ({@code RegionFileStorage}): the
 * chunk, entity or POI data of one dimension of a world an operator listed in
 * {@code aurora.awf.worlds}.
 *
 * <p><b>Layering.</b> The world's existing region files are the base. A chunk the engine writes
 * or deletes after AWF is attached is owned by AWF and never goes to a region file; a chunk it has
 * not touched is still read from the region file. {@link #read} says which: {@code null} means
 * "not AWF's, read the region file", {@link #DELETED} means "AWF deleted it, there is no data".
 * Region files are never written while AWF is attached, so they stay exactly as they were on the
 * day it was enabled.</p>
 *
 * <p><b>Durability.</b> A write is in memory until a commit. Commits start every
 * {@code commit-interval-seconds} on the governed storage lane ({@link #maintain}), and run
 * synchronously on {@link #flush} and {@link #close} (save-all flush and shutdown). A crash can
 * lose the writes of the last interval — region files lose less, since each write goes to the
 * OS at once. That is the trade this storage makes, and why no world uses it unless listed.</p>
 *
 * <p><b>Once attached, attached.</b> The store under a region folder shadows the region files
 * from then on. If the world is later removed from the list, the store is still opened: writing to
 * the region files underneath it would be silently overridden by older AWF data on a later run.
 * Leaving AWF needs an export, which does not exist yet.</p>
 */
public final class AwfRegionStorage {

    /** {@link #read}'s answer for a chunk AWF deleted. Never modified; compare by identity. */
    public static final byte[] DELETED = new byte[0];
    /** Suffix of the store directory beside a region folder: {@code region} → {@code region.awf}. */
    public static final String STORE_SUFFIX = ".awf";

    private final String name;
    private final AwfWorld world;
    private final AwfSettings settings;
    private final Executor storageLane;
    private final Runnable onClose;
    private final AtomicLong lastCommitNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    /**
     * Makes {@link #write} and the close/discard transitions mutually exclusive. A write holds the
     * read lock from its {@code closed}/{@code discarding} check until its bytes are in the world,
     * so writes on many region threads still run side by side. {@link #close} and {@link #discard}
     * take the write lock to flip their state, so a write admitted before close is in the world
     * before close takes its final snapshot, and a write that comes after sees the new state.
     */
    private final java.util.concurrent.locks.ReentrantReadWriteLock transition =
        new java.util.concurrent.locks.ReentrantReadWriteLock();
    /** Runs inside {@link #write} once it is admitted; for tests that interleave close with it. */
    private volatile Runnable admittedHook = () -> {};
    /**
     * Set when the world is being unloaded without saving: from then on writes are dropped and
     * nothing more is committed, so the store keeps the state of its last commit.
     */
    private volatile boolean discarding;
    /** The storage folder kind ({@code region}, ...) when empty new chunks are pruned, else null. */
    private volatile String pruneKind;
    private final AtomicLong pruned = new AtomicLong();
    /** Chunks outside these are not stored; null for no bounds. */
    private volatile dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds saveBounds;
    private final AtomicLong outOfBounds = new AtomicLong();
    private final AtomicLong deletes = new AtomicLong();
    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong baseFallthroughs = new AtomicLong();

    AwfRegionStorage(final String name, final AwfWorld world, final AwfSettings settings, final Executor storageLane,
                     final long nowNanos, final Runnable onClose) {
        this.name = name;
        this.world = world;
        this.settings = settings;
        this.storageLane = storageLane;
        this.lastCommitNanos = new AtomicLong(nowNanos);
        this.onClose = onClose;
    }

    /** The FILE backend's store directory for a region folder. */
    public static Path storeFor(final Path regionFolder) {
        final Path absolute = regionFolder.toAbsolutePath().normalize();
        return absolute.resolveSibling(absolute.getFileName() + STORE_SUFFIX);
    }

    /** Whether a folder lies inside one of the listed worlds: some path element equals its name. */
    static boolean listed(final Path regionFolder, final java.util.Set<String> worlds) {
        if (worlds.isEmpty()) return false;
        for (final Path element : regionFolder.toAbsolutePath().normalize()) {
            if (worlds.contains(element.toString())) return true;
        }
        return false;
    }

    public String name() {
        return this.name;
    }

    /**
     * The chunk's NBT bytes if AWF owns it or the world's template has it, {@link #DELETED} if AWF
     * deleted it, or {@code null} when neither has it and the region file is authoritative.
     */
    public byte[] read(final int chunkX, final int chunkZ) throws IOException {
        final ChunkKey key = new ChunkKey(chunkX, chunkZ);
        this.reads.incrementAndGet();
        if (!this.world.owns(key)) {
            // An instance reads its template's chunk until it writes its own.
            final Optional<byte[]> fromTemplate = this.world.read(key);
            if (fromTemplate.isPresent()) return fromTemplate.get();
            this.baseFallthroughs.incrementAndGet();
            return null;
        }
        final Optional<byte[]> bytes = this.world.read(key);
        return bytes.isPresent() ? bytes.get() : DELETED;
    }

    /**
     * Takes a chunk's new NBT bytes, or its deletion when {@code nbt} is {@code null}. The array is
     * copied. Never touches a region file and never blocks on the backend.
     */
    public void write(final int chunkX, final int chunkZ, final byte[] nbt) throws IOException {
        this.transition.readLock().lock();
        try {
            if (this.discarding) return;
            if (this.closed.get()) throw new IOException("AWF storage " + this.name + " is closed");
            this.admittedHook.run();
            admittedWrite(chunkX, chunkZ, nbt);
        } finally {
            this.transition.readLock().unlock();
        }
    }

    private void admittedWrite(final int chunkX, final int chunkZ, final byte[] nbt) throws IOException {
        final dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds bounds = this.saveBounds;
        if (bounds != null && !bounds.contains(chunkX, chunkZ)) {
            this.outOfBounds.incrementAndGet();
            return;
        }
        final String prune = this.pruneKind;
        if (prune != null && nbt != null) {
            final ChunkKey key = new ChunkKey(chunkX, chunkZ);
            // Only chunks nothing has ever stored: an emptied chunk must still shadow what it held.
            if (!this.world.owns(key) && !this.world.baseHas(key) && ChunkPruning.isEmpty(prune, nbt)) {
                this.pruned.incrementAndGet();
                return;
            }
        }
        final ChunkKey key = new ChunkKey(chunkX, chunkZ);
        if (nbt == null) {
            this.world.delete(key);
            this.deletes.incrementAndGet();
            return;
        }
        if (nbt.length > AwfFile.MAX_CHUNK_BYTES) {
            throw new IOException("chunk " + key + " in " + this.name + " is " + nbt.length
                + " bytes; AWF stores at most " + AwfFile.MAX_CHUNK_BYTES);
        }
        this.world.write(key, nbt);
    }

    /**
     * Starts a commit on the storage lane when writes have waited at least the commit interval and
     * none is already running. Never blocks; called once a second.
     *
     * @return whether a commit was started
     */
    public boolean maintain(final long nowNanos) {
        if (this.discarding || this.closed.get() || this.world.dirtyCount() == 0 || this.world.pendingSaveCount() > 0) return false;
        final long last = this.lastCommitNanos.get();
        if (nowNanos - last < this.settings.commitIntervalSeconds() * 1_000_000_000L) return false;
        if (!this.lastCommitNanos.compareAndSet(last, nowNanos)) return false;
        this.world.save(this.settings.persistence(), this.storageLane, this.settings.commitAttempts())
            .whenComplete((ok, failed) -> {
                if (failed != null && !(unwrap(failed) instanceof AwfWorld.FencedException)) {
                    dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF commit for " + this.name
                        + " failed; its chunks stay in memory and the next commit retries them", failed);
                }
            });
        return true;
    }

    private static Throwable unwrap(final Throwable failed) {
        return failed instanceof java.util.concurrent.CompletionException && failed.getCause() != null
            ? failed.getCause() : failed;
    }

    /**
     * Commits everything written so far and returns once it is durable. On a region thread, where
     * blocking on disk is not allowed, the commit is started on the storage lane instead and this
     * returns at once.
     */
    public void flush() throws IOException {
        if (this.discarding || this.closed.get()) return;
        if (this.world.dirtyCount() == 0) return;
        if (ExecutionLane.of(Thread.currentThread().getName()) == ExecutionLane.REGION_TICK) {
            this.lastCommitNanos.set(Long.MIN_VALUE / 2);
            maintain(0L);
            return;
        }
        this.world.saveNow(this.settings.persistence(), this.settings.commitAttempts());
        this.lastCommitNanos.set(System.nanoTime());
    }

    /** Flushes and forgets this storage. Called when the engine closes the region storage. */
    public void close() throws IOException {
        // Under the write lock: every write admitted before this point is in the world, and every
        // later one sees closed and fails loudly, so the final commit below misses nothing.
        this.transition.writeLock().lock();
        try {
            if (!this.closed.compareAndSet(false, true)) return;
        } finally {
            this.transition.writeLock().unlock();
        }
        try {
            if (!this.discarding && this.world.dirtyCount() > 0) {
                this.world.saveNow(this.settings.persistence(), this.settings.commitAttempts());
            }
        } finally {
            try {
                this.world.closeStore();
            } finally {
                this.onClose.run();
            }
        }
    }

    /**
     * Prunes empty chunks nothing has stored yet (see {@link ChunkPruning}). For worlds whose
     * generator makes empty chunks, where regenerating one gives the same chunk.
     */
    public void pruneEmpty(final String storageFolder) {
        this.pruneKind = storageFolder;
    }

    /** Stops storing chunks outside {@code bounds} (null: store everywhere). */
    public void saveBounds(final dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds bounds) {
        this.saveBounds = bounds;
    }

    /** Writes dropped for being outside the save bounds since this storage opened. */
    public long outOfBounds() {
        return this.outOfBounds.get();
    }

    /** Stops pruning (null) or prunes for the given storage folder kind. */
    void pruneKind(final String storageFolder) {
        this.pruneKind = storageFolder;
    }

    /** Empty chunks not stored since this storage opened. */
    public long pruned() {
        return this.pruned.get();
    }

    /** Chunks this storage holds itself (not from its template): committed or waiting to be. */
    public int storedChunks() {
        return this.world.ownedCount();
    }

    /**
     * Stops committing: writes from now on are dropped and close commits nothing, so the store
     * keeps its last committed state. Chunks written but not yet committed are discarded too.
     * Used to unload a world without saving it; there is no way back for this storage.
     */
    public void discard() {
        this.transition.writeLock().lock();
        try {
            this.discarding = true;
            // Queued commits that have not started yet commit nothing; see AwfWorld#fence.
            this.world.fence();
        } finally {
            this.transition.writeLock().unlock();
        }
    }

    /** Sets the hook {@link #write} runs once admitted. Tests only. */
    void admittedHook(final Runnable hook) {
        this.admittedHook = hook;
    }

    /** Whether a close or discard is waiting for admitted writes to finish. Tests only. */
    boolean transitionWaiting() {
        return this.transition.hasQueuedThreads();
    }

    public boolean discarding() {
        return this.discarding;
    }

    public boolean closed() {
        return this.closed.get();
    }

    /** A point-in-time view for {@code /perf awf}. */
    public record Stats(String name, AwfMetrics.Snapshot world, long reads, long baseFallthroughs, long deletes,
                        long evicted) {}

    public Stats stats() {
        return new Stats(this.name, this.world.metrics(), this.reads.get(), this.baseFallthroughs.get(),
            this.deletes.get(), this.world.evicted());
    }
}
