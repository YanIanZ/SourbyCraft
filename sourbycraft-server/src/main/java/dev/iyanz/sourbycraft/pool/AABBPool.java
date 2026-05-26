package dev.iyanz.sourbycraft.pool;

import net.minecraft.world.phys.AABB;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-thread pool of mutable AABB wrappers.
 * AABB itself is immutable; this wrapper allows reuse across calculation
 * scopes and produces an immutable AABB via {@link MutableAABB#snapshot()}.
 */
public final class AABBPool {

    public static final int MAX_PER_THREAD = 32;

    public static final class MutableAABB {
        public double minX, minY, minZ, maxX, maxY, maxZ;
        public void set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
        public AABB snapshot() { return new AABB(minX, minY, minZ, maxX, maxY, maxZ); }
    }

    private static final ThreadLocal<Deque<MutableAABB>> TL =
        ThreadLocal.withInitial(ArrayDeque::new);

    private AABBPool() {}

    public static MutableAABB acquire() {
        Deque<MutableAABB> stack = TL.get();
        MutableAABB b = stack.pollFirst();
        if (b == null) return new MutableAABB();
        b.set(0, 0, 0, 0, 0, 0);
        return b;
    }

    public static void release(MutableAABB b) {
        if (b == null) return;
        Deque<MutableAABB> stack = TL.get();
        if (stack.size() < MAX_PER_THREAD) stack.addFirst(b);
    }
}
