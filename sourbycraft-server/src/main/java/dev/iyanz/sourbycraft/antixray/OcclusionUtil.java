package dev.iyanz.sourbycraft.antixray;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Line-of-sight check between two world positions.
 *
 * <p>Uses vanilla {@link Level#clip(ClipContext)} which already runs a
 * DDA voxel traversal over the chunk's block-state palette, so this
 * helper is a thin wrapper rather than a re-implementation of the
 * RayTraceAntiXray BlockIterator. Same observable behaviour
 * (skip-non-solid, hit-on-collision-shape) at cheaper code volume.
 *
 * <p>Used by {@link VisibilityCache} + {@link RayTraceWorker} to
 * decide whether an ore at {@code target} is currently visible from
 * the {@code from} eye position; if not, the ore stays
 * obfuscated through Paper's existing anti-xray engine-mode 1 path.
 */
public final class OcclusionUtil {

    private OcclusionUtil() {}

    /**
     * Returns {@code true} when no solid block sits between {@code from}
     * and {@code to}.  The ray is treated as colliding with block-shape
     * collision (not fluids); ores themselves are treated as transparent
     * targets — the caller positions {@code to} at the ore centre and
     * accepts a MISS or a hit at the ore's own block.
     */
    public static boolean isVisible(final Level level, final Vec3 from, final Vec3 to) {
        return isVisible(level, from, to, false);
    }

    /** @param fluidObscures when true the ray also collides with fluids (water hides ores). */
    public static boolean isVisible(final Level level, final Vec3 from, final Vec3 to, final boolean fluidObscures) {
        if (level == null) return false;
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER,
            fluidObscures ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE,
            (net.minecraft.world.entity.Entity) null);
        HitResult hit = level.clip(ctx);
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }
}
