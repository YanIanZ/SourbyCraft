package dev.iyanz.sourbycraft.awf;

/**
 * A chunk's position in an Aurora World Fabric world.
 *
 * @param x chunk x
 * @param z chunk z
 */
public record ChunkKey(int x, int z) implements Comparable<ChunkKey> {

    /** Packs both coordinates into one long, as the engine's own chunk keys do. */
    public long packed() {
        return ((long)this.z << 32) | (this.x & 0xFFFFFFFFL);
    }

    public static ChunkKey unpack(final long packed) {
        return new ChunkKey((int)packed, (int)(packed >>> 32));
    }

    @Override
    public int compareTo(final ChunkKey other) {
        final int byZ = Integer.compare(this.z, other.z);
        return byZ != 0 ? byZ : Integer.compare(this.x, other.x);
    }

    @Override
    public String toString() {
        return this.x + "," + this.z;
    }
}
