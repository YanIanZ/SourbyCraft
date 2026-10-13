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

    /** Attempts a read of stored bytes gets before the position is fenced; see {@link #read}. */
    static final int READ_ATTEMPTS = 3;
    /** Pause before the second and third attempt. */
    private static final long[] READ_BACKOFF_MILLIS = {100L, 400L};
    /** No further attempt starts once this much time has gone into one read (a frozen backend). */
    static final long READ_RETRY_BUDGET_NANOS = 5_000_000_000L;
    /**
     * After a read gave up, every read in this server gets a single attempt for this long. A chunk
     * load pulls in hundreds of neighbouring chunks, and the chunk system has few I/O threads shared
     * by every world: retrying each of them would back the I/O queue up for minutes (and a
     * {@code save-all flush} waits for that queue on the region threads).
     */
    static final long FAIL_FAST_NANOS = 5_000_000_000L;
    /** {@link System#nanoTime} until which reads are not retried, 0 for none; see {@link #FAIL_FAST_NANOS}. */
    private static final AtomicLong failFastUntil = new AtomicLong();
    /**
     * Positions whose stored bytes could not be read, with the time they were fenced. The engine
     * treats a failed read as "no data" and generates the chunk again; writes of that position are
     * refused from then on, so the regenerated chunk can never replace the stored one. A later
     * successful read on the chunk-load path lifts the fence (see {@link #recovered}).
     */
    private final java.util.Map<ChunkKey, Long> unreadable = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong readRetries = new AtomicLong();
    private final AtomicLong readFailures = new AtomicLong();
    private final AtomicLong refusedWrites = new AtomicLong();
    /** Whether a read is on the engine's chunk-load path; tests replace it. */
    private volatile java.util.function.BooleanSupplier loadPath = AwfRegionStorage::onChunkLoadPath;
    /** Set when {@link #close} committed everything but the backend could not release the store. */
    private volatile boolean releaseFailed;
    /** Whether the last commit attempt (periodic, flush or close) failed; for flush reporting. */
    private volatile boolean lastCommitFailed;

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

    /** Whether a storage folder belongs to one of the listed worlds; see {@link #worldNames}. */
    static boolean listed(final Path regionFolder, final java.util.Set<String> worlds) {
        if (worlds.isEmpty()) return false;
        for (final String name : worldNames(regionFolder)) {
            if (worlds.contains(name)) return true;
        }
        return false;
    }

    /**
     * The world names a storage folder ({@code region}, {@code entities}, {@code poi}) belongs to,
     * read from the folder's own end of the path, never from the directories the server lives in:
     * <ul>
     *   <li>{@code <level>/dimensions/<namespace>/<name>/<storage>} (every dimension on 26.2):
     *       the dimension's {@code <name>} and the level folder {@code <level>};</li>
     *   <li>any other layout inside the server directory: the first element of the path
     *       relative to it ({@code world/region} → {@code world});</li>
     *   <li>any other layout outside it: the storage folder's parent and grandparent
     *       ({@code <world>/region}, {@code <world>/DIM-1/region}).</li>
     * </ul>
     * Matching any path element instead let a managed world named like an ancestor directory of
     * the server ({@code home} for {@code /home/mc/server}) claim every world's storage.
     */
    static java.util.Set<String> worldNames(final Path regionFolder) {
        final Path folder = regionFolder.toAbsolutePath().normalize();
        final int n = folder.getNameCount();
        final java.util.Set<String> names = new java.util.LinkedHashSet<>(4);
        if (n >= 5 && folder.getName(n - 4).toString().equals("dimensions")) {
            names.add(folder.getName(n - 2).toString());
            names.add(folder.getName(n - 5).toString());
            return names;
        }
        final Path cwd = Path.of("").toAbsolutePath().normalize();
        if (folder.startsWith(cwd) && !folder.equals(cwd)) {
            final Path relative = cwd.relativize(folder);
            if (relative.getNameCount() >= 2) names.add(relative.getName(0).toString());
            return names;
        }
        if (n >= 2) names.add(folder.getName(n - 2).toString());
        if (n >= 3) names.add(folder.getName(n - 3).toString());
        return names;
    }

    public String name() {
        return this.name;
    }

    /**
     * The chunk's NBT bytes if AWF owns it or the world's template has it, {@link #DELETED} if AWF
     * deleted it, or {@code null} when neither has it and the region file is authoritative.
     *
     * <p><b>A failed read is never "no data".</b> Reading stored bytes from the backend is retried
     * ({@link #READ_ATTEMPTS} attempts, backing off, at most {@link #READ_RETRY_BUDGET_NANOS} per
     * read, never on a region thread, never after close, and not for {@link #FAIL_FAST_NANOS} after
     * a read anywhere in the server gave up). If it still fails the
     * {@link IOException} is thrown, which the engine answers by generating the chunk again; so the
     * position is fenced first: every later write of it is refused and counted, and the stored bytes
     * stay as they were. The fence is lifted only by a successful read on the chunk-load path
     * ({@code moonrise$readData}): that read means the regenerated chunk has left memory and the
     * stored one is being loaded, so writes from then on derive from the stored bytes. A successful
     * read on another path (a structure scan) returns the stored bytes but keeps the fence, because
     * the regenerated chunk may still be loaded and would be saved over them.</p>
     */
    public byte[] read(final int chunkX, final int chunkZ) throws IOException {
        final ChunkKey key = new ChunkKey(chunkX, chunkZ);
        this.reads.incrementAndGet();
        final boolean owned = this.world.owns(key);
        final Optional<byte[]> bytes = readStored(key);
        if (this.unreadable.containsKey(key)) recovered(key);
        if (owned) return bytes.isPresent() ? bytes.get() : DELETED;
        // An instance reads its template's chunk until it writes its own.
        if (bytes.isPresent()) return bytes.get();
        this.baseFallthroughs.incrementAndGet();
        return null;
    }

    private Optional<byte[]> readStored(final ChunkKey key) throws IOException {
        final long started = System.nanoTime();
        // A region thread must not sleep: one attempt there, as before.
        final boolean mayRetry = ExecutionLane.of(Thread.currentThread().getName()) != ExecutionLane.REGION_TICK
            && !failingFast(started);
        for (int attempt = 1; ; attempt++) {
            try {
                return this.world.read(key);
            } catch (final IOException failed) {
                final boolean again = mayRetry && attempt < READ_ATTEMPTS && !this.closed.get()
                    && System.nanoTime() - started < READ_RETRY_BUDGET_NANOS;
                if (again) {
                    this.readRetries.incrementAndGet();
                    try {
                        Thread.sleep(READ_BACKOFF_MILLIS[Math.min(attempt, READ_BACKOFF_MILLIS.length) - 1]);
                        continue;
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (attempt > 1) failFastUntil.set(System.nanoTime() + FAIL_FAST_NANOS);
                throw fence(key, failed, attempt);
            }
        }
    }

    private IOException fence(final ChunkKey key, final IOException failed, final int attempts) {
        this.readFailures.incrementAndGet();
        final boolean first = this.unreadable.putIfAbsent(key, System.nanoTime()) == null;
        final String message = "AWF could not read chunk " + key + " of " + this.name + " (" + attempts
            + " attempt(s)): " + failed.getMessage() + ". The stored chunk is kept: writes of this position are"
            + " refused until it is read back when the chunk loads again; a chunk the server generates in its"
            + " place meanwhile is not saved";
        // The chunk system logs the exception with its stack; one line here names the consequence.
        if (first) dev.iyanz.sourbycraft.util.SourbyLogger.error(message);
        return new IOException(message, failed);
    }

    private void recovered(final ChunkKey key) {
        if (!this.loadPath.getAsBoolean()) return;
        if (this.unreadable.remove(key) != null) {
            dev.iyanz.sourbycraft.util.SourbyLogger.info("AWF read chunk " + key + " of " + this.name
                + " again; its stored bytes were loaded and writes of it are accepted again");
        }
    }

    /** Whether the caller is the chunk system's load I/O ({@code RegionFileStorage.moonrise$readData}). */
    private static boolean onChunkLoadPath() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
            "moonrise$readData".equals(frame.getMethodName())
                && frame.getClassName().endsWith(".RegionFileStorage")));
    }

    private static boolean failingFast(final long now) {
        final long until = failFastUntil.get();
        return until != 0 && now - until < 0;
    }

    /** Ends the fail-fast window. Tests only. */
    static void resetFailFast() {
        failFastUntil.set(0);
    }

    /** Replaces the chunk-load-path check. Tests only. */
    void loadPath(final java.util.function.BooleanSupplier loadPath) {
        this.loadPath = loadPath;
    }

    /** Positions fenced because their stored bytes could not be read. */
    public java.util.Set<ChunkKey> unreadable() {
        return java.util.Set.copyOf(this.unreadable.keySet());
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
        if (!this.unreadable.isEmpty() && this.unreadable.containsKey(new ChunkKey(chunkX, chunkZ))) {
            // Not derived from the stored bytes, which could not be read: never let it replace them.
            this.refusedWrites.incrementAndGet();
            return;
        }
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
                if (failed == null) this.lastCommitFailed = false;
                if (failed != null && !(unwrap(failed) instanceof AwfWorld.FencedException)) {
                    this.lastCommitFailed = true;
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
            if (this.lastCommitFailed) {
                // save-all reports success from here; say that this storage is not durable.
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF commits for " + this.name + " are failing: "
                    + this.world.dirtyCount() + " chunk(s) are only in memory; this flush starts another attempt"
                    + " but does not wait for it");
            }
            this.lastCommitNanos.set(Long.MIN_VALUE / 2);
            maintain(0L);
            return;
        }
        try {
            this.world.saveNow(this.settings.persistence(), this.settings.commitAttempts());
            this.lastCommitFailed = false;
        } catch (final IOException failed) {
            this.lastCommitFailed = true;
            throw failed;
        }
        this.lastCommitNanos.set(System.nanoTime());
    }

    /**
     * {@link #close} could not confirm its final commit: the chunks it names may be lost. (A close
     * whose commit succeeded but whose backend release failed does not throw; see
     * {@link #releaseFailed()}.)
     */
    public static final class CloseFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final int uncommitted;

        CloseFailure(final String message, final int uncommitted, final Throwable cause) {
            super(message, cause);
            this.uncommitted = uncommitted;
        }

        /** Chunks written since the last confirmed commit when the final commit failed. */
        public int uncommitted() {
            return this.uncommitted;
        }
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
        if (!this.unreadable.isEmpty()) {
            dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF " + this.name + ": " + this.unreadable.size()
                + " chunk position(s) could not be read this session; their stored bytes were kept and "
                + this.refusedWrites.get() + " write(s) of chunks generated in their place were not saved");
        }
        IOException commitFailed = null;
        int uncommitted = 0;
        try {
            try {
                if (!this.discarding && this.world.dirtyCount() > 0) {
                    this.world.saveNow(this.settings.persistence(), this.settings.commitAttempts());
                }
            } catch (final IOException failed) {
                commitFailed = failed;
                uncommitted = this.world.dirtyCount();
            }
        } finally {
            try {
                this.world.closeStore();
            } catch (final IOException releaseFailed) {
                if (commitFailed != null) {
                    commitFailed.addSuppressed(releaseFailed);
                } else {
                    // Everything is committed; only the backend's claim (a Redis lease) stays until it
                    // expires. Not a failure of the close: callers would report chunks lost.
                    this.releaseFailed = true;
                    dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF committed " + this.name + " but its backend could"
                        + " not release it (" + releaseFailed.getMessage() + "); no chunks are lost, and a lease"
                        + " expires on its own");
                }
            } finally {
                this.onClose.run();
            }
        }
        if (commitFailed != null) {
            throw new CloseFailure("AWF could not confirm the final commit of " + this.name + ": " + uncommitted
                + " chunk(s) written since the last confirmed commit are lost, unless the backend applied the"
                + " commit after reporting the failure (" + commitFailed.getMessage() + ")", uncommitted, commitFailed);
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

    /** Whether {@link #close} committed everything but could not release the store on its backend. */
    public boolean releaseFailed() {
        return this.releaseFailed;
    }

    public boolean closed() {
        return this.closed.get();
    }

    /** A point-in-time view for {@code /perf awf}. */
    public record Stats(String name, AwfMetrics.Snapshot world, long reads, long baseFallthroughs, long deletes,
                        long evicted, long readRetries, long readFailures, int unreadable, long refusedWrites) {}

    public Stats stats() {
        return new Stats(this.name, this.world.metrics(), this.reads.get(), this.baseFallthroughs.get(),
            this.deletes.get(), this.world.evicted(), this.readRetries.get(), this.readFailures.get(),
            this.unreadable.size(), this.refusedWrites.get());
    }
}
