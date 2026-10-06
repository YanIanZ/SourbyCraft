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
        if (this.closed.get()) throw new IOException("AWF storage " + this.name + " is closed");
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
        if (this.closed.get() || this.world.dirtyCount() == 0 || this.world.pendingSaveCount() > 0) return false;
        final long last = this.lastCommitNanos.get();
        if (nowNanos - last < this.settings.commitIntervalSeconds() * 1_000_000_000L) return false;
        if (!this.lastCommitNanos.compareAndSet(last, nowNanos)) return false;
        this.world.save(this.settings.persistence(), this.storageLane, this.settings.commitAttempts())
            .whenComplete((ok, failed) -> {
                if (failed != null) {
                    dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF commit for " + this.name
                        + " failed; its chunks stay in memory and the next commit retries them", failed);
                }
            });
        return true;
    }

    /**
     * Commits everything written so far and returns once it is durable. On a region thread, where
     * blocking on disk is not allowed, the commit is started on the storage lane instead and this
     * returns at once.
     */
    public void flush() throws IOException {
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
        if (!this.closed.compareAndSet(false, true)) return;
        try {
            if (this.world.dirtyCount() > 0) {
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
