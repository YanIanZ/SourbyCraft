package dev.iyanz.sourbycraft.awf.redis.fabric;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregate, bounded-cardinality telemetry for the Aurora Redis Fabric and the profile engine.
 *
 * <p>Every counter is a plain sum plus a few gauges; there is no label per player, profile or world,
 * so memory does not grow with population. {@link Snapshot} is immutable and cheap to render in
 * {@code /awf redis status} or {@code /perf awf}. Latency is reported as a mean over completed
 * operations since start, which is honest for a bounded set of pools; it is not a percentile.</p>
 */
public final class FabricMetrics {

    private final long startedAtMillis = System.currentTimeMillis();
    private final long startedAtNanos = System.nanoTime();

    private final LongAdder operations = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder connectionFailures = new LongAdder();
    private final LongAdder reconnects = new LongAdder();
    private final LongAdder poolSaturation = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();

    private final LongAdder leaseRenewFailures = new LongAdder();
    private final LongAdder ownershipConflicts = new LongAdder();
    private final LongAdder staleFenceRejections = new LongAdder();
    private final LongAdder profileOperationFailures = new LongAdder();
    private final LongAdder snapshotsWritten = new LongAdder();
    private final LongAdder snapshotNanos = new LongAdder();
    private final LongAdder eventsPublished = new LongAdder();
    private final LongAdder eventsConsumed = new LongAdder();
    private final LongAdder eventsRetried = new LongAdder();
    private final LongAdder deadLettered = new LongAdder();
    private final LongAdder duplicatesSuppressed = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheEvictions = new LongAdder();
    private final LongAdder cacheInvalidations = new LongAdder();
    private final LongAdder backpressureRejections = new LongAdder();

    private final AtomicLong streamBacklog = new AtomicLong();
    private final AtomicLong consumerLag = new AtomicLong();
    private final AtomicLong connected = new AtomicLong();
    private final AtomicLong lastLatencyMicros = new AtomicLong(-1);

    /** One completed Redis call. */
    public void operation(final long nanos, final boolean ok) {
        this.operations.increment();
        if (nanos > 0) {
            this.totalLatencyNanos.add(nanos);
            this.lastLatencyMicros.set(nanos / 1_000L);
        }
        if (!ok) this.failures.increment();
    }

    public void timeout() {
        this.timeouts.increment();
    }

    public void connectionFailure() {
        this.connectionFailures.increment();
    }

    public void reconnect() {
        this.reconnects.increment();
    }

    public void poolSaturation() {
        this.poolSaturation.increment();
    }

    public void leaseRenewFailure() {
        this.leaseRenewFailures.increment();
    }

    public void ownershipConflict() {
        this.ownershipConflicts.increment();
    }

    public void staleFence() {
        this.staleFenceRejections.increment();
    }

    public void profileFailure() {
        this.profileOperationFailures.increment();
    }

    public void snapshot(final long nanos) {
        this.snapshotsWritten.increment();
        if (nanos > 0) this.snapshotNanos.add(nanos);
    }

    public void eventPublished() {
        this.eventsPublished.increment();
    }

    public void eventConsumed() {
        this.eventsConsumed.increment();
    }

    public void eventRetried() {
        this.eventsRetried.increment();
    }

    public void deadLettered() {
        this.deadLettered.increment();
    }

    public void duplicateSuppressed() {
        this.duplicatesSuppressed.increment();
    }

    public void cacheHit() {
        this.cacheHits.increment();
    }

    public void cacheMiss() {
        this.cacheMisses.increment();
    }

    public void cacheEviction() {
        this.cacheEvictions.increment();
    }

    public void cacheInvalidation() {
        this.cacheInvalidations.increment();
    }

    public void backpressure() {
        this.backpressureRejections.increment();
    }

    public void streamBacklog(final long value) {
        this.streamBacklog.set(Math.max(0L, value));
    }

    public void consumerLag(final long value) {
        this.consumerLag.set(Math.max(0L, value));
    }

    public void connected(final boolean value) {
        this.connected.set(value ? 1L : 0L);
    }

    public long eventsPublished() {
        return this.eventsPublished.sum();
    }

    public long cacheHits() {
        return this.cacheHits.sum();
    }

    public long cacheMisses() {
        return this.cacheMisses.sum();
    }

    public long profileFailures() {
        return this.profileOperationFailures.sum();
    }

    /** Whether the last probe reached Redis. */
    public boolean connected() {
        return this.connected.get() != 0L;
    }

    public Snapshot snapshot() {
        final long ops = this.operations.sum();
        final long elapsedMillis = Math.max(1L, (System.nanoTime() - this.startedAtNanos) / 1_000_000L);
        final long hits = this.cacheHits.sum();
        final long misses = this.cacheMisses.sum();
        return new Snapshot(this.startedAtMillis, ops, this.failures.sum(), this.timeouts.sum(),
            this.connectionFailures.sum(), this.reconnects.sum(), this.poolSaturation.sum(),
            ops == 0L ? 0.0 : (double) this.totalLatencyNanos.sum() / (double) ops / 1_000_000.0,
            this.lastLatencyMicros.get() < 0 ? -1.0 : this.lastLatencyMicros.get() / 1000.0,
            ops * 1000.0 / (double) elapsedMillis, this.streamBacklog.get(), this.consumerLag.get(),
            hits + misses == 0 ? -1.0 : (double) hits / (double) (hits + misses),
            hits, misses, this.cacheEvictions.sum(), this.cacheInvalidations.sum(),
            this.leaseRenewFailures.sum(), this.ownershipConflicts.sum(), this.staleFenceRejections.sum(),
            this.profileOperationFailures.sum(), this.snapshotsWritten.sum(),
            this.snapshotsWritten.sum() == 0 ? -1.0 : (double) this.snapshotNanos.sum() / this.snapshotsWritten.sum() / 1_000_000.0,
            this.eventsPublished.sum(), this.eventsConsumed.sum(), this.eventsRetried.sum(), this.deadLettered.sum(),
            this.duplicatesSuppressed.sum(), this.backpressureRejections.sum(), this.connected.get() != 0L);
    }

    /**
     * Immutable aggregate view.
     *
     * @param meanLatencyMillis mean over completed calls since start, or {@code 0}
     * @param lastLatencyMillis last call, or {@code -1} when none completed
     * @param cacheHitRatio {@code -1} until at least one lookup happened
     * @param meanSnapshotMillis {@code -1} until at least one snapshot was written
     */
    public record Snapshot(long startedAtMillis, long operations, long failures, long timeouts, long connectionFailures,
                           long reconnects, long poolSaturation, double meanLatencyMillis, double lastLatencyMillis,
                           double operationsPerSecond, long streamBacklog, long consumerLag, double cacheHitRatio,
                           long cacheHits, long cacheMisses, long cacheEvictions, long cacheInvalidations,
                           long leaseRenewFailures, long ownershipConflicts, long staleFenceRejections,
                           long profileFailures, long snapshots, double meanSnapshotMillis, long eventsPublished,
                           long eventsConsumed, long eventsRetried, long deadLettered, long duplicatesSuppressed,
                           long backpressureRejections, boolean connected) {
    }
}
