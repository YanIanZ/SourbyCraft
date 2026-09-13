package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.brand.BuildInfo;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** One event per published snapshot only while a recording enables this event. */
@Name("dev.iyanz.sourbycraft.PerformanceSnapshot")
@Label("SourbyCraft Performance Snapshot")
@Category({"SourbyCraft", "Telemetry"})
@StackTrace(false)
final class TelemetryEvent extends Event {
    private static final EventType TYPE = EventType.getEventType(TelemetryEvent.class);
    public String build;
    public String state;
    public long sequence;
    public int activeRegions;
    public double targetTps;
    public double worstTps;
    @Label("Worst average MSPT") public double worstAverageMspt;
    public double estimatedP95Mspt;
    public double estimatedP99Mspt;
    public double processCpuPercent;
    public long heapUsedBytes;

    static void record(final PerformanceSnapshot snapshot) {
        if (!TYPE.isEnabled()) return;
        final var event = new TelemetryEvent();
        final var recent = snapshot.window(MetricWindow.FIVE_SECONDS);
        event.build = BuildInfo.load().build();
        event.state = snapshot.freshness().state().name();
        event.sequence = snapshot.sequence();
        event.activeRegions = snapshot.activeRegionCount();
        event.targetTps = snapshot.targetTps();
        event.worstTps = recent.worstTps();
        event.worstAverageMspt = recent.worstAverageMspt();
        event.estimatedP95Mspt = recent.estimatedP95Mspt();
        event.estimatedP99Mspt = recent.estimatedP99Mspt();
        event.processCpuPercent = snapshot.runtime().processCpuPercent();
        event.heapUsedBytes = snapshot.runtime().heapUsedBytes();
        event.commit();
    }
}
