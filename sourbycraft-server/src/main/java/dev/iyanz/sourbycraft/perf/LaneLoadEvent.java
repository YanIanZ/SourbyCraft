package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.execution.LaneCpuSampler;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * One execution lane's share of the machine, once per collection.
 *
 * <p>A separate event per lane rather than one wide event: lanes come and go with the pools that
 * back them, and a flat event would have to fix the set in advance.</p>
 */
@Name("dev.iyanz.sourbycraft.LaneLoad")
@Label("SourbyCraft Execution Lane Load")
@Category({"SourbyCraft", "Telemetry"})
@StackTrace(false)
final class LaneLoadEvent extends Event {
    private static final EventType TYPE = EventType.getEventType(LaneLoadEvent.class);

    @Label("Lane") public String lane;
    @Label("Threads") public int threads;
    @Label("Cores") public double cores;

    static void record(final LaneCpuSampler.LaneLoads loads) {
        if (!TYPE.isEnabled() || !loads.available()) {
            return;
        }
        for (final LaneCpuSampler.LaneLoad load : loads.lanes()) {
            final LaneLoadEvent event = new LaneLoadEvent();
            event.lane = load.lane().name();
            event.threads = load.threads();
            event.cores = load.cores();
            event.commit();
        }
    }
}
