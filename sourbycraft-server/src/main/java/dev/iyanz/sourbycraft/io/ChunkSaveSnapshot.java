package dev.iyanz.sourbycraft.io;

import java.util.List;

/** Immutable snapshot of a batched chunk save for one region. */
public final class ChunkSaveSnapshot {

    public static final class Entry {
        public final int chunkX;
        public final int chunkZ;
        public final byte[] nbtBytes;

        public Entry(int chunkX, int chunkZ, byte[] nbtBytes) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.nbtBytes = nbtBytes;
        }
    }

    private final String worldId;
    private final int regionX;
    private final int regionZ;
    private final List<Entry> entries;

    public ChunkSaveSnapshot(String worldId, int regionX, int regionZ, List<Entry> entries) {
        this.worldId = worldId;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.entries = List.copyOf(entries);
    }

    public String worldId() { return worldId; }
    public int regionX() { return regionX; }
    public int regionZ() { return regionZ; }
    public List<Entry> entries() { return entries; }
}
