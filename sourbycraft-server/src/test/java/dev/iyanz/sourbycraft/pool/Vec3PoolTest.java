package dev.iyanz.sourbycraft.pool;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Vec3PoolTest {

    @Test
    void acquireReturnsZero() {
        Vec3Pool.MutableVec3 v = Vec3Pool.acquire();
        try {
            assertEquals(0.0, v.x);
            assertEquals(0.0, v.y);
            assertEquals(0.0, v.z);
        } finally {
            Vec3Pool.release(v);
        }
    }

    @Test
    void snapshotProducesImmutableVec3() {
        Vec3Pool.MutableVec3 v = Vec3Pool.acquire();
        try {
            v.set(1.0, 2.0, 3.0);
            Vec3 snap = v.snapshot();
            v.set(99.0, 99.0, 99.0);
            assertEquals(1.0, snap.x);
            assertEquals(2.0, snap.y);
            assertEquals(3.0, snap.z);
        } finally {
            Vec3Pool.release(v);
        }
    }

    @Test
    void releaseReuses() {
        Vec3Pool.MutableVec3 a = Vec3Pool.acquire();
        a.set(5, 5, 5);
        Vec3Pool.release(a);
        Vec3Pool.MutableVec3 b = Vec3Pool.acquire();
        try {
            assertSame(a, b);
            assertEquals(0.0, b.x);
        } finally {
            Vec3Pool.release(b);
        }
    }
}
