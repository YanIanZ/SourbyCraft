package dev.iyanz.sourbycraft.perf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * SourbyCraft — async pathfinding counters per entity type ({@code /perf async}).
 *
 * <p>The global counters in {@link AsyncPathProcessor} say how busy the pool is; these say which
 * mobs are asking. The key is the entity type registry key as a string ({@code minecraft:zombie}).
 * The set of keys is bounded at {@link #MAX_TYPES}: a type first seen after that is counted in the
 * shared {@link #OTHER} bucket, so a plugin that registers or spoofs many types cannot grow this
 * without limit. First-use admission of a key is serialized, so the cap is exact under
 * concurrency, and a request resolves its {@link Slot} once and counts admission, completion and
 * refusal on that same object. An offload declined on capacity before any submission charges its
 * type's slot once, in {@code AsyncPathProcessor.noteDeclined}.</p>
 *
 * <p>How the type reaches the processor:</p>
 * <ul>
 *   <li><b>Explicit</b>: {@code AsyncPathProcessor.trySubmit(MobPathSnapshot, Supplier)} reads
 *   {@link MobPathSnapshot#entityTypeKey()} from the snapshot it is given and clears this thread's
 *   noted subject. No ambient state. {@code AsyncPathRecompute.offload} submits this way, and names
 *   the type itself when it declines on capacity.</li>
 *   <li><b>Fallback for {@code trySubmit(Supplier)}</b>, which carries no snapshot:
 *   {@code MobPathSnapshot.captureDetached(mob)} clears this thread's noted subject first and notes
 *   the new type only after the capture succeeded, so a throwing capture leaves nothing;
 *   {@code trySubmit(Supplier)} consumes it on entry. A capture that is never followed by a submit
 *   leaves its type noted until the next capture or submit on that thread. A submit with no noted
 *   subject is counted under {@link #UNKNOWN}. No server code path submits this way.</li>
 * </ul>
 */
public final class AsyncPathTypeStats {

    /** Distinct entity type keys tracked; further types share {@link #OTHER}. */
    public static final int MAX_TYPES = 64;
    public static final String OTHER = "other";
    public static final String UNKNOWN = "unknown";

    /** One row of the report. */
    public record TypeCount(String type, long admitted, long solved, long refused, long declined) {
        long demand() { return this.admitted + this.refused + this.declined; }
    }

    /** The counters one request charges; resolved once per request by {@link #slotFor}. */
    static final class Slot {
        private final LongAdder admitted = new LongAdder();
        private final LongAdder solved = new LongAdder();
        private final LongAdder refused = new LongAdder();
        private final LongAdder declined = new LongAdder();

        void admitted() { this.admitted.increment(); }
        /** Undoes {@link #admitted()} when the executor rejected the task after all. */
        void admissionRolledBack() { this.admitted.decrement(); }
        void solved() { this.solved.increment(); }
        void refused() { this.refused.increment(); }
        /** An offload declined on capacity before it was submitted. */
        void declined() { this.declined.increment(); }

        private void reset() {
            this.admitted.reset();
            this.solved.reset();
            this.refused.reset();
            this.declined.reset();
        }
    }

    private static final ThreadLocal<String> SUBJECT = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Slot> TYPES = new ConcurrentHashMap<>();
    private static final Slot OTHER_SLOT = new Slot();
    private static final Object ADMISSION_LOCK = new Object();

    private AsyncPathTypeStats() {}

    /** Records the type of the mob just captured on this thread; consumed by the next submit. */
    public static void noteSubject(final String typeKey) {
        SUBJECT.set(typeKey);
    }

    /** Forgets this thread's noted subject. */
    static void clearSubject() {
        SUBJECT.remove();
    }

    /** The type noted on this thread (and clears it), or {@link #UNKNOWN}. */
    static String takeSubject() {
        final String key = SUBJECT.get();
        if (key == null) return UNKNOWN;
        SUBJECT.remove();
        return key;
    }

    /** The bucket for {@code type}: its own while under the cap, else the shared one. Stable. */
    static Slot slotFor(final String type) {
        final Slot existing = TYPES.get(type);
        if (existing != null) return existing;
        synchronized (ADMISSION_LOCK) {
            final Slot raced = TYPES.get(type);
            if (raced != null) return raced;
            if (TYPES.size() >= MAX_TYPES) return OTHER_SLOT;
            final Slot fresh = new Slot();
            TYPES.put(type, fresh);
            return fresh;
        }
    }

    /**
     * The busiest types, by admitted + refused + declined descending (then name), at most
     * {@code limit} rows; the {@link #OTHER} bucket is appended last when it holds anything and is
     * not counted in {@code limit}.
     */
    public static List<TypeCount> top(final int limit) {
        final List<TypeCount> rows = new ArrayList<>(TYPES.size());
        TYPES.forEach((type, c) -> rows.add(
            new TypeCount(type, c.admitted.sum(), c.solved.sum(), c.refused.sum(), c.declined.sum())));
        rows.sort(Comparator.comparingLong(TypeCount::demand).reversed()
            .thenComparing(TypeCount::type));
        final List<TypeCount> out = new ArrayList<>(rows.subList(0, Math.min(Math.max(limit, 0), rows.size())));
        final TypeCount other = new TypeCount(OTHER, OTHER_SLOT.admitted.sum(),
            OTHER_SLOT.solved.sum(), OTHER_SLOT.refused.sum(), OTHER_SLOT.declined.sum());
        if (other.demand() > 0 || other.solved() > 0) out.add(other);
        return out;
    }

    /** Distinct tracked types (excluding the shared bucket). */
    public static int trackedTypes() {
        return TYPES.size();
    }

    /** Test seam: forgets every counter and this thread's noted subject. */
    static void resetForTest() {
        synchronized (ADMISSION_LOCK) {
            TYPES.clear();
        }
        SUBJECT.remove();
        OTHER_SLOT.reset();
    }
}
