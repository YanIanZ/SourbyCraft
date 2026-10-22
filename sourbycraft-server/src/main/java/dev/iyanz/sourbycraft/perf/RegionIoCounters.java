package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.atomic.LongAdder;

/**
 * Chunk-system storage operations, counted where every chunk, entity and POI read and write passes:
 * the Moonrise {@code readData} and {@code finishWrite} paths of {@code RegionFileStorage}, whether
 * the bytes then go to a region file or to Aurora World Fabric. Vanilla's own {@code read}/
 * {@code write} paths (upgrade tools) are not counted.
 */
public final class RegionIoCounters {

    public static final RegionIoCounters GLOBAL = new RegionIoCounters();

    public record Totals(long reads, long writes, long deletes) {}

    private final LongAdder reads = new LongAdder();
    private final LongAdder writes = new LongAdder();
    private final LongAdder deletes = new LongAdder();

    /** One chunk-system read reached storage. */
    public void read() {
        this.reads.increment();
    }

    /** One chunk-system write reached its I/O stage; {@code delete} for a removal. */
    public void write(final boolean delete) {
        (delete ? this.deletes : this.writes).increment();
    }

    public Totals totals() {
        return new Totals(this.reads.sum(), this.writes.sum(), this.deletes.sum());
    }
}
