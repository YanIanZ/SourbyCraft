package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.function.BooleanSupplier;

/**
 * When the {@code PortalCreateEvent} with reason END_PLATFORM may fire. The base never fired it (an
 * unconditional {@code if (false)}). It is fired only when an entity caused the platform (world
 * generation passes none, and the event needs one) and the calling thread owns the platform's
 * position, so listeners run on the region that is placing the blocks, exactly like any other
 * block-placement event. Otherwise the platform is built silently as before. The caller that matters is
 * {@code Entity.findOrCreatePortalAsync} (every End-portal trip passes through it); it now hands over the portaling
 * entity, which has already been removed from its origin region, so a listener sees it as an entity in transit.
 */
public final class EndPlatformEvents {

    private EndPlatformEvents() {
    }

    /** Engine glue. */
    public static boolean shouldFire(final net.minecraft.world.entity.@org.jspecify.annotations.Nullable Entity entity,
                                     final net.minecraft.world.level.ServerLevelAccessor level, final net.minecraft.core.BlockPos origin) {
        return shouldFire(entity != null, () -> RegionOwnership.current().isTickThreadFor(level.getLevel(), origin));
    }

    /** The rule: an entity is known and this thread owns the platform. Ownership is read only when needed. */
    public static boolean shouldFire(final boolean hasEntity, final BooleanSupplier ownsPlatform) {
        return hasEntity && ownsPlatform.getAsBoolean();
    }
}
