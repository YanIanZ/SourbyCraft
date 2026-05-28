package dev.iyanz.sourbycraft.tick;

import net.minecraft.world.entity.Entity;

public final class EntityTickRouter {

    private EntityTickRouter() {}

    public static boolean route(
        Entity entity,
        BatchPhysicsTicker ticker,
        boolean parallelItem,
        boolean parallelOrb,
        boolean parallelArrow,
        Runnable inlineTick
    ) {
        EntityClass cls = EntityTickClassifier.classify(entity);
        boolean allowed = switch (cls) {
            case ITEM -> parallelItem;
            case EXP_ORB -> parallelOrb;
            case ARROW -> parallelArrow;
            default -> false;
        };
        if (!allowed) {
            inlineTick.run();
            return false;
        }
        boolean queued = ticker.submit(new ParallelTickSnapshot(entity, cls));
        if (!queued) {
            inlineTick.run();
            return false;
        }
        return true;
    }
}
