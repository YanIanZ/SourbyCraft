package dev.iyanz.sourbycraft.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * How the machine is currently divided between lanes, and how much of it is not divided at all.
 *
 * <p>Portions are derived from measurement every time they are asked for, never configured. The
 * point is to answer "where is the time going and what is spare" continuously, because the answer
 * moves with the workload: chunk generation dominates while players explore and falls away when
 * they settle.</p>
 *
 * <p>This reports and does not act. Resizing a pool from a control loop would be a config mutation
 * the operator did not ask for, and the evidence does not support it anyway — a chunk-worker A/B on
 * this hardware made every percentile worse while six of eight cores stayed idle, so more threads
 * for a busy lane is not a safe inference from that lane being busy.</p>
 */
public final class LanePortions {

    private LanePortions() {}

    /**
     * One lane's slice.
     *
     * @param lane           the lane
     * @param cores          CPU it consumed, in cores
     * @param shareOfUsed    its fraction of the CPU the server actually used
     * @param shareOfMachine its fraction of the whole machine
     */
    public record Portion(ExecutionLane lane, double cores, double shareOfUsed, double shareOfMachine) {}

    /**
     * The division of the machine at one moment.
     *
     * @param available    whether there was a usable reading
     * @param reason       why not, when there was not
     * @param machineCores cores the process may use
     * @param usedCores    cores it did use
     * @param idleCores    cores left unused; negative is impossible, zero means saturated
     * @param portions     lanes, heaviest first
     * @param dominant     the heaviest lane, or {@code null} when nothing ran
     */
    public record Report(boolean available, String reason, int machineCores, double usedCores,
                         double idleCores, List<Portion> portions, ExecutionLane dominant) {

        /** Whether one lane holds most of what the server used, which is what makes it the thing to fix. */
        public boolean isConcentrated() {
            return !this.portions.isEmpty() && this.portions.get(0).shareOfUsed() >= 0.5;
        }

        /** Whether the machine is effectively spent, so redistribution is the only move left. */
        public boolean isSaturated() {
            return this.available && this.idleCores <= 0.5;
        }
    }

    /**
     * Divides a lane reading into portions.
     *
     * @param loads        a lane reading
     * @param machineCores cores the process may use; must be positive
     * @return the division, or an unavailable report when the reading was not usable
     */
    public static Report of(final LaneCpuSampler.LaneLoads loads, final int machineCores) {
        if (machineCores <= 0) {
            return unavailable("the machine reports no usable cores", machineCores);
        }
        if (!loads.available()) {
            return unavailable(loads.reason(), machineCores);
        }
        final double used = loads.totalCores();
        final List<Portion> portions = new ArrayList<>(loads.lanes().size());
        for (final LaneCpuSampler.LaneLoad load : loads.lanes()) {
            // Share of used is undefined rather than zero when nothing ran: a lane that used none
            // of no CPU has no share, and calling that 0% would read as "idle while others worked".
            final double shareOfUsed = used > 0.0 ? load.cores() / used : Double.NaN;
            portions.add(new Portion(load.lane(), load.cores(), shareOfUsed, load.cores() / machineCores));
        }
        return new Report(true, "", machineCores, used, Math.max(0.0, machineCores - used),
            List.copyOf(portions), portions.isEmpty() ? null : portions.get(0).lane());
    }

    /**
     * A report for when there is nothing to divide yet — the collector has not run, or is not
     * running at all. Says why rather than reporting an idle machine.
     */
    public static Report notMeasured(final String reason) {
        return unavailable(reason, 0);
    }

    private static Report unavailable(final String reason, final int machineCores) {
        return new Report(false, reason, machineCores, Double.NaN, Double.NaN, List.of(), null);
    }
}
