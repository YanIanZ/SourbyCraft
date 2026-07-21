package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.TickData;
import dev.iyanz.sourbycraft.util.ContainerMemory;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * SourbyCraft rich performance snapshot — the honest, region-threading-aware picture that TPS and a
 * single worst-MSPT number cannot give.
 *
 * <p>TPS on this engine is the scheduler rate (stays ~20 while a region chokes). A single worst-MSPT
 * hides whether ONE region is bad or MANY are, and an <i>average</i> MSPT hides the tail that players
 * actually feel. This surveys every region and computes the metrics that matter:
 * <ul>
 *   <li><b>utilisation</b> — the fraction of wall-clock a region spent ticking; the real "how loaded",
 *       independent of the 20 TPS cap.</li>
 *   <li><b>MSPT tail</b> — the worst region's p95/p99 tick, not just its average.</li>
 *   <li><b>missing CPU time</b> — CPU a region was owed but didn't get, i.e. tick-thread
 *       oversubscription / starvation (too few threads for the live region count).</li>
 *   <li><b>region spread</b> — how many regions are actually ticking, and the worst/median across
 *       them (one bad region vs a systemically loaded server look identical on /tps).</li>
 * </ul>
 * Combined with {@link GcTracker} (GC overhead, invisible to TPS/MSPT) and heap/RSS, this is a
 * genuinely accurate health readout. Fully defensive: any failure degrades to {@link Snapshot#EMPTY}.
 */
public final class PerfMetrics {

    private PerfMetrics() {}

    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    public static Snapshot snapshot() {
        try {
            final long now = System.nanoTime();
            final List<double[]> regions = new ArrayList<>(); // [avgMspt, p95Mspt, worstMspt, utilPct, missingCpuMs]

            // Global region first — a global-tick stall (plugin startup task, autosave) is real load too.
            addRegion(regions, RegionizedServer.getGlobalTickData().getTickReport15s(now));
            for (final World world : Bukkit.getWorlds()) {
                final ServerLevel level = ((CraftWorld) world).getHandle();
                level.regioniser.computeForAllRegions(region ->
                    addRegion(regions, region.getData().getRegionSchedulingHandle().getTickReport15s(now)));
            }

            final int regionCount = regions.size();
            double worstAvg = 0, worstP95 = 0, worstSingle = 0, maxUtil = 0, sumUtil = 0, totalMissingCpu = 0;
            final double[] avgs = new double[Math.max(1, regionCount)];
            for (int i = 0; i < regionCount; i++) {
                final double[] r = regions.get(i);
                avgs[i] = r[0];
                worstAvg = Math.max(worstAvg, r[0]);
                worstP95 = Math.max(worstP95, r[1]);
                worstSingle = Math.max(worstSingle, r[2]);
                maxUtil = Math.max(maxUtil, r[3]);
                sumUtil += r[3];
                totalMissingCpu += r[4];
            }
            final double medianAvg = median(avgs, regionCount);
            final double avgUtil = regionCount > 0 ? sumUtil / regionCount : 0.0;

            // scheduler rate (caller region view — fine for the headline TPS number)
            double tps1m = Double.NaN;
            try {
                final double[] t = Bukkit.getTPS();
                if (t.length > 0) tps1m = Math.min(20.0, t[0]);
            } catch (final Throwable ignored) {
                // off any region / very early boot
            }
            final int tickThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

            // memory
            final MemoryUsage heap = MEMORY.getHeapMemoryUsage();
            final long heapUsed = heap.getUsed();
            final long heapCommitted = heap.getCommitted();
            final long heapMax = heap.getMax();
            final double rssPct = ContainerMemory.usagePercentOrNaN();

            // load
            final int players = safePlayers();
            final long chunks = safeChunks();
            final long entities = safeEntities();

            return new Snapshot(
                regionCount, worstAvg, medianAvg, worstP95, worstSingle, maxUtil, avgUtil, totalMissingCpu,
                tps1m, tickThreads,
                heapUsed, heapCommitted, heapMax, rssPct,
                players, chunks, entities,
                GcTracker.snapshot());
        } catch (final Throwable t) {
            return Snapshot.EMPTY;
        }
    }

    /** Extract [avgMspt, p95Mspt, worstMspt, utilPct, missingCpuMs] from one region's 15s report. */
    private static void addRegion(final List<double[]> out, final TickData.TickReportData report) {
        if (report == null) return;
        final TickData.SegmentedAverage mspt = report.timePerTickData();
        if (mspt == null) return;
        final double avg = mspt.segmentAll().average() / 1.0E6;
        // worst-5% average = the tail players feel; worst single = segmentAll greatest
        final double p95 = mspt.segment5PercentWorst() != null ? mspt.segment5PercentWorst().average() / 1.0E6 : avg;
        final double worst = mspt.segmentAll().greatest() / 1.0E6;
        final double utilPct = report.utilisation() * 100.0;
        double missingCpuMs = 0.0;
        final TickData.SegmentedAverage missing = report.missingCPUTimeData();
        if (missing != null && missing.segmentAll() != null) {
            missingCpuMs = Math.max(0.0, missing.segmentAll().average() / 1.0E6);
        }
        out.add(new double[]{avg, p95, worst, utilPct, missingCpuMs});
    }

    private static double median(final double[] a, final int len) {
        if (len <= 0) return 0.0;
        final double[] copy = new double[len];
        System.arraycopy(a, 0, copy, 0, len);
        java.util.Arrays.sort(copy);
        return len % 2 == 1 ? copy[len / 2] : (copy[len / 2 - 1] + copy[len / 2]) / 2.0;
    }

    private static int safePlayers() {
        try {
            return Bukkit.getOnlinePlayers().size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    private static long safeChunks() {
        try {
            long c = 0;
            for (final World w : Bukkit.getWorlds()) c += w.getChunkCount();
            return c;
        } catch (final Throwable t) {
            return -1;
        }
    }

    private static long safeEntities() {
        try {
            long e = 0;
            for (final World w : Bukkit.getWorlds()) {
                e += w.getEntityCount();
            }
            return e;
        } catch (final Throwable t) {
            return -1; // off-region / API shape changed — omit rather than risk a bad read
        }
    }

    /**
     * A point-in-time performance readout. MSPT figures are ms; utilisation figures are % of
     * wall-clock; {@code missingCpuMs} is summed across regions (starvation). {@code rssPct} is
     * NaN outside a container.
     */
    public record Snapshot(
        int regionCount,
        double worstRegionMspt,
        double medianRegionMspt,
        double worstRegionP95Mspt,
        double worstSingleTickMs,
        double maxUtilisation,
        double avgUtilisation,
        double totalMissingCpuMs,
        double tps1m,
        int tickThreads,
        long heapUsed,
        long heapCommitted,
        long heapMax,
        double rssPercent,
        int players,
        long loadedChunks,
        long entities,
        GcTracker.Gc gc
    ) {
        public static final Snapshot EMPTY = new Snapshot(
            0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, 0, 0, 0, 0, Double.NaN, -1, -1, -1, GcTracker.Gc.EMPTY);

        public double heapPercent() {
            return this.heapMax > 0 ? 100.0 * this.heapUsed / this.heapMax : Double.NaN;
        }

        /**
         * A single 0-100 health score distilled from the accurate signals (not just TPS): region
         * headroom (worst utilisation + tail MSPT), CPU starvation, GC overhead, and heap pressure.
         * 100 = idle-healthy, &lt;50 = a region is saturated or GC/heap is hurting. Display-only.
         */
        public int healthScore() {
            double score = 100.0;
            if (!Double.isNaN(this.maxUtilisation)) {
                score -= Math.max(0.0, this.maxUtilisation - 70.0) * (30.0 / 30.0); // 70%->0, 100%->-30
            }
            if (!Double.isNaN(this.worstRegionP95Mspt)) {
                score -= Math.min(30.0, Math.max(0.0, this.worstRegionP95Mspt - 40.0) * (30.0 / 60.0)); // 40ms->0, 100ms->-30
            }
            if (this.gc != null && this.gc.hasData()) {
                score -= Math.min(20.0, this.gc.gcTimePercent() * 2.0); // 10% GC -> -20
            }
            final double heapPct = this.heapPercent();
            if (!Double.isNaN(heapPct)) {
                score -= Math.min(20.0, Math.max(0.0, heapPct - 80.0)); // 80%->0, 100%->-20
            }
            if (this.totalMissingCpuMs > 5.0) {
                score -= Math.min(15.0, this.totalMissingCpuMs - 5.0);
            }
            return (int) Math.round(Math.max(0.0, Math.min(100.0, score)));
        }
    }
}
