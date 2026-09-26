package dev.iyanz.sourbycraft.startup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hit, miss and discard counts plus phase times for one startup-cache build.
 *
 * <p>A cold start (no cache, all misses) and a warm start (all hits) are different benchmark
 * classes and are reported as such by {@link Summary#startClass()}; comparing a warm time with a
 * cold one is not a speed-up claim.</p>
 */
public final class StartupTelemetry {

    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();
    private final AtomicInteger discarded = new AtomicInteger();
    private final Map<String, Long> phases = new LinkedHashMap<>();

    void hit() { this.hits.incrementAndGet(); }
    void miss() { this.misses.incrementAndGet(); }
    void discarded(final int count) { this.discarded.addAndGet(count); }
    int misses() { return this.misses.get(); }

    /** Phases are recorded by the building thread only. */
    void phase(final String name, final long nanos) {
        this.phases.merge(name, nanos, Long::sum);
    }

    Summary summary(final long totalNanos) {
        return new Summary(this.hits.get(), this.misses.get(), this.discarded.get(), totalNanos,
            new LinkedHashMap<>(this.phases));
    }

    /**
     * @param hits entries reused because the source SHA-256 matched
     * @param misses sources analysed from scratch
     * @param discarded cached entries rejected as corrupt or stale
     * @param totalNanos wall time of the whole build
     * @param phaseNanos wall time per phase, in execution order
     */
    public record Summary(int hits, int misses, int discarded, long totalNanos, Map<String, Long> phaseNanos) {
        public Summary {
            phaseNanos = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(phaseNanos));
        }

        /** {@code cold}, {@code warm} or {@code mixed}; an empty build is {@code empty}. */
        public String startClass() {
            if (this.hits == 0 && this.misses == 0) return "empty";
            if (this.hits == 0) return "cold";
            if (this.misses == 0) return "warm";
            return "mixed";
        }

        public double totalMillis() {
            return this.totalNanos / 1_000_000.0;
        }
    }
}
