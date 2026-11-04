package dev.iyanz.sourbycraft.perf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded history of performance incidents: events, not samples (PRD §39).
 *
 * <p>A detector reports, once per observation pass, every condition that is currently over its
 * threshold (a {@link Breach}). Consecutive breaches of the same kind and subject are one incident:
 * it opens at the first breach, its end and peak advance with each later one, and it closes once
 * no breach of that key has been seen for {@link #MERGE_GAP_MILLIS}. A breach inside that gap
 * extends the same incident, so a condition that flickers across a pass or two is still one
 * incident with one duration.</p>
 *
 * <p>Closed incidents go into a ring of {@link #CAPACITY} immutable records; the oldest is
 * overwritten. Open incidents are kept apart (at most {@link #MAX_OPEN}) and shown as ongoing.
 * All state is guarded by this object's monitor, held only for the few field updates of one pass
 * or one copy for a reader. The writer is the metrics collector thread, once a second, never a
 * tick thread.</p>
 */
public final class IncidentLog {

    /** Closed incidents kept. PRD §39 asks only for "bounded"; 20 matches the documented candidate. */
    public static final int CAPACITY = 20;

    /** Open incidents tracked at once; a breach of a new key beyond this is counted, not tracked. */
    public static final int MAX_OPEN = 64;

    /** A key not breached for longer than this closes; a breach within it extends the incident. */
    public static final long MERGE_GAP_MILLIS = 5_000L;

    /** What kind of event an incident is. Every kind is "higher is worse". */
    public enum Kind {
        REGION_OVER_BUDGET("region over tick budget", "ms"),
        GLOBAL_TICK_SLOW("global tick slow", "ms"),
        GC_TIME("GC time per second", "ms"),
        HEAP_PRESSURE("heap pressure", "%"),
        LANE_REJECTED("governed lane rejected work", "rejected"),
        ASYNC_PATH_REFUSED("async path pool refused or declined work", "refused/declined"),
        STORAGE_BACKLOG("storage I/O backlog", "tasks"),
        AWF_COMMIT_FAILURE("AWF commit/read failure", "failures"),
        BRIDGE_FATAL_VIOLATION("bridged plugin ownership violation", "violations");

        private final String display;
        private final String unit;

        Kind(final String display, final String unit) {
            this.display = display;
            this.unit = unit;
        }

        public String display() {
            return this.display;
        }

        public String unit() {
            return this.unit;
        }
    }

    /** One condition over its threshold in one observation pass. */
    public record Breach(Kind kind, String subject, double value, double threshold) {
        public Breach {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subject, "subject");
        }
    }

    /**
     * One incident.
     *
     * @param startEpochMillis first breach
     * @param endEpochMillis   last breach seen (equal to the start when seen once)
     * @param peak             largest value seen
     * @param threshold        the threshold at the last breach
     * @param observations     passes in which the condition was over its threshold
     * @param ongoing          not yet closed: breached within {@link #MERGE_GAP_MILLIS} of the last pass
     */
    public record Incident(long startEpochMillis, long endEpochMillis, Kind kind, String subject,
                           double peak, double threshold, int observations, boolean ongoing) {
        public long durationMillis() {
            return this.endEpochMillis - this.startEpochMillis;
        }
    }

    /** What a reader sees: newest first, plus how much was not kept. */
    public record View(List<Incident> incidents, long closedTotal, long overwritten, long untrackedBreaches) {}

    private record Key(Kind kind, String subject) {}

    private static final class Open {
        final long start;
        long lastSeen;
        double peak;
        double threshold;
        int observations;

        Open(final long start, final double value, final double threshold) {
            this.start = start;
            this.lastSeen = start;
            this.peak = value;
            this.threshold = threshold;
            this.observations = 1;
        }

        Incident freeze(final Key key, final boolean ongoing) {
            return new Incident(this.start, this.lastSeen, key.kind(), key.subject(), this.peak,
                this.threshold, this.observations, ongoing);
        }
    }

    private final Incident[] ring = new Incident[CAPACITY];
    private int next;
    private long closedTotal;
    private long untrackedBreaches;
    private final Map<Key, Open> open = new LinkedHashMap<>();

    /** The process's log, fed by {@link IncidentDetector#GLOBAL}. */
    public static final IncidentLog GLOBAL = new IncidentLog();

    /** Applies one observation pass taken at {@code nowEpochMillis}. */
    public synchronized void pass(final long nowEpochMillis, final List<Breach> breaches) {
        // Close first: a breach arriving after a longer gap than MERGE_GAP_MILLIS (for example
        // after the collector was stalled) starts a new incident instead of stretching the old one.
        this.open.entrySet().removeIf(entry -> {
            if (nowEpochMillis - entry.getValue().lastSeen <= MERGE_GAP_MILLIS) {
                return false;
            }
            this.close(entry.getValue().freeze(entry.getKey(), false));
            return true;
        });
        for (final Breach breach : breaches) {
            final Key key = new Key(breach.kind(), breach.subject());
            final Open incident = this.open.get(key);
            if (incident == null) {
                if (this.open.size() >= MAX_OPEN) {
                    this.untrackedBreaches++;
                    continue;
                }
                this.open.put(key, new Open(nowEpochMillis, breach.value(), breach.threshold()));
                continue;
            }
            incident.lastSeen = nowEpochMillis;
            incident.peak = Math.max(incident.peak, breach.value());
            incident.threshold = breach.threshold();
            incident.observations++;
        }
    }

    private void close(final Incident incident) {
        this.ring[this.next] = incident;
        this.next = (this.next + 1) % CAPACITY;
        this.closedTotal++;
    }

    /** A copy for rendering: ongoing and closed incidents, newest start first. */
    public synchronized View view() {
        final List<Incident> out = new ArrayList<>(CAPACITY + this.open.size());
        this.open.forEach((key, incident) -> out.add(incident.freeze(key, true)));
        for (final Incident incident : this.ring) {
            if (incident != null) {
                out.add(incident);
            }
        }
        out.sort(Comparator.comparingLong(Incident::startEpochMillis).reversed()
            .thenComparing(Comparator.comparingLong(Incident::endEpochMillis).reversed()));
        return new View(List.copyOf(out), this.closedTotal, Math.max(0L, this.closedTotal - CAPACITY),
            this.untrackedBreaches);
    }
}
