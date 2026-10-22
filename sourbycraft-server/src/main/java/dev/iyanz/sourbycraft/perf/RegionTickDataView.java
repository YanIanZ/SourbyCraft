package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.common.time.TickTime;
import java.util.Objects;

/**
 * {@link TickData} view of one region's shared tick telemetry (Minecraft feature patch 0013).
 *
 * <p>The region schedule handle used to keep five independent {@code TickData} collectors (5s, 15s,
 * 1m, 5m, 15m), each with its own deque of every tick in its window. The handle now records each tick
 * once into its {@link RegionTickMetricsHolder}; its five {@code tickTimesN} fields keep the upstream
 * {@code TickData} type (field descriptors unchanged for plugins and spark) but hold these views, which
 * answer the {@code TickData} queries for their window from the shared owner. Standalone
 * {@code new TickData(interval)} collectors (global tick, plugins) are unaffected: they use the upstream
 * implementation unchanged.
 *
 * <p>A view over a holder resolves {@link RegionTickMetricsHolder#current()} on every call, so it
 * follows owner rotation on merge. The superclass constructor allocates an empty deque that a view
 * never uses (a small one-off allocation per handle, not on the tick path).
 */
public final class RegionTickDataView extends TickData {

    private final RegionTickMetrics owner;
    private final RegionTickMetricsHolder holder;
    private final long interval;

    private RegionTickDataView(final RegionTickMetrics owner, final RegionTickMetricsHolder holder,
                               final long intervalNS) {
        super(intervalNS);
        this.owner = owner;
        this.holder = holder;
        this.interval = intervalNS;
    }

    /** View of one fixed owner (tests, diagnostics). */
    public static TickData of(final RegionTickMetrics owner, final long intervalNS) {
        return new RegionTickDataView(Objects.requireNonNull(owner), null, intervalNS);
    }

    /** View that follows the holder's current owner (what the region schedule handle uses). */
    public static TickData of(final RegionTickMetricsHolder holder, final long intervalNS) {
        return new RegionTickDataView(null, Objects.requireNonNull(holder), intervalNS);
    }

    private RegionTickMetrics shared() {
        return this.holder == null ? this.owner : this.holder.current();
    }

    /** A sample without a target interval (callers outside the region scheduler's own completion path). */
    @Override
    public void addDataFrom(final TickTime time) {
        this.shared().tickCompletedWithoutTarget(time);
    }

    @Override
    public Double getTPSAverage(final TickTime inProgress, final long tickInterval) {
        return this.shared().tps(this.interval, inProgress, tickInterval);
    }

    @Override
    public MSPTData getMSPTData(final TickTime inProgress, final long tickInterval) {
        return this.shared().mspt(this.interval, inProgress, tickInterval);
    }

    @Override
    public TickReportData generateTickReport(final TickTime inProgress, final long endTime, final long tickInterval) {
        return this.shared().report(this.interval, inProgress, endTime, tickInterval);
    }
}
