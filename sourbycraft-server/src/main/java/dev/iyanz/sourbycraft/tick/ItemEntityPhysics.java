package dev.iyanz.sourbycraft.tick;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Off-thread "physics phase" for item entities.
 *
 * v9.3: noop returning true (=> run full tick on main).
 * The real movement-only phase is deferred to v10 once we have added a
 * sourbycraft$movePhase(...) method to ItemEntity that excludes pickup.
 */
public final class ItemEntityPhysics {

    private ItemEntityPhysics() {}

    public static boolean apply(Entity entity) {
        if (!(entity instanceof ItemEntity)) return true;
        // No off-thread work yet. Return true so interact phase runs on main.
        return true;
    }
}
