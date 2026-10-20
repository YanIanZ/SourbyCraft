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
 * refusal on that same object.</p>
 *
 * <p>How the type reaches the processor:</p>
 * <ul>
 *   <li><b>Explicit</b> (preferred): {@code AsyncPathProcessor.trySubmit(MobPathSnapshot, Supplier)}
 *   reads {@link MobPathSnapshot#entityTypeKey()} from the snapshot it is given. No ambient state.
 *   It needs the navigation patch to pass the snapshot it already holds.</li>
 *   <li><b>Fallback for the unchanged patch</b>: the patch calls
 *   {@code MobPathSnapshot.captureDetached(mob)} and then {@code trySubmit(Supplier)} in a straight
 *   line on one (owning region) thread. {@code captureDetached} clears this thread's noted subject
 *   first and notes the new type only after the capture succeeded, so a throwing capture leaves
 *   nothing; {@code trySubmit} consumes it on entry. A capture that is never followed by a submit
 *   leaves its type noted until the next capture or submit on that thread; with the single patch call
 *   site (always capture-then-submit) that cannot misattribute, and
 *   {@code AsyncPathPatchShapeTest} pins the call order. A submit with no noted subject is
 *   counted under {@link #UNKNOWN}.</li>
 * </ul>
 */
public final class AsyncPathTypeStats {

    /** Distinct entity type keys tracked; further types share {@link #OTHER}. */
    public static final int MAX_TYPES = 64;
    public static final String OTHER = "other";
    public static final String UNKNOWN = "unknown";

    /** One row of the report. */
    public record TypeCount(String type, long admitted, long solved, long refused) {
        long demand() { return this.admitted + this.refused; }
    }

    /** The counters one request charges; resolved once per request by {@link #slotFor}. */
    static final class Slot {
        private final LongAdder admitted = new LongAdder();
        private final LongAdder solved = new LongAdder();
        private final LongAdder refused = new LongAdder();

        void admitted() { this.admitted.increment(); }
        /** Undoes {@link #admitted()} when the executor rejected the task after all. */
        void admissionRolledBack() { this.admitted.decrement(); }
        void solved() { this.solved.increment(); }
        void refused() { this.refused.increment(); }

        private void reset() {
            this.admitted.reset();
            this.solved.reset();
            this.refused.reset();
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
     * The busiest types, by admitted + refused descending (then name), at most {@code limit} rows;
     * the {@link #OTHER} bucket is appended last when it holds anything and is not counted in
     * {@code limit}.
     */
    public static List<TypeCount> top(final int limit) {
        final List<TypeCount> rows = new ArrayList<>(TYPES.size());
        TYPES.forEach((type, c) -> rows.add(
            new TypeCount(type, c.admitted.sum(), c.solved.sum(), c.refused.sum())));
        rows.sort(Comparator.comparingLong(TypeCount::demand).reversed()
            .thenComparing(TypeCount::type));
        final List<TypeCount> out = new ArrayList<>(rows.subList(0, Math.min(Math.max(limit, 0), rows.size())));
        final TypeCount other = new TypeCount(OTHER, OTHER_SLOT.admitted.sum(),
            OTHER_SLOT.solved.sum(), OTHER_SLOT.refused.sum());
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
