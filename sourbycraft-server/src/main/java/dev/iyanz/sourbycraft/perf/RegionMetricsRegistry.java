package dev.iyanz.sourbycraft.perf;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Process-wide ownership and retention for region telemetry generations. */
public final class RegionMetricsRegistry {

    public static final RegionMetricsRegistry INSTANCE = new RegionMetricsRegistry();

    private static final long MAXIMUM_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(15L);
    private static final long EXPIRY_GRACE_NANOS = TimeUnit.SECONDS.toNanos(1L);
    /** How many generations one activation examines before deferring the rest (audit OBS-6). */
    private static final int SWEEP_BATCH = 64;

    private final AtomicLong nextWorldId = new AtomicLong();
    private final AtomicLong nextGenerationId = new AtomicLong();
    private final ConcurrentHashMap<Long, Generation> generations = new ConcurrentHashMap<>();
    /** The highest generation id a sweep has already passed; wraps so nothing is skipped. */
    private final AtomicLong sweepCursor = new AtomicLong();

    RegionMetricsRegistry() {}

    public enum RetirementReason {
        INACTIVE,
        SPLIT,
        MERGE,
        DESTROYED,
        WORLD_UNLOAD
    }

    public record GenerationView(long generationId, long worldId, long regionId, boolean active,
                                  RegionTickMetrics metrics, RegionTickMetrics.Snapshot snapshot) {}

    /** A live region generation for the stall recorder: identity and its lock-free tick markers. */
    public record ActiveGeneration(long worldId, long regionId, RegionTickMetrics metrics) {}

    /**
     * Walks the live (not retired) generations without touching any monitor: a concurrent map
     * iteration plus one acquire read per generation. Meant for a watchdog that must keep working
     * while a region is stuck; it never refreshes, publishes or removes anything.
     */
    public void forEachActive(final Consumer<ActiveGeneration> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (final Generation generation : this.generations.values()) {
            if (generation.isActive()) {
                consumer.accept(new ActiveGeneration(generation.worldId, generation.regionId, generation.metrics));
            }
        }
    }

    public long newWorldId() {
        return nextNonZero(this.nextWorldId);
    }

    public long activate(final long worldId, final long regionId, final RegionTickMetricsHolder holder,
                         final long nowNanos) {
        Objects.requireNonNull(holder, "holder");
        final long existing = holder.generationId();
        if (existing != 0L) {
            return existing;
        }

        // Amortised sweep: the collector normally reaps expired generations, but if it never
        // started the map would otherwise grow without bound as regions activate and retire.
        // Activation is the one place generations are added, so it reaps a bounded batch here.
        this.sweepExpired(nowNanos);

        final long generationId = nextNonZero(this.nextGenerationId);
        final Generation generation = new Generation(generationId, worldId, regionId, holder.current());
        this.generations.put(generationId, generation);
        if (holder.tryClaimGeneration(generationId)) {
            return generationId;
        }

        this.generations.remove(generationId, generation);
        return holder.generationId();
    }

    public void retire(final long generationId, final RetirementReason reason, final long nowNanos) {
        Objects.requireNonNull(reason, "reason");
        final Generation generation = this.generations.get(generationId);
        if (generation != null) {
            generation.retire(reason, nowNanos);
        }
    }

    public void retire(final RegionTickMetricsHolder holder, final RetirementReason reason,
                       final long nowNanos) {
        Objects.requireNonNull(holder, "holder");
        final long generationId = holder.generationId();
        this.retire(generationId, reason, nowNanos);
        holder.clearGeneration(generationId);
    }

    public long rotateForMerge(final long generationId, final long worldId, final long regionId,
                               final RegionTickMetricsHolder holder, final long nowNanos) {
        Objects.requireNonNull(holder, "holder");
        if (!holder.tryBeginMergeWave()) {
            return holder.generationId();
        }

        final long ownedGeneration = holder.generationId();
        if (ownedGeneration != 0L && ownedGeneration != generationId) {
            throw new IllegalStateException("Telemetry holder does not own generation " + generationId);
        }
        this.retire(generationId, RetirementReason.MERGE, nowNanos);
        holder.clearGeneration(generationId);
        holder.replaceOwnerAfterRetirement();
        return generationId == 0L ? 0L : this.activate(worldId, regionId, holder, nowNanos);
    }

    public void retireWorld(final long worldId, final long nowNanos) {
        for (final Generation generation : this.generations.values()) {
            if (generation.worldId == worldId) {
                generation.retire(RetirementReason.WORLD_UNLOAD, nowNanos);
            }
        }
    }

    public void forEachUnexpired(final long nowNanos, final Consumer<GenerationView> consumer) {
        this.forEachUnexpired(nowNanos, consumer, true);
    }

    void forEachUnexpiredForCollection(final long nowNanos, final Consumer<GenerationView> consumer) {
        this.forEachUnexpired(nowNanos, consumer, false);
    }

    /**
     * Read-only lookup for diagnostics ({@code /perf region}): the unexpired generations of one
     * (world, region), each with the snapshot its metrics last published (lock-free, normally the
     * collector's once-a-second publication). Unlike {@link #forEachUnexpired}, it never calls the
     * synchronized {@code refreshSnapshot()} on any generation and never removes an expired one:
     * filtering happens first, and nothing is written.
     */
    public void forEachUnexpiredOf(final long worldId, final long regionId, final long nowNanos,
                                   final Consumer<GenerationView> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (final Generation generation : this.generations.values()) {
            if (generation.worldId != worldId || generation.regionId != regionId) {
                continue;
            }
            final boolean active = generation.isActive();
            if (!active && expired(nowNanos, generation.retiredAtNanos)) {
                continue;
            }
            consumer.accept(new GenerationView(generation.generationId, generation.worldId,
                generation.regionId, active, generation.metrics, generation.metrics.snapshot(nowNanos)));
        }
    }

    private void forEachUnexpired(final long nowNanos, final Consumer<GenerationView> consumer,
                                  final boolean refresh) {
        Objects.requireNonNull(consumer, "consumer");
        for (final Generation generation : this.generations.values()) {
            final RegionTickMetrics.Snapshot snapshot = refresh
                ? generation.metrics.refreshSnapshot(nowNanos) : null;
            final boolean active = generation.isActive();
            if (!active && expired(nowNanos, generation.retiredAtNanos)) {
                this.generations.remove(generation.generationId, generation);
                continue;
            }
            consumer.accept(new GenerationView(generation.generationId, generation.worldId,
                generation.regionId, active, generation.metrics, snapshot));
        }
    }

    private static boolean expired(final long nowNanos, final long retiredAtNanos) {
        if (retiredAtNanos == Long.MIN_VALUE) {
            return false;
        }
        final long retention = MAXIMUM_WINDOW_NANOS + EXPIRY_GRACE_NANOS;
        final long expiresAt = retiredAtNanos > Long.MAX_VALUE - retention
            ? Long.MAX_VALUE : retiredAtNanos + retention;
        return nowNanos > expiresAt;
    }

    /**
     * Removes a bounded batch of expired generations, spread across activations. A monotonic
     * cursor spreads the scan over the whole map rather than re-examining the first few entries
     * every time; it wraps once every generation has been passed, so a generation that was still
     * live when first passed is re-examined after it retires.
     */
    private void sweepExpired(final long nowNanos) {
        final long cursor = this.sweepCursor.get();
        long highest = cursor;
        int examined = 0;
        for (final Generation generation : this.generations.values()) {
            if (generation.generationId <= cursor) {
                continue;
            }
            if (++examined > SWEEP_BATCH) {
                break;
            }
            highest = Math.max(highest, generation.generationId);
            if (!generation.isActive() && expired(nowNanos, generation.retiredAtNanos)) {
                this.generations.remove(generation.generationId, generation);
            }
        }
        this.sweepCursor.compareAndSet(cursor, highest == cursor ? 0L : highest);
    }

    private static long nextNonZero(final AtomicLong sequence) {
        while (true) {
            final long current = sequence.get();
            if (current < 0L || current == Long.MAX_VALUE) {
                throw new IllegalStateException("Telemetry ID sequence exhausted");
            }
            final long next = current + 1L;
            if (sequence.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    private static final class Generation {

        private static final int RETIRED = 0;
        private static final int LIVE = 1;
        private static final int RETIRING = 2;
        private static final VarHandle STATE;

        static {
            try {
                STATE = MethodHandles.lookup().findVarHandle(Generation.class, "state", int.class);
            } catch (final ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        private final long generationId;
        private final long worldId;
        private final long regionId;
        private final RegionTickMetrics metrics;
        private volatile int state = LIVE;
        private volatile long retiredAtNanos = Long.MIN_VALUE;
        private volatile RetirementReason retirementReason;

        private Generation(final long generationId, final long worldId, final long regionId,
                           final RegionTickMetrics metrics) {
            this.generationId = generationId;
            this.worldId = worldId;
            this.regionId = regionId;
            this.metrics = metrics;
        }

        private boolean isActive() {
            return (int)STATE.getAcquire(this) == LIVE;
        }

        private void retire(final RetirementReason reason, final long nowNanos) {
            if (!STATE.compareAndSet(this, LIVE, RETIRING)) {
                return;
            }
            this.metrics.seal(nowNanos);
            this.retirementReason = reason;
            this.retiredAtNanos = nowNanos;
            STATE.setRelease(this, RETIRED);
        }
    }
}
