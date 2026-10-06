package dev.iyanz.sourbycraft.bridge;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-plugin Aurora Bridge counters, as listed under Telemetry in
 * {@code aurora-plugin-bridge.md}: scheduler redirects, owner handoffs, rejected operations,
 * fatal violations, quarantine, startup duration and cache state, the last failure, and the wall
 * time of bridged task bodies per lane.
 *
 * <p>Counters are monotonic and lock-free; {@link #snapshot()} copies them into immutable records
 * for rendering. Entries exist only for plugins the bridge admitted, so the map is bounded by the
 * plugin count.</p>
 */
public final class BridgeTelemetry {

    /** One plugin's counters at a point in time. */
    public record PluginStats(String plugin, long schedulerRedirects, long ownerHandoffs,
                              long rejectedOperations, long fatalViolations, boolean quarantined,
                              String lastFailure, long enableMillis, String startupCacheState,
                              BodyTimes bodyTimes) {}

    /** Recent-window size for body-time percentiles, per plugin and lane. */
    static final int BODY_WINDOW = 256;

    /** The thread a bridged task body ran on: its owning region, the global region, or the async scheduler. */
    public enum BodyLane {
        REGION, GLOBAL, ASYNC;

        static BodyLane of(final BridgeRouter.Route route) {
            return switch (route) {
                case REGION_OWNER, ENTITY_OWNER -> REGION;
                case IO_LANE -> ASYNC;
                default -> GLOBAL;
            };
        }
    }

    /**
     * Wall time of one lane's task bodies, measured on the thread that ran them; queue wait is not
     * included. Count, total and max cover the whole uptime; p50/p99 cover the last {@code samples}
     * bodies (at most {@link #BODY_WINDOW}). All zero when no body ran.
     */
    public record BodyTime(long count, long totalNanos, long maxNanos, int samples, long p50Nanos, long p99Nanos) {
        public double totalMillis() { return this.totalNanos / 1_000_000.0; }
        public double meanMillis() { return this.count == 0 ? 0.0 : this.totalNanos / (double)this.count / 1_000_000.0; }
        public double maxMillis() { return this.maxNanos / 1_000_000.0; }
        public double p50Millis() { return this.p50Nanos / 1_000_000.0; }
        public double p99Millis() { return this.p99Nanos / 1_000_000.0; }
    }

    /** Body times for the three lanes a bridged task can run on. */
    public record BodyTimes(BodyTime region, BodyTime global, BodyTime async) {}

    /** Count/total/max are lock-free; the recent window is a small synchronized ring. */
    private static final class BodyTimer {
        final LongAdder count = new LongAdder();
        final LongAdder total = new LongAdder();
        final AtomicLong max = new AtomicLong();
        private final long[] recent = new long[BODY_WINDOW];
        private long recorded;

        void record(final long nanos) {
            this.count.increment();
            this.total.add(nanos);
            this.max.accumulateAndGet(nanos, Math::max);
            synchronized (this) {
                this.recent[(int)(this.recorded % BODY_WINDOW)] = nanos;
                this.recorded++;
            }
        }

        /** The ring and the uptime counters are each consistent, not one atomic snapshot. */
        BodyTime snapshot() {
            final long[] copy;
            synchronized (this) {
                copy = Arrays.copyOf(this.recent, (int)Math.min(this.recorded, BODY_WINDOW));
            }
            Arrays.sort(copy);
            return new BodyTime(this.count.sum(), this.total.sum(), this.max.get(), copy.length,
                rank(copy, 0.50), rank(copy, 0.99));
        }

        /** Nearest-rank percentile; zero for an empty window. */
        private static long rank(final long[] sorted, final double q) {
            if (sorted.length == 0) return 0L;
            final int rank = (int)Math.ceil(q * sorted.length);
            return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
        }
    }

    private static final class Counters {
        final AtomicLong redirects = new AtomicLong();
        final AtomicLong handoffs = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong fatal = new AtomicLong();
        final AtomicBoolean quarantined = new AtomicBoolean();
        final AtomicReference<String> lastFailure = new AtomicReference<>();
        final AtomicLong enableMillis = new AtomicLong(-1L);
        final AtomicReference<String> cacheState = new AtomicReference<>("unknown");
        final BodyTimer[] bodies = {new BodyTimer(), new BodyTimer(), new BodyTimer()};
    }

    private final Map<String, Counters> plugins = new ConcurrentHashMap<>();

    private Counters of(final String plugin) {
        return this.plugins.computeIfAbsent(plugin, ignored -> new Counters());
    }

    void register(final String plugin) { of(plugin); }
    void redirect(final String plugin) { of(plugin).redirects.incrementAndGet(); }
    void handoff(final String plugin) { of(plugin).handoffs.incrementAndGet(); }
    void rejected(final String plugin) { of(plugin).rejected.incrementAndGet(); }
    void failure(final String plugin, final String description) { of(plugin).lastFailure.set(description); }
    void enableMillis(final String plugin, final long millis) { of(plugin).enableMillis.set(millis); }
    void cacheState(final String plugin, final String state) { of(plugin).cacheState.set(state); }
    void bodyTime(final String plugin, final BodyLane lane, final long nanos) {
        of(plugin).bodies[lane.ordinal()].record(Math.max(0L, nanos));
    }

    /** Records a fatal violation and returns the new total. */
    long fatal(final String plugin, final String description) {
        final Counters c = of(plugin);
        c.lastFailure.set(description);
        return c.fatal.incrementAndGet();
    }

    /** Marks the plugin quarantined; returns true only for the call that changed it. */
    boolean quarantine(final String plugin) {
        return of(plugin).quarantined.compareAndSet(false, true);
    }

    boolean quarantined(final String plugin) {
        final Counters c = this.plugins.get(plugin);
        return c != null && c.quarantined.get();
    }

    boolean known(final String plugin) {
        return this.plugins.containsKey(plugin);
    }

    public PluginStats stats(final String plugin) {
        final Counters c = this.plugins.get(plugin);
        if (c == null) return null;
        return new PluginStats(plugin, c.redirects.get(), c.handoffs.get(), c.rejected.get(), c.fatal.get(),
            c.quarantined.get(), c.lastFailure.get(), c.enableMillis.get(), c.cacheState.get(),
            new BodyTimes(c.bodies[BodyLane.REGION.ordinal()].snapshot(), c.bodies[BodyLane.GLOBAL.ordinal()].snapshot(),
                c.bodies[BodyLane.ASYNC.ordinal()].snapshot()));
    }

    public List<PluginStats> snapshot() {
        return this.plugins.keySet().stream().sorted().map(this::stats).toList();
    }
}
