package dev.iyanz.sourbycraft.pool;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-thread pool of mutable Vec3 wrappers.
 * Vec3 itself is immutable; this wrapper allows reuse across calculation
 * scopes and produces an immutable Vec3 via {@link MutableVec3#snapshot()}.
 */
public final class Vec3Pool {

    public static final int MAX_PER_THREAD = 32;

    public static final class MutableVec3 {
        public double x, y, z;
        public void set(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        public Vec3 snapshot() { return new Vec3(x, y, z); }
    }

    private static final ThreadLocal<Deque<MutableVec3>> TL =
        ThreadLocal.withInitial(ArrayDeque::new);

    private Vec3Pool() {}

    public static MutableVec3 acquire() {
        Deque<MutableVec3> stack = TL.get();
        MutableVec3 v = stack.pollFirst();
        if (v == null) return new MutableVec3();
        v.set(0, 0, 0);
        return v;
    }

    public static void release(MutableVec3 v) {
        if (v == null) return;
        Deque<MutableVec3> stack = TL.get();
        if (stack.size() < MAX_PER_THREAD) stack.addFirst(v);
    }
}
