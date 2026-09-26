package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.atomic.LongAdder;

/**
 * Server-wide network counters: wire bytes and packets in each direction, and connections opened.
 *
 * <p>Bytes are counted at the head of each channel pipeline, so they are what crossed the socket —
 * after compression and encryption. Packets are counted next to the packet handler, so they are
 * protocol packets, whatever size they were on the wire. Increments are {@link LongAdder}s on the
 * Netty event loop; their cost has not been measured and is not claimed to be negligible.</p>
 *
 * <p>Rates come from {@link #sample}, which the performance collector calls once a second: the rate
 * is the difference between the last two samples divided by the time between them.</p>
 */
public final class NetworkCounters {

    /** The process's counters. */
    public static final NetworkCounters GLOBAL = new NetworkCounters();

    /** Totals at one instant. */
    public record Totals(long bytesIn, long bytesOut, long packetsIn, long packetsOut, long connections, long nanos) {}

    /**
     * Per-second rates over the last sample interval.
     *
     * @param available false until two samples exist
     */
    public record Rates(boolean available, double bytesInPerSecond, double bytesOutPerSecond,
                        double packetsInPerSecond, double packetsOutPerSecond) {
        static final Rates UNAVAILABLE = new Rates(false, 0, 0, 0, 0);
    }

    final LongAdder bytesIn = new LongAdder();
    final LongAdder bytesOut = new LongAdder();
    final LongAdder packetsIn = new LongAdder();
    final LongAdder packetsOut = new LongAdder();
    final LongAdder connections = new LongAdder();
    private Totals previous;
    private volatile Rates rates = Rates.UNAVAILABLE;
    private volatile boolean enabled = true;

    /** {@code aurora.network.counters} (LIVE): false stops every increment; totals stop growing. */
    public void setEnabled(final boolean value) { this.enabled = value; }
    public boolean enabled() { return this.enabled; }

    public void bytesIn(final long n) { if (this.enabled) this.bytesIn.add(n); }
    public void bytesOut(final long n) { if (this.enabled) this.bytesOut.add(n); }
    public void packetIn() { if (this.enabled) this.packetsIn.increment(); }
    public void packetOut() { if (this.enabled) this.packetsOut.increment(); }
    public void connectionOpened() { if (this.enabled) this.connections.increment(); }

    public Totals totals(final long nowNanos) {
        return new Totals(this.bytesIn.sum(), this.bytesOut.sum(), this.packetsIn.sum(), this.packetsOut.sum(),
            this.connections.sum(), nowNanos);
    }

    /** Takes a sample; called from one thread (the collector). */
    public synchronized void sample(final long nowNanos) {
        final Totals now = totals(nowNanos);
        if (this.previous != null) {
            this.rates = rate(this.previous, now);
        }
        this.previous = now;
    }

    public Rates rates() {
        return this.rates;
    }

    static Rates rate(final Totals older, final Totals newer) {
        final double seconds = (newer.nanos() - older.nanos()) / 1_000_000_000.0;
        if (seconds <= 0) return Rates.UNAVAILABLE;
        return new Rates(true,
            (newer.bytesIn() - older.bytesIn()) / seconds,
            (newer.bytesOut() - older.bytesOut()) / seconds,
            (newer.packetsIn() - older.packetsIn()) / seconds,
            (newer.packetsOut() - older.packetsOut()) / seconds);
    }
}
