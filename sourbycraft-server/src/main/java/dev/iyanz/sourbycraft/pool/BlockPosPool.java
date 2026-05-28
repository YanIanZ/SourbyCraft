package dev.iyanz.sourbycraft.pool;

import net.minecraft.core.BlockPos;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-thread pool of {@link BlockPos.MutableBlockPos}.
 * Cross-thread sharing is unsupported by design (ThreadLocal storage).
 */
public final class BlockPosPool {

    private static final int MAX_PER_THREAD = 32;

    private static final ThreadLocal<Deque<BlockPos.MutableBlockPos>> TL =
        ThreadLocal.withInitial(ArrayDeque::new);

    private BlockPosPool() {}

    public static BlockPos.MutableBlockPos acquire() {
        Deque<BlockPos.MutableBlockPos> stack = TL.get();
        BlockPos.MutableBlockPos p = stack.pollFirst();
        if (p == null) {
            return new BlockPos.MutableBlockPos();
        }
        p.set(0, 0, 0);
        return p;
    }

    public static void release(BlockPos.MutableBlockPos p) {
        if (p == null) return;
        Deque<BlockPos.MutableBlockPos> stack = TL.get();
        if (stack.size() < MAX_PER_THREAD) {
            stack.addFirst(p);
        }
    }
}
