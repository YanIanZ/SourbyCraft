package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.common.time.TickData;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Folia/Canvas-aware MSPT: the <b>worst region's</b> tick time, in milliseconds.
 *
 * <p>On the region-threaded engine each region ticks on its own thread, so {@link
 * Bukkit#getAverageTickTime()} reports only the tick time of the region the <i>caller</i> happens to
 * run on. A staff member standing in a quiet region — or a console command that lands on the global
 * region — reads a healthy sub-millisecond value <i>while the spawn region chokes at seconds per
 * tick</i>. That is exactly why {@code /mspt} used to print {@code 0.88ms} while the Folia watchdog
 * screamed that a region had not responded in 5.38s: two different regions, two different numbers.
 *
 * <p>This surveys <b>every</b> loaded region across every world plus the global region and returns
 * the single highest per-region average tick time. TPS is intentionally NOT derived from this — the
 * region scheduler keeps cycling at ~20 TPS as long as most regions are healthy, so TPS stays ~19
 * (scheduler rate) while MSPT surfaces the worst region (which can legitimately touch thousands of
 * ms under load). That pairing — high MSPT, healthy TPS — is the honest region-threaded picture.
 *
 * <p>Fully defensive: any failure to reach the internal region API degrades to {@link
 * Bukkit#getAverageTickTime()} (the old local-region value) and never throws on the caller.
 */
public final class RegionMspt {

    private RegionMspt() {}

    /**
     * @return the worst region's average MSPT in milliseconds, or {@link Double#NaN} when there is
     *         no tick data yet (very early boot) or the region API is unavailable.
     */
    public static double worstMsptMs() {
        try {
            final long now = System.nanoTime();
            // Seed with the global region so a global-tick stall (e.g. a plugin startup task) also shows.
            final double[] worst = { msptOf(RegionizedServer.getGlobalTickData().getTickReport15s(now)) };
            for (final World world : Bukkit.getWorlds()) {
                final ServerLevel level = ((CraftWorld) world).getHandle();
                level.regioniser.computeForAllRegions(region ->
                    worst[0] = max(worst[0], msptOf(region.getData().getRegionSchedulingHandle().getTickReport15s(now))));
            }
            // No region has produced a report yet — fall back so callers still get a number.
            return Double.isNaN(worst[0]) ? Bukkit.getAverageTickTime() : worst[0];
        } catch (final Throwable t) {
            // Internal region API shape changed / unavailable — never break the caller.
            try {
                return Bukkit.getAverageTickTime();
            } catch (final Throwable ignored) {
                return Double.NaN;
            }
        }
    }

    /** Average tick time of one region's 15s report, in ms; NaN when the region has no data. */
    private static double msptOf(final TickData.TickReportData report) {
        if (report == null) {
            return Double.NaN;
        }
        return report.timePerTickData().segmentAll().average() / 1.0E6; // report is in ns
    }

    /** {@link Math#max} that treats NaN as "no reading" so a single empty region never wins. */
    private static double max(final double a, final double b) {
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        return Math.max(a, b);
    }
}
