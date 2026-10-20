package dev.iyanz.sourbycraft.perf;

/**
 * How long chunk-system loads take at the region-file (.mca) read stage, per world and storage type.
 *
 * <p><b>What is timed.</b> Feature patch 0050 wraps the region-file part of
 * {@code RegionFileStorage.moonrise$readData} on the chunk-system I/O thread: finding (and if needed
 * opening) the {@code .mca}, and reading the chunk's sectors into memory. For a chunk stored outside
 * the region file (an oversized {@code .mcc}) that call also decompresses and parses it, so those
 * loads include the parse. Only reads that found data are recorded (a missing chunk, which the
 * server then generates, is not a load); a read that throws is recorded as a failure and the
 * exception propagates unchanged. Not timed: decompression and NBT parsing of ordinary chunks
 * ({@code moonrise$finishRead}, on the chunk-system's decompression worker), vanilla's
 * non-chunk-system {@code read(ChunkPos)}, the region file's header recalculation on a later
 * retry, and storages attached to Aurora World Fabric (their reads never reach a region file).</p>
 *
 * <p><b>Shape.</b> The same recorder as saves, {@link RegionFileSaveLatency}: a lock-free ring of the
 * last {@link RegionFileSaveLatency#CAPACITY} samples per recorder, p50/p99/max over the last
 * {@link RegionFileSaveLatency#WINDOW_SECONDS} seconds, exact counts and all-time maximum. In a
 * {@link RegionFileSaveLatency.Snapshot} from {@link #GLOBAL}, {@code saves()} counts loads and
 * {@code failures()} loads that threw. The overhead has not been benchmarked on a server.</p>
 */
public final class RegionFileLoadLatency {

    public static final RegionFileSaveLatency GLOBAL = new RegionFileSaveLatency(System::nanoTime);

    private RegionFileLoadLatency() {}
}
