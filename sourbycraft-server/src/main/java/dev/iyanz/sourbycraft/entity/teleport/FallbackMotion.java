package dev.iyanz.sourbycraft.entity.teleport;

import io.papermc.paper.entity.TeleportFlag;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * Carries the caller's {@code TeleportFlag.Relative} velocity flags into an async fallback, so they
 * mean what they mean on Paper's synchronous path ({@code CraftEntity#teleport0} turns them into
 * {@code Relative.DELTA_*}/{@code ROTATE_DELTA} and the move applies them through
 * {@code PositionMoveRotation.calculateAbsolute}). The inherited {@code teleportAsync} API ignores
 * the flags and always starts the entity at zero velocity. Evaluated on the entity's own region.
 */
public final class FallbackMotion {

    private FallbackMotion() {}

    /** The NMS relatives for the API flags; non-velocity flags (entity state) carry no motion. */
    public static Set<Relative> relatives(final TeleportFlag... flags) {
        final EnumSet<Relative> relatives = EnumSet.noneOf(Relative.class);
        for (final TeleportFlag flag : flags) {
            if (flag instanceof TeleportFlag.Relative relative) {
                relatives.add(switch (relative) {
                    case VELOCITY_X -> Relative.DELTA_X;
                    case VELOCITY_Y -> Relative.DELTA_Y;
                    case VELOCITY_Z -> Relative.DELTA_Z;
                    case VELOCITY_ROTATION -> Relative.ROTATE_DELTA;
                });
            }
        }
        return relatives;
    }

    /** The velocity Paper's sync path would leave the entity with after moving to {@code pos}/{@code yaw}/{@code pitch}. */
    public static Vec3 velocity(final PositionMoveRotation current, final Vec3 pos, final float yaw, final float pitch,
                                final Set<Relative> relatives) {
        return PositionMoveRotation.calculateAbsolute(current, new PositionMoveRotation(pos, Vec3.ZERO, yaw, pitch), relatives)
            .deltaMovement();
    }
}
