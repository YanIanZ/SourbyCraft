package dev.iyanz.sourbycraft.perf;

import java.util.List;

/**
 * The chunk system's storage backlog per world, as Aurora needs it for {@code /perf storage}.
 * {@link RegionIoQueue} supplies it from the current engine.
 */
public interface StorageBacklog {

    /** I/O tasks accepted and not finished, per data type, for one world. */
    record WorldQueue(String world, long chunk, long poi, long entity) {
        public long total() {
            return this.chunk + this.poi + this.entity;
        }
    }

    /** One entry per loaded world; empty before the server exists. */
    List<WorldQueue> sample();
}
