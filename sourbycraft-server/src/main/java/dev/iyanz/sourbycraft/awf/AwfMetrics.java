package dev.iyanz.sourbycraft.awf;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The metrics {@code aurora-world-fabric.md} requires, per world: resident and dirty chunks,
 * save queue depth, age of the oldest pending save, serialization and backend latency
 * percentiles, retries, failures, and bytes read and written.
 *
 * <p>"Serialization" here is the storage lane's preparation of a commit (copying the dirty set
 * into an immutable snapshot); "backend" is the commit itself, object writes through the
 * generation swap. Chunk serialization to NBT happens before AWF sees the bytes and is not
 * measured here.</p>
 */
public final class AwfMetrics {

    /** A point-in-time copy. */
    public record Snapshot(long residentChunks, long dirtyChunks, long saveQueueDepth, long oldestPendingSaveMillis,
                           LatencyRecorder.Percentiles serialization, LatencyRecorder.Percentiles backend,
                           long retries, long failures, long bytesRead, long bytesWritten, long materialized) {}

    final AtomicLong retries = new AtomicLong();
    final AtomicLong failures = new AtomicLong();
    final AtomicLong bytesRead = new AtomicLong();
    final AtomicLong bytesWritten = new AtomicLong();
    final AtomicLong materialized = new AtomicLong();
    final LatencyRecorder serialization = new LatencyRecorder();
    final LatencyRecorder backend = new LatencyRecorder();
}
