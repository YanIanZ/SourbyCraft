package dev.iyanz.sourbycraft.io;

import dev.iyanz.sourbycraft.async.AsyncWorkerPool;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Coalesces chunk save requests per region within a batch window, then
 * submits each window's batch to an {@link AsyncWorkerPool} for off-thread write.
 */
public final class ChunkSaveBatcher {

    private static final class Key {
        final String world; final int rx; final int rz;
        Key(String world, int rx, int rz) { this.world = world; this.rx = rx; this.rz = rz; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o; return rx == k.rx && rz == k.rz && world.equals(k.world);
        }
        @Override public int hashCode() { return Objects.hash(world, rx, rz); }
    }

    private final long batchWindowMs;
    private final AsyncWorkerPool<ChunkSaveSnapshot, ChunkSaveDiff> pool;

    private final Map<Key, List<ChunkSaveSnapshot.Entry>> pending = new HashMap<>();
    private final Map<Key, Long> firstSeen = new HashMap<>();
    private final ScheduledExecutorService flusher;

    public ChunkSaveBatcher(
        ExecutorService workerExec,
        long batchWindowMs,
        int queueCap,
        int circuitThreshold,
        long circuitCooldownMs,
        double watchdogMultiplier,
        Function<ChunkSaveSnapshot, ChunkSaveDiff> writer
    ) {
        this.batchWindowMs = batchWindowMs;
        this.pool = new AsyncWorkerPool<>(
            "chunk-save", workerExec, queueCap,
            circuitThreshold, circuitCooldownMs, watchdogMultiplier, writer);
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SourbyCraft-ChunkSaveBatcher-Flusher");
            t.setDaemon(true);
            return t;
        });
        this.flusher.scheduleAtFixedRate(this::flushExpired,
            batchWindowMs, Math.max(10, batchWindowMs / 4), TimeUnit.MILLISECONDS);
    }

    /** Enqueue a single chunk write. Coalesced into the next batch window. */
    public synchronized void enqueue(String worldId, int regionX, int regionZ,
                                     int chunkX, int chunkZ, byte[] nbt) {
        Key k = new Key(worldId, regionX, regionZ);
        pending.computeIfAbsent(k, x -> new ArrayList<>())
               .add(new ChunkSaveSnapshot.Entry(chunkX, chunkZ, nbt));
        firstSeen.putIfAbsent(k, System.currentTimeMillis());
    }

    private synchronized void flushExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Key, Long>> it = firstSeen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Long> e = it.next();
            if (now - e.getValue() < batchWindowMs) continue;
            Key k = e.getKey();
            List<ChunkSaveSnapshot.Entry> entries = pending.remove(k);
            it.remove();
            if (entries == null || entries.isEmpty()) continue;
            ChunkSaveSnapshot snap = new ChunkSaveSnapshot(k.world, k.rx, k.rz, entries);
            pool.submit(snap); // backpressure: drop on floor when full (T7 deferred inline fallback)
        }
    }

    /** Drain completed diffs onto the consumer. Call from main thread. */
    public void drainDiffs(Consumer<ChunkSaveDiff> consumer) {
        pool.drainDiffs(consumer);
    }

    public AsyncWorkerPool<ChunkSaveSnapshot, ChunkSaveDiff> pool() { return pool; }

    public void shutdown() {
        flusher.shutdownNow();
        pool.shutdown();
    }
}
