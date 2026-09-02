package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.RegionTickMetrics;
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

    private final AtomicLong nextWorldId = new AtomicLong();
    private final AtomicLong nextGenerationId = new AtomicLong();
    private final ConcurrentHashMap<Long, Generation> generations = new ConcurrentHashMap<>();

    RegionMetricsRegistry() {}

    public enum RetirementReason {
        INACTIVE,
        SPLIT,
        MERGE,
        DESTROYED,
        WORLD_UNLOAD
    }

    public record GenerationView(long generationId, long worldId, long regionId, boolean active,
                                 RegionTickMetrics.Snapshot snapshot) {}

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
        Objects.requireNonNull(consumer, "consumer");
        for (final Generation generation : this.generations.values()) {
            final RegionTickMetrics.Snapshot snapshot = generation.metrics.snapshot(nowNanos);
            final boolean active = generation.isActive();
            if (!active && expired(nowNanos, generation.retiredAtNanos, snapshot.sampledAtNanos())) {
                this.generations.remove(generation.generationId, generation);
                continue;
            }
            consumer.accept(new GenerationView(generation.generationId, generation.worldId,
                generation.regionId, active, snapshot));
        }
    }

    private static boolean expired(final long nowNanos, final long retiredAtNanos,
                                   final long sampledAtNanos) {
        if (retiredAtNanos == Long.MIN_VALUE) {
            return false;
        }
        final long anchor = Math.max(retiredAtNanos, sampledAtNanos);
        final long retention = MAXIMUM_WINDOW_NANOS + EXPIRY_GRACE_NANOS;
        final long expiresAt = anchor > Long.MAX_VALUE - retention ? Long.MAX_VALUE : anchor + retention;
        return nowNanos > expiresAt;
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
            this.retirementReason = reason;
            this.retiredAtNanos = nowNanos;
            STATE.setRelease(this, RETIRED);
        }
    }
}
