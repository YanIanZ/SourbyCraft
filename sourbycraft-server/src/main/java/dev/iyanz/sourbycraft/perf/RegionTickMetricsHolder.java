package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.RegionTickMetrics;
import ca.spottedleaf.common.time.TickTime;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Mutable telemetry owner cell shared by a region handle and all compatibility views. */
public final class RegionTickMetricsHolder {

    private static final VarHandle GENERATION_ID;
    private static final VarHandle MERGE_WAVE;

    static {
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            GENERATION_ID = lookup.findVarHandle(RegionTickMetricsHolder.class, "generationId", long.class);
            MERGE_WAVE = lookup.findVarHandle(RegionTickMetricsHolder.class, "mergeWave", int.class);
        } catch (final ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private volatile RegionTickMetrics current = new RegionTickMetrics();
    private volatile long generationId;
    private volatile int mergeWave;

    public RegionTickMetrics current() {
        return this.current;
    }

    public long generationId() {
        return (long)GENERATION_ID.getAcquire(this);
    }

    public void tickStarted(final long startNanos) {
        MERGE_WAVE.setRelease(this, 0);
        this.current.tickStarted(startNanos);
    }

    public void tickCompleted(final TickTime tick, final long targetIntervalNanos) {
        this.current.tickCompleted(tick, targetIntervalNanos);
    }

    boolean tryClaimGeneration(final long generationId) {
        return GENERATION_ID.compareAndSet(this, 0L, generationId);
    }

    boolean clearGeneration(final long generationId) {
        return GENERATION_ID.compareAndSet(this, generationId, 0L);
    }

    boolean tryBeginMergeWave() {
        return MERGE_WAVE.compareAndSet(this, 0, 1);
    }

    void replaceOwnerAfterRetirement() {
        if (this.generationId() != 0L) {
            throw new IllegalStateException("Cannot replace an active telemetry owner");
        }
        this.current = new RegionTickMetrics();
    }
}
