package dev.iyanz.sourbycraft.io;

/** Result of an async chunk save attempt. */
public final class ChunkSaveDiff {

    private final String worldId;
    private final int regionX;
    private final int regionZ;
    private final boolean success;
    private final int chunksWritten;
    private final String failureReason;

    private ChunkSaveDiff(String worldId, int regionX, int regionZ, boolean success, int chunksWritten, String failureReason) {
        this.worldId = worldId;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.success = success;
        this.chunksWritten = chunksWritten;
        this.failureReason = failureReason;
    }

    public static ChunkSaveDiff success(String worldId, int regionX, int regionZ, int chunksWritten) {
        return new ChunkSaveDiff(worldId, regionX, regionZ, true, chunksWritten, null);
    }

    public static ChunkSaveDiff failure(String worldId, int regionX, int regionZ, String reason) {
        return new ChunkSaveDiff(worldId, regionX, regionZ, false, 0, reason);
    }

    public String worldId() { return worldId; }
    public int regionX() { return regionX; }
    public int regionZ() { return regionZ; }
    public boolean success() { return success; }
    public int chunksWritten() { return chunksWritten; }
    public String failureReason() { return failureReason; }
}
