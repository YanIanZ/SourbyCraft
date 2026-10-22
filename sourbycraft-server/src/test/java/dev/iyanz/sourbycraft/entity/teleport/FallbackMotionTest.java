package dev.iyanz.sourbycraft.entity.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.papermc.paper.entity.TeleportFlag;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The velocity an async fallback starts with, so {@code TeleportFlag.Relative} velocity flags mean
 * the same as on Paper's synchronous path (which applies them through
 * {@code PositionMoveRotation.calculateAbsolute}); the inherited {@code teleportAsync} API always
 * passed zero.
 */
class FallbackMotionTest {

    private static final PositionMoveRotation MOVING =
        new PositionMoveRotation(new Vec3(10, 64, 10), new Vec3(0.5, -0.25, 1.0), 90.0F, 0.0F);

    @Test
    void apiFlagsTranslateLikePapersSyncPath() {
        assertEquals(EnumSet.noneOf(Relative.class), FallbackMotion.relatives());
        assertEquals(EnumSet.of(Relative.DELTA_X, Relative.DELTA_Z, Relative.ROTATE_DELTA),
            FallbackMotion.relatives(TeleportFlag.Relative.VELOCITY_X, TeleportFlag.EntityState.RETAIN_PASSENGERS,
                TeleportFlag.Relative.VELOCITY_Z, TeleportFlag.Relative.VELOCITY_ROTATION));
        assertEquals(EnumSet.of(Relative.DELTA_Y), FallbackMotion.relatives(TeleportFlag.Relative.VELOCITY_Y));
    }

    @Test
    void withoutVelocityFlagsTheEntityStopsAsOnPapersPath() {
        assertEquals(Vec3.ZERO, FallbackMotion.velocity(MOVING, new Vec3(0, 70, 0), 90.0F, 0.0F, Set.of()));
    }

    @Test
    void velocityFlagsKeepTheirComponents() {
        final Vec3 v = FallbackMotion.velocity(MOVING, new Vec3(0, 70, 0), 90.0F, 0.0F,
            EnumSet.of(Relative.DELTA_X, Relative.DELTA_Y));
        assertEquals(0.5, v.x, 1e-9);
        assertEquals(-0.25, v.y, 1e-9);
        assertEquals(0.0, v.z, 1e-9);
    }

    @Test
    void rotateDeltaTurnsTheKeptVelocityWithTheYaw() {
        final Vec3 v = FallbackMotion.velocity(MOVING, new Vec3(0, 70, 0), 180.0F, 0.0F,
            EnumSet.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z, Relative.ROTATE_DELTA));
        final Vec3 expected = PositionMoveRotation.calculateAbsolute(MOVING,
            new PositionMoveRotation(new Vec3(0, 70, 0), Vec3.ZERO, 180.0F, 0.0F),
            EnumSet.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z, Relative.ROTATE_DELTA)).deltaMovement();
        assertEquals(expected, v);
        assertEquals(1.118, v.horizontalDistance(), 1e-3, "rotated, not scaled");
    }
}
