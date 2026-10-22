package dev.iyanz.sourbycraft.execution;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * How much of the machine each {@link ExecutionLane} actually used.
 *
 * <p>Answers the question thread counts cannot: a lane with six threads that are mostly parked
 * costs less than a lane with two that are saturated. Reported in <b>cores</b> — CPU time consumed
 * over wall time elapsed — so the numbers compare directly against {@code availableProcessors()}
 * and a total above that is oversubscription, stated rather than inferred.</p>
 *
 * <p>Not thread-safe: one sampler, sampled from one thread, on a fixed cadence.</p>
 */
public final class LaneCpuSampler {

    private static volatile boolean enabled = true;

    /**
     * Turns lane attribution on or off, live.
     *
     * <p>Sampling walks every thread once a second. That is cheap beside a loaded server and not
     * beside an idle one — on an idle server the telemetry lane costs more than the region lane —
     * so a host running many quiet worlds has a reason to switch it off.</p>
     */
    public static void setEnabled(final boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private final ThreadCpuSource source;
    private final LongSupplier nanoClock;
    private Map<Long, Long> previousCpu = Map.of();
    private long previousNanos;
    private boolean sampled;

    public LaneCpuSampler(final ThreadCpuSource source, final LongSupplier nanoClock) {
        this.source = source;
        this.nanoClock = nanoClock;
    }

    public static LaneCpuSampler platform() {
        return new LaneCpuSampler(ThreadCpuSource.platform(), System::nanoTime);
    }

    /** One lane's share of the machine over the sampled interval. */
    public record LaneLoad(ExecutionLane lane, int threads, double cores) {}

    /**
     * Lane loads since the previous call.
     *
     * @param available     whether the reading can be used at all
     * @param reason        why not, when it cannot
     * @param intervalNanos wall time the reading covers
     * @param lanes         one entry per lane that had a thread, heaviest first
     * @param totalCores    every lane summed, for comparison against the core count
     */
    public record LaneLoads(boolean available, String reason, long intervalNanos,
                            List<LaneLoad> lanes, double totalCores) {

        static LaneLoads unavailable(final String reason) {
            return new LaneLoads(false, reason, 0L, List.of(), Double.NaN);
        }
    }

    /**
     * Samples every thread and attributes its CPU since the last call to its lane.
     *
     * <p>The first call establishes a baseline and reports nothing: a cumulative counter says how
     * much CPU a thread has used since it started, which over an unknown interval means nothing.
     * A thread first seen in this sample is counted but contributes no CPU, for the same reason.</p>
     */
    public LaneLoads sample() {
        if (!enabled) {
            // Drop the baseline too: resuming later must not attribute the whole gap to whichever
            // threads happened to run during it.
            this.previousCpu = Map.of();
            this.sampled = false;
            return LaneLoads.unavailable("lane sampling is disabled");
        }
        final List<ThreadCpuSource.ThreadCpu> threads = this.source.sample();
        final long now = this.nanoClock.getAsLong();
        if (threads.isEmpty()) {
            return LaneLoads.unavailable("this platform does not report per-thread CPU time");
        }

        final Map<Long, Long> current = new HashMap<>(threads.size());
        final Map<ExecutionLane, long[]> cpuByLane = new EnumMap<>(ExecutionLane.class);
        final Map<ExecutionLane, int[]> threadsByLane = new EnumMap<>(ExecutionLane.class);
        for (final ThreadCpuSource.ThreadCpu thread : threads) {
            current.put(thread.id(), thread.cpuNanos());
            final ExecutionLane lane = ExecutionLane.of(thread.name());
            threadsByLane.computeIfAbsent(lane, key -> new int[1])[0]++;
            final Long before = this.previousCpu.get(thread.id());
            if (before == null) {
                continue;                     // New thread: its total is not this interval's work.
            }
            // A counter that went backwards means the id was reused by a new thread. Its history
            // is not ours, so it contributes nothing rather than a negative.
            final long delta = Math.max(0L, thread.cpuNanos() - before);
            cpuByLane.computeIfAbsent(lane, key -> new long[1])[0] += delta;
        }

        final long interval = now - this.previousNanos;
        final boolean first = !this.sampled;
        this.previousCpu = current;
        this.previousNanos = now;
        this.sampled = true;
        if (first) {
            return LaneLoads.unavailable("the first sample establishes the baseline");
        }
        if (interval <= 0L) {
            return LaneLoads.unavailable("no time passed since the previous sample");
        }

        final List<LaneLoad> lanes = new ArrayList<>(threadsByLane.size());
        double total = 0.0;
        for (final Map.Entry<ExecutionLane, int[]> entry : threadsByLane.entrySet()) {
            final long[] cpu = cpuByLane.get(entry.getKey());
            final double cores = (cpu == null ? 0L : cpu[0]) / (double) interval;
            total += cores;
            lanes.add(new LaneLoad(entry.getKey(), entry.getValue()[0], cores));
        }
        lanes.sort((left, right) -> Double.compare(right.cores(), left.cores()));
        return new LaneLoads(true, "", interval, List.copyOf(lanes), total);
    }
}
