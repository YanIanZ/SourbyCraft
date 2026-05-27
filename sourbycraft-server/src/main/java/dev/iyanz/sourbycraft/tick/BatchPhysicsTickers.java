package dev.iyanz.sourbycraft.tick;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import net.minecraft.world.entity.Entity;
import java.util.function.Predicate;

public final class BatchPhysicsTickers {

    private static volatile BatchPhysicsTicker INSTANCE;
    /** Pluggable physics phase. Default = noop (returns true ⇒ interact on main). */
    public static volatile Predicate<Entity> PHYSICS_PHASE = e -> true;

    private BatchPhysicsTickers() {}

    public static BatchPhysicsTicker get() {
        BatchPhysicsTicker b = INSTANCE;
        if (b == null) {
            synchronized (BatchPhysicsTickers.class) {
                b = INSTANCE;
                if (b == null) {
                    b = new BatchPhysicsTicker(
                        VirtualExecutor.executor(),
                        SourbyCraftConfig.v9BackpressureQueueDepthCap,
                        3,
                        SourbyCraftConfig.v9WatchdogCircuitBreakSeconds * 1000L,
                        SourbyCraftConfig.v9WatchdogTaskTimeoutMultiplier,
                        e -> PHYSICS_PHASE.test(e)
                    );
                    INSTANCE = b;
                }
            }
        }
        return b;
    }

    public static BatchPhysicsTicker peek() { return INSTANCE; }

    public static void shutdown() {
        BatchPhysicsTicker b = INSTANCE;
        if (b != null) b.shutdown();
        INSTANCE = null;
    }
}
