package dev.iyanz.sourbycraft.abysslink;

import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-cardinality, label-free counters for the optional AbyssLink control connection.
 *
 * <p>This type only aggregates observations. It never records a node ID, player UUID, player
 * name, IP address, certificate detail, error text or threat detail, so a snapshot cannot leak
 * the identities the protocol forbids in telemetry. Counters are monotonic lifetime totals owned
 * by the caller; this class does not connect, schedule or retry anything by itself.</p>
 *
 * <p>Every counter is a {@link LongAdder}. {@link #snapshot()} sums each adder independently, so
 * a snapshot is <strong>observational, not transactional</strong>: concurrent updates may land
 * between two sums, and the result is not a point-in-time image of a single instant. Treat it as
 * a diagnostic sample for {@code /perf network}-style readouts, never as an invariant, a billing
 * total or a security decision input. {@link #rttNanos} is a single {@code volatile} read taken
 * once per snapshot; it is control-plane round-trip time, not player latency and not a
 * percentile/aggregate.</p>
 */
public final class AbyssLinkMetrics {

    /** Sessions that completed handshake and became active, as recorded by the caller. */
    public final LongAdder connections = new LongAdder();

    /** New connection attempts made after a prior session ended or failed. */
    public final LongAdder reconnects = new LongAdder();

    /** Connections or frames refused before becoming usable (policy, authentication, framing). */
    public final LongAdder rejected = new LongAdder();

    /** Handshake, heartbeat or request deadlines that expired. */
    public final LongAdder timeouts = new LongAdder();

    /** Writes or admissions refused because a bounded queue/watermark/pending cap was full. */
    public final LongAdder backpressure = new LongAdder();

    /** Complete inbound frames accepted by the codec. */
    public final LongAdder framesIn = new LongAdder();

    /** Complete outbound frames handed to the codec. */
    public final LongAdder framesOut = new LongAdder();

    /** Inbound application-frame bytes, including the 20-byte header; excludes TLS/TCP overhead. */
    public final LongAdder bytesIn = new LongAdder();

    /** Outbound application-frame bytes, including the 20-byte header; excludes TLS/TCP overhead. */
    public final LongAdder bytesOut = new LongAdder();

    /** Ping answers served from the bounded ping/miss cache. */
    public final LongAdder cacheHits = new LongAdder();

    /** Bounded advisory threat signals accepted, not attacks blocked or players punished. */
    public final LongAdder threats = new LongAdder();

    /**
     * Most recent completed control round-trip in nanoseconds, measured with this process's
     * monotonic clock. Zero means unavailable (including response-only heartbeat mode). Never compare it with a remote timestamp.
     */
    public volatile long rttNanos;

    /**
     * Reads every counter once. The returned record is immutable, but the reads are not atomic
     * with respect to each other; see the class documentation before drawing conclusions.
     */
    public Snapshot snapshot() {
        return new Snapshot(
            connections.sum(),
            reconnects.sum(),
            rejected.sum(),
            timeouts.sum(),
            backpressure.sum(),
            framesIn.sum(),
            framesOut.sum(),
            bytesIn.sum(),
            bytesOut.sum(),
            cacheHits.sum(),
            threats.sum(),
            rttNanos
        );
    }

    /** Immutable, label-free copy of the counters at snapshot time. */
    public record Snapshot(long connections, long reconnects, long rejected, long timeouts,
            long backpressure, long framesIn, long framesOut, long bytesIn, long bytesOut,
            long cacheHits, long threats, long rttNanos) {
    }
}
