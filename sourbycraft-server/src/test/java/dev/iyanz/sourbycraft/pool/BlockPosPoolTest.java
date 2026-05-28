package dev.iyanz.sourbycraft.pool;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockPosPoolTest {

    @Test
    void acquireReturnsZeroedPos() {
        BlockPos.MutableBlockPos p = BlockPosPool.acquire();
        try {
            assertEquals(0, p.getX());
            assertEquals(0, p.getY());
            assertEquals(0, p.getZ());
        } finally {
            BlockPosPool.release(p);
        }
    }

    @Test
    void releaseReusesInstance() {
        BlockPos.MutableBlockPos first = BlockPosPool.acquire();
        first.set(1, 2, 3);
        BlockPosPool.release(first);
        BlockPos.MutableBlockPos second = BlockPosPool.acquire();
        try {
            assertSame(first, second, "Pool should hand back the same instance after release");
            assertEquals(0, second.getX(), "Released instance must be zeroed on next acquire");
        } finally {
            BlockPosPool.release(second);
        }
    }

    @Test
    void drainDoesNotLeakAcrossThreads() throws Exception {
        BlockPos.MutableBlockPos main = BlockPosPool.acquire();
        BlockPosPool.release(main);

        final BlockPos.MutableBlockPos[] other = new BlockPos.MutableBlockPos[1];
        Thread t = new Thread(() -> other[0] = BlockPosPool.acquire());
        t.start();
        t.join();
        assertNotSame(main, other[0], "ThreadLocal pool must not share instances across threads");
        BlockPosPool.release(other[0]);
    }
}
