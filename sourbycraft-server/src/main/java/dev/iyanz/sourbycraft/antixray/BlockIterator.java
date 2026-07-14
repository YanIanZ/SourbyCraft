package dev.iyanz.sourbycraft.antixray;

/**
 * Fast voxel-traversal (Amanatides &amp; Woo) block iterator — a faithful port of
 * stonar96/RayTraceAntiXray's {@code util.BlockIterator} so SourbyEngine's line-of-sight test
 * matches RayTraceAntiXray exactly. Walks the integer block grid a ray passes through, from a
 * start point along a normalized direction up to {@code distance}, returning each successive
 * block coordinate via {@link #calculateNext()}.
 *
 * <p>Amanatides, J., &amp; Woo, A. <i>A Fast Voxel Traversal Algorithm for Ray Tracing.</i>
 * http://www.cse.yorku.ca/~amana/research/grid.pdf
 *
 * <p>Reuses two int[3] buffers to avoid per-step garbage.
 */
public final class BlockIterator {
    private int x;
    private int y;
    private int z;
    private int stepX;
    private int stepY;
    private int stepZ;
    private double tMax;
    private double tMaxX;
    private double tMaxY;
    private double tMaxZ;
    private double tDeltaX;
    private double tDeltaY;
    private double tDeltaZ;
    private int[] ref = new int[3];
    private int[] refSwap = new int[3];
    private int[] next;

    public BlockIterator() {}

    /** @param startX/Y/Z ray origin; {@code direction*} normalized; {@code distance} max ray length. */
    public BlockIterator initializeNormalized(int x, int y, int z, double startX, double startY, double startZ,
                                              double directionX, double directionY, double directionZ, double distance) {
        this.x = x;
        this.y = y;
        this.z = z;
        tMax = distance;
        stepX = directionX < 0. ? -1 : 1;
        stepY = directionY < 0. ? -1 : 1;
        stepZ = directionZ < 0. ? -1 : 1;
        tMaxX = directionX == 0. ? Double.POSITIVE_INFINITY : (x + (stepX + 1) / 2 - startX) / directionX;
        tMaxY = directionY == 0. ? Double.POSITIVE_INFINITY : (y + (stepY + 1) / 2 - startY) / directionY;
        tMaxZ = directionZ == 0. ? Double.POSITIVE_INFINITY : (z + (stepZ + 1) / 2 - startZ) / directionZ;
        tDeltaX = 1. / Math.abs(directionX);
        tDeltaY = 1. / Math.abs(directionY);
        tDeltaZ = 1. / Math.abs(directionZ);
        next = ref;
        ref[0] = x;
        ref[1] = y;
        ref[2] = z;
        return this;
    }

    /**
     * Advance to the next block the ray enters. Returns the (reused) int[3] {x,y,z}, or {@code null}
     * once the ray has travelled past {@code distance}. The first {x,y,z} written by
     * {@code initializeNormalized} is the START block and is NOT returned by the first call — the
     * first call already steps to the second block (matching RayTraceAntiXray, where the start block
     * = the target ore is skipped as its own occluder).
     */
    public int[] calculateNext() {
        if (tMaxX < tMaxY) {
            if (tMaxZ < tMaxX) {
                if (tMaxZ <= tMax) {
                    z += stepZ;
                    ref[0] = x;
                    ref[1] = y;
                    ref[2] = z;
                    tMaxZ += tDeltaZ;
                } else {
                    next = null;
                }
            } else {
                if (tMaxX <= tMax) {
                    if (tMaxZ == tMaxX) {
                        z += stepZ;
                        tMaxZ += tDeltaZ;
                    }

                    x += stepX;
                    ref[0] = x;
                    ref[1] = y;
                    ref[2] = z;
                    tMaxX += tDeltaX;
                } else {
                    next = null;
                }
            }
        } else if (tMaxY < tMaxZ) {
            if (tMaxY <= tMax) {
                if (tMaxX == tMaxY) {
                    x += stepX;
                    tMaxX += tDeltaX;
                }

                y += stepY;
                ref[0] = x;
                ref[1] = y;
                ref[2] = z;
                tMaxY += tDeltaY;
            } else {
                next = null;
            }
        } else {
            if (tMaxZ <= tMax) {
                if (tMaxX == tMaxZ) {
                    x += stepX;
                    tMaxX += tDeltaX;
                }

                if (tMaxY == tMaxZ) {
                    y += stepY;
                    tMaxY += tDeltaY;
                }

                z += stepZ;
                ref[0] = x;
                ref[1] = y;
                ref[2] = z;
                tMaxZ += tDeltaZ;
            } else {
                next = null;
            }
        }

        return next;
    }
}
