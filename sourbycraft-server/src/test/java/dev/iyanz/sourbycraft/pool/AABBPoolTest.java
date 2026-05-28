package dev.iyanz.sourbycraft.pool;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AABBPoolTest {

    @Test
    void acquireReturnsZero() {
        AABBPool.MutableAABB b = AABBPool.acquire();
        try {
            assertEquals(0.0, b.minX);
            assertEquals(0.0, b.maxX);
        } finally {
            AABBPool.release(b);
        }
    }

    @Test
    void snapshotProducesImmutableAABB() {
        AABBPool.MutableAABB b = AABBPool.acquire();
        try {
            b.set(0, 0, 0, 1, 2, 3);
            AABB snap = b.snapshot();
            b.set(9, 9, 9, 9, 9, 9);
            assertEquals(0.0, snap.minX);
            assertEquals(1.0, snap.maxX);
            assertEquals(3.0, snap.maxZ);
        } finally {
            AABBPool.release(b);
        }
    }

    @Test
    void releaseReuses() {
        AABBPool.MutableAABB a = AABBPool.acquire();
        a.set(1, 1, 1, 2, 2, 2);
        AABBPool.release(a);
        AABBPool.MutableAABB b = AABBPool.acquire();
        try {
            assertSame(a, b);
            assertEquals(0.0, b.minX);
        } finally {
            AABBPool.release(b);
        }
    }
}
