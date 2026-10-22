package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Mutable telemetry owner cell shared by a region handle and all compatibility views. */
public final class RegionTickMetricsHolder {

    // Region tick metrics lifecycle seam (minecraft feature patch 0079): one call per region/world
    // lifecycle callback. The callers pass System.nanoTime(); no new scheduler ownership.
    public static long newWorldId() {
        return RegionMetricsRegistry.INSTANCE.newWorldId();
    }

    public static void retireWorld(final long worldId, final long nowNanos) {
        RegionMetricsRegistry.INSTANCE.retireWorld(worldId, nowNanos);
    }

    /** {@code TickRegions.onRegionActive}: this handle's region starts a telemetry generation. */
    public void activate(final long worldId, final long regionId, final long nowNanos) {
        RegionMetricsRegistry.INSTANCE.activate(worldId, regionId, this, nowNanos);
    }

    /** {@code TickRegionData.mergeInto}: the merge target retires its generation and starts a fresh one. */
    public void rotateForMerge(final long worldId, final long regionId, final long nowNanos) {
        RegionMetricsRegistry.INSTANCE.rotateForMerge(this.generationId(), worldId, regionId, this, nowNanos);
    }

    /** {@code TickRegions.onRegionDestroy}. */
    public void retireDestroyed(final long nowNanos) {
        RegionMetricsRegistry.INSTANCE.retire(this, RegionMetricsRegistry.RetirementReason.DESTROYED, nowNanos);
    }

    /** {@code TickRegions.onRegionInactive}, after the handle is descheduled. */
    public void retireInactive(final long nowNanos) {
        RegionMetricsRegistry.INSTANCE.retire(this, RegionMetricsRegistry.RetirementReason.INACTIVE, nowNanos);
    }

    /** {@code TickRegions.preSplit}. */
    public void retireSplit(final long nowNanos) {
        RegionMetricsRegistry.INSTANCE.retire(this, RegionMetricsRegistry.RetirementReason.SPLIT, nowNanos);
    }

    /** {@code TickRegions.preMerge}, for the region merged away. */
    public void retireMerged(final long nowNanos) {
        RegionMetricsRegistry.INSTANCE.retire(this, RegionMetricsRegistry.RetirementReason.MERGE, nowNanos);
    }

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

    /** A {@code TickData} view of this holder's current owner for one window (patch 0013's handle fields). */
    public TickData view(final long intervalNS) {
        return RegionTickDataView.of(this, intervalNS);
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
