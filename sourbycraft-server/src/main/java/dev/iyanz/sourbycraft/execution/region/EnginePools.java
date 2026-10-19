package dev.iyanz.sourbycraft.execution.region;

/**
 * The engine's shared chunk pools as sized now, for the startup thread ledger and
 * {@code /perf governor}. {@link FoliaRegionBackend} supplies it.
 *
 * <p>These are configured ceilings read from the pools themselves, not measured load. A pool
 * that has not been set up yet reports {@code -1}; the ledger prints it as unknown.</p>
 */
public interface EnginePools {

    /** Platform threads of the chunk worker pool (generation, loading, lighting), or {@code -1}. */
    int chunkWorkerThreads();

    /** Platform threads of the chunk I/O pool, or {@code -1}. */
    int chunkIoThreads();
}
