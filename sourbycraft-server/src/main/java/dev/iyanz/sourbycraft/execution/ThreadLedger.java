package dev.iyanz.sourbycraft.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The platform threads the server is configured to run, per pool, against the processors the JVM
 * can see. Printed once at startup and shown in {@code /perf governor}.
 *
 * <p>This is a ledger of configured sizes, not a measurement: a region tick thread with no region
 * to tick idles, and lazily started pools hold fewer threads than their ceiling. It exists so an
 * operator can see oversubscription (audit ENG-9) without reading five configuration files. It
 * changes no default; every number comes from the setting named beside it.</p>
 */
public final class ThreadLedger {

    /**
     * One pool.
     *
     * @param name what the threads do
     * @param threads configured platform threads, {@code 0} when the pool is off, {@code -1} when
     *     not known yet (the pool has not been set up)
     * @param source the setting the number comes from
     */
    public record Pool(String name, int threads, String source) {}

    /** The engine's chunk pools, behind the Aurora contract (dependency ledger §1.2). */
    private static final dev.iyanz.sourbycraft.execution.region.EnginePools POOLS =
        new dev.iyanz.sourbycraft.execution.region.FoliaRegionBackend();

    private ThreadLedger() {}

    /** The pools as configured now. Never throws; a pool that cannot be read is reported as unknown. */
    public static List<Pool> collect() {
        final int processors = Runtime.getRuntime().availableProcessors();
        final List<Pool> pools = new ArrayList<>();
        pools.add(read("Region tick", ThreadLedger::regionTick, "paper-global.yml threaded-regions.threads"));
        pools.add(read("Chunk workers", POOLS::chunkWorkerThreads, "paper-global.yml chunk-system.worker-threads"));
        pools.add(read("Chunk I/O", POOLS::chunkIoThreads, "paper-global.yml chunk-system.io-threads"));
        pools.add(read("Netty event loops", () -> Integer.getInteger("io.netty.eventLoopThreads", processors * 2),
            "spigot.yml settings.netty-threads"));
        pools.add(read("Async scheduler core", () -> Math.max(4, processors / 2),
            "Folia async scheduler: max(4, processors / 2) core threads, more on demand"));
        pools.add(read("Async pathfinding", () -> dev.iyanz.sourbycraft.perf.AsyncPathProcessor.isEnabled()
            ? dev.iyanz.sourbycraft.perf.AsyncPathProcessor.poolThreads() : 0,
            dev.iyanz.sourbycraft.config.AuroraConfig.ASYNC_PATH_KEY + " (processors / 4 when on)"));
        pools.add(read("Bridge I/O lane", () -> ResourceGovernor.GLOBAL.budgets().resolvedBridgeIoThreads(),
            dev.iyanz.sourbycraft.config.AuroraConfig.BRIDGE_IO_THREADS_KEY));
        pools.add(read("Storage lane", () -> ResourceGovernor.GLOBAL.budgets().resolvedStorageThreads(),
            dev.iyanz.sourbycraft.config.AuroraConfig.STORAGE_THREADS_KEY));
        return List.copyOf(pools);
    }

    private static int regionTick() {
        final int configured = io.papermc.paper.configuration.GlobalConfiguration.get().threadedRegions.threads;
        return configured > 0 ? configured : dev.iyanz.aurora.cpu.AuroraCpu.regionTickThreads();
    }

    private static Pool read(final String name, final java.util.function.IntSupplier threads, final String source) {
        try {
            return new Pool(name, threads.getAsInt(), source);
        } catch (final RuntimeException | LinkageError unavailable) {
            return new Pool(name, -1, source);
        }
    }

    /**
     * The ledger as text: a total line, then one line per pool.
     *
     * @param processors {@code Runtime.availableProcessors()}
     */
    public static List<String> format(final List<Pool> pools, final int processors) {
        int total = 0;
        int unknown = 0;
        int width = 0;
        for (final Pool pool : pools) {
            if (pool.threads() > 0) total += pool.threads();
            if (pool.threads() < 0) unknown++;
            width = Math.max(width, pool.name().length());
        }
        final List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT,
            "Thread ledger: %d configured platform thread(s) in %d pool(s) for %d available processor(s) (%.1fx)%s;"
                + " configured ceilings, not measured load",
            total, pools.size(), processors, processors <= 0 ? 0.0 : total / (double) processors,
            unknown > 0 ? ", " + unknown + " pool(s) not set up yet" : ""));
        for (final Pool pool : pools) {
            final String count = pool.threads() < 0 ? "?" : pool.threads() == 0 ? "off" : Integer.toString(pool.threads());
            lines.add(String.format(Locale.ROOT, "  %-" + width + "s %4s  (%s)", pool.name(), count, pool.source()));
        }
        lines.add("  Not counted: virtual-thread executors, and async scheduler threads beyond its core size.");
        return List.copyOf(lines);
    }
}
