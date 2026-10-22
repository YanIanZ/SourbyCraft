package dev.iyanz.sourbycraft.execution.region;

import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;

/**
 * {@link RegionBackend}, {@link CurrentRegion}, {@link RegionPopulation} and {@link EnginePools} over
 * the Folia-derived region scheduler and the Moonrise chunk system.
 *
 * <p>With this in place the region scheduler is named in exactly two files: here, and
 * {@link dev.iyanz.sourbycraft.execution.RegionOwnerHandoff}. One answers "how is the engine
 * ticking" and "which region is this thread ticking", the other "hand this to whoever owns that
 * entity". Nothing else reaches the backend.</p>
 */
public final class FoliaRegionBackend implements RegionBackend, CurrentRegion, RegionPopulation, EnginePools, RegionTickRate {

    private static final int MAX_CACHED_ANCHORS = 4096;
    /** Anchors by region id; recomputed when the cached chunk has left the region. World held weakly. */
    private final AnchorCache anchors = new AnchorCache(MAX_CACHED_ANCHORS);

    @Override
    public RegionTickMetrics.Snapshot globalTickMetrics(final long nowNanos) {
        return RegionizedServer.getGlobalTickData().sourbyTickMetrics.current().refreshSnapshot(nowNanos);
    }

    @Override
    public RegionTickMetrics globalTickMetricsLive() {
        final TickRegionScheduler.RegionScheduleHandle global = RegionizedServer.getGlobalTickData();
        return global == null ? null : global.sourbyTickMetrics.current();
    }

    @Override
    public String globalTickFrameMarker() {
        return RegionizedServer.GlobalTickTickHandle.class.getName();
    }

    @Override
    public String regionTickFrameMarker() {
        return TickRegionScheduler.RegionScheduleHandle.class.getName();
    }

    @Override
    public double tickRateHz() {
        return TickRegionScheduler.getTickRate();
    }

    // RegionTickRate: the one server-wide rate (TickRegionScheduler), for ServerTickManager#getTickRate/setTickRate.

    @Override
    public float currentTickRate() {
        return TickRegionScheduler.getTickRate();
    }

    @Override
    public void setTickRate(final float hz) {
        TickRegionScheduler.setTickRate(hz);
    }

    /** Backs {@link RegionOwnership#ensureGlobalTickThread}: the engine's global-tick ownership check. */
    static void ensureGlobalTickThread(final String reason) {
        RegionizedServer.ensureGlobalTickThread(reason);
    }

    /** Backs {@link RegionOwnership#isGlobalTickThread}: the engine's non-throwing global-tick test. */
    static boolean isGlobalTickThread() {
        return RegionizedServer.isGlobalTickThread();
    }

    /** Backs {@link RegionOwnership#regionsTicking}: whether the region scheduler has started. */
    static boolean regionsStarted() {
        return TickRegions.hasStarted();
    }

    /** Backs {@link RegionOwnership#executeOnGlobalTick}: inline on the global tick thread, else queued for it. */
    static void executeOnGlobalTick(final Runnable task) {
        RegionizedServer.getInstance().scheduleToOrExecute(task);
    }

    /**
     * A chunk the calling thread's region owns, for the Aurora Bridge's caller-region route.
     * {@code getCenterChunk} sorts every chunk the region owns, so anchors are cached per region
     * id and recomputed only when the cached chunk has left the region.
     */
    @Override
    public RegionAnchor anchor() {
        final var region = TickRegionScheduler.getCurrentRegion();
        if (region == null) return null;
        final RegionAnchor cached = this.anchors.get(region.id, anchor -> org.bukkit.Bukkit.isOwnedByCurrentRegion(
            (org.bukkit.World) anchor.world(), anchor.chunkX(), anchor.chunkZ()));
        if (cached != null) {
            return cached;
        }
        final net.minecraft.world.level.ChunkPos center = region.getCenterChunk();
        if (center == null) return null;
        final RegionAnchor anchor = new RegionAnchor(region.regioniser.world.getWorld(), center.x(), center.z());
        this.anchors.put(region.id, anchor);
        return anchor;
    }

    /**
     * Sums each region's own counters (entities, players, chunks), which the region refreshes as it
     * ticks and publishes as atomics, so reading them needs no region's lock or thread.
     */
    @Override
    public java.util.List<WorldCounts> population() {
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null) return java.util.List.of();
        final java.util.List<WorldCounts> out = new java.util.ArrayList<>();
        for (final net.minecraft.server.level.ServerLevel world : server.getAllLevels()) {
            final long[] sums = new long[4];
            world.regioniser.computeForAllRegionsUnsynchronised(region -> {
                final var stats = region.getData().getRegionStats();
                sums[0]++;
                sums[1] += stats.getChunkCount();
                sums[2] += stats.getEntityCount();
                sums[3] += stats.getPlayerCount();
            });
            out.add(new WorldCounts(world.getWorld().getName(), (int)sums[0], sums[1], sums[2], sums[3]));
        }
        return out;
    }

    // EnginePools: the Moonrise chunk pools' configured sizes, for the thread ledger (audit ENG-9).

    @Override
    public int chunkWorkerThreads() {
        return poolSize(ca.spottedleaf.moonrise.common.util.MoonriseCommon.WORKER_POOL);
    }

    @Override
    public int chunkIoThreads() {
        return poolSize(ca.spottedleaf.moonrise.common.util.MoonriseCommon.IO_POOL);
    }

    private static int poolSize(final ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool pool) {
        return pool == null ? -1 : pool.getCoreThreads().length;
    }
}
