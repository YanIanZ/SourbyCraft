package dev.iyanz.sourbycraft.perf;

import java.util.ArrayList;
import java.util.List;

/**
 * The last hour of the server's health, one sample per minute, for {@code /perf history}.
 *
 * <p>The collector offers every published snapshot (once a second); one is kept per wall-clock
 * minute. The ring holds {@link #CAPACITY} samples and never grows, so memory is fixed whatever
 * the uptime.</p>
 */
public final class PerformanceHistory {

    public static final int CAPACITY = 60;
    public static final PerformanceHistory GLOBAL = new PerformanceHistory();

    /**
     * One minute.
     *
     * @param worstTps worst region TPS over the last minute
     * @param worstMspt worst region average MSPT over the last minute
     * @param processCpuPercent process CPU, NaN when unknown
     */
    public record Sample(long epochMillis, double worstTps, double worstMspt, double processCpuPercent,
                         long heapUsedBytes, long heapMaxBytes) {}

    private final Sample[] ring = new Sample[CAPACITY];
    private int next;
    private int size;
    private long lastMinute = Long.MIN_VALUE;

    /** Keeps the first sample of each wall-clock minute. Called by the collector thread. */
    public synchronized boolean offer(final Sample sample) {
        final long minute = Math.floorDiv(sample.epochMillis(), 60_000L);
        if (minute == this.lastMinute) return false;
        this.lastMinute = minute;
        this.ring[this.next] = sample;
        this.next = (this.next + 1) % CAPACITY;
        if (this.size < CAPACITY) this.size++;
        return true;
    }

    /** Oldest first. */
    public synchronized List<Sample> samples() {
        final List<Sample> out = new ArrayList<>(this.size);
        for (int i = 0; i < this.size; i++) {
            out.add(this.ring[Math.floorMod(this.next - this.size + i, CAPACITY)]);
        }
        return out;
    }
}
