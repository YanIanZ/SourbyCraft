package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory MultiProfile metrics for {@code /perf multiprofile} (Build 55): committed-switch latency percentiles,
 * I/O queue wait percentiles, the oldest outstanding salvage record and open kick vote this node has seen, and
 * counters for refusals, vetoes and audit drops. Everything is bounded (fixed rings) and lock-light (one short
 * synchronized section per sample); nothing here is persisted or exact across restarts, and the rows say so.
 */
public final class ProfileMetrics {

    /** Samples kept per latency series; percentiles are over the most recent ones. */
    public static final int WINDOW = 1024;

    /** A fixed ring of {@code long} samples with percentiles over what it holds. */
    static final class LatencyWindow {
        private final long[] ring = new long[WINDOW];
        private int size;
        private int next;
        private long count;

        synchronized void record(final long value) {
            this.ring[this.next] = Math.max(0L, value);
            this.next = (this.next + 1) % WINDOW;
            if (this.size < WINDOW) this.size++;
            this.count++;
        }

        synchronized long count() {
            return this.count;
        }

        /** The {@code percentile}th (0-100) sample of the window, or {@code -1} when empty. */
        synchronized long percentile(final int percentile) {
            if (this.size == 0) return -1L;
            final long[] sorted = Arrays.copyOf(this.ring, this.size);
            Arrays.sort(sorted);
            final int index = (int) Math.min(this.size - 1L, Math.max(0L, Math.round((percentile / 100.0) * (this.size - 1))));
            return sorted[index];
        }

        synchronized int size() {
            return this.size;
        }
    }

    /** What {@code /perf multiprofile} renders; one consistent read of every series. */
    public record Snapshot(long switchesCommitted, long switchP50Millis, long switchP95Millis, long switchP99Millis,
                           long switchesFailed, long switchesRefusedCooldown, long switchesVetoed, long guardTimeouts,
                           long queueWaitSamples, long queueWaitP50Micros, long queueWaitP95Micros, long queueWaitP99Micros,
                           int queueWaiting, int queueRunning, int queuePeakWaiting, int queuePartitions,
                           long oldestSalvageAgeMillis, int outstandingSalvage, long oldestVoteAgeMillis, int openVotes,
                           long auditRecorded, long auditDropped, long auditWriteFailures, long profileEventsDropped) {}

    private final LatencyWindow switchLatency = new LatencyWindow();
    private final LatencyWindow queueWait = new LatencyWindow();
    private final AtomicLong switchesFailed = new AtomicLong();
    private final AtomicLong cooldownRefusals = new AtomicLong();
    private final AtomicLong vetoes = new AtomicLong();
    private final AtomicLong guardTimeouts = new AtomicLong();
    private final AtomicLong profileEventsDropped = new AtomicLong();
    /** Outstanding salvage records this node created or saw, by id, value the store clock they were created at. */
    private final ConcurrentHashMap<UUID, Long> salvage = new ConcurrentHashMap<>();
    /** Open kick votes this node created or saw, by id, value the store clock they were opened at. */
    private final ConcurrentHashMap<UUID, Long> votes = new ConcurrentHashMap<>();
    /** Most tracked ids per map; beyond it the oldest tracked entry is dropped (the rows are indicative). */
    static final int MAX_TRACKED = 4096;

    void switchCommitted(final long durationMillis) {
        this.switchLatency.record(durationMillis);
    }

    void switchFailed() {
        this.switchesFailed.incrementAndGet();
    }

    void cooldownRefused() {
        this.cooldownRefusals.incrementAndGet();
    }

    void vetoed() {
        this.vetoes.incrementAndGet();
    }

    void guardTimedOut() {
        this.guardTimeouts.incrementAndGet();
    }

    void profileEventDropped() {
        this.profileEventsDropped.incrementAndGet();
    }

    /** Queue wait of one step, nanoseconds. */
    void queueWaited(final long nanos) {
        this.queueWait.record(nanos / 1_000L);
    }

    void salvageOutstanding(final UUID salvageId, final long createdAtStoreMillis) {
        track(this.salvage, salvageId, createdAtStoreMillis);
    }

    void salvageSettled(final UUID salvageId) {
        this.salvage.remove(salvageId);
    }

    void voteOpened(final UUID voteId, final long openedAtStoreMillis) {
        track(this.votes, voteId, openedAtStoreMillis);
    }

    void voteClosed(final UUID voteId) {
        this.votes.remove(voteId);
    }

    private static void track(final ConcurrentHashMap<UUID, Long> map, final UUID id, final long at) {
        if (map.size() >= MAX_TRACKED) {
            UUID oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (final var entry : map.entrySet()) {
                if (entry.getValue() < oldestAt) {
                    oldestAt = entry.getValue();
                    oldest = entry.getKey();
                }
            }
            if (oldest != null) map.remove(oldest);
        }
        map.put(id, at);
    }

    private static long oldestAge(final ConcurrentHashMap<UUID, Long> map, final long storeNow) {
        long oldest = Long.MAX_VALUE;
        for (final long at : map.values()) oldest = Math.min(oldest, at);
        return oldest == Long.MAX_VALUE ? -1L : Math.max(0L, storeNow - oldest);
    }

    /**
     * @param storeNow the store clock now (ages of salvage and votes are judged on it)
     * @param queue the engine's I/O queue, for depth and partitions
     * @param audit the audit log, or {@code null} when off
     */
    public Snapshot snapshot(final long storeNow, final ProfileIoQueue queue, final ProfileAudit audit) {
        return new Snapshot(this.switchLatency.count(), this.switchLatency.percentile(50), this.switchLatency.percentile(95),
            this.switchLatency.percentile(99), this.switchesFailed.get(), this.cooldownRefusals.get(), this.vetoes.get(),
            this.guardTimeouts.get(), this.queueWait.count(), this.queueWait.percentile(50), this.queueWait.percentile(95),
            this.queueWait.percentile(99), queue.waitingSteps(), queue.runningSteps(), queue.peakWaiting(), queue.partitions(),
            oldestAge(this.salvage, storeNow), this.salvage.size(), oldestAge(this.votes, storeNow), this.votes.size(),
            audit == null ? 0L : audit.recorded(), audit == null ? 0L : audit.dropped(), audit == null ? 0L : audit.writeFailures(),
            this.profileEventsDropped.get());
    }
}
