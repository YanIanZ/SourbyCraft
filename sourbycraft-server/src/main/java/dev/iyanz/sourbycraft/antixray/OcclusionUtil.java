package dev.iyanz.sourbycraft.antixray;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Line-of-sight check between two world positions, using a
 * <em>see-through-aware</em> occluder test rather than the physics
 * collision shape.
 *
 * <p><b>Why not {@code Level#clip(COLLIDER)}:</b> the collision-shape ray
 * stops on ANY block whose collision box is non-empty — which includes
 * glass, tinted glass, iron bars, slabs, stairs, fences and leaves. A
 * player can genuinely <em>see through</em> those blocks, so treating them
 * as occluders both (a) blinds legit players to ores behind glass/leaves
 * (over-hide, bad UX) and (b) is the wrong physical model for xray. The
 * correct primitive is {@code BlockState#isViewBlocking}, which is true
 * exactly for solid full blocks that a suffocating player cannot see out
 * of (stone/dirt/ore/wood/wool/etc.) and false for every transparent /
 * partial / non-full block. Full-cube blocks like leaves that are still
 * see-through are also excluded because {@code isViewBlocking} is derived
 * from {@code isSuffocating} = {@code blocksMotion() && isCollisionShapeFullBlock},
 * and leaves/glass are flagged {@code noOcclusion()} at registration.
 *
 * <p>This walks the same DDA voxel path as vanilla {@code clip}
 * (via {@link BlockGetter#traverseBlocks}) so the cost is identical to the
 * old {@code Level#clip} call — one traversal bounded by the ray length,
 * no allocation beyond a mutable {@link BlockPos}. It is a pure block
 * read, region-thread safe for the callers documented on
 * {@link EntityVisibilityCheck} / {@link ParticleVisibilityCheck} /
 * {@link RayTraceWorker}.
 *
 * <p>Used by {@link VisibilityCache} + {@link RayTraceWorker} to decide
 * whether an ore at {@code target} is currently visible from the
 * {@code from} eye position; if not, the ore stays obfuscated through the
 * ore-reveal layer over Paper's engine anti-xray.
 */
public final class OcclusionUtil {

    private OcclusionUtil() {}

    /** Shared "ray blocked" sentinel — see traversal lambda. Never inspected beyond null-ness. */
    private static final BlockHitResult BLOCKED =
        BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO);

    /**
     * Returns {@code true} when no <em>view-blocking</em> (solid full-cube,
     * suffocating) block sits strictly between {@code from} and {@code to}.
     * See-through blocks (glass/leaves/bars/slabs/stairs/fences/air) do NOT
     * occlude; a full solid block does. The endpoint block (the ore/target
     * itself) is never treated as its own occluder.
     */
    public static boolean isVisible(final Level level, final Vec3 from, final Vec3 to) {
        return isVisible(level, from, to, false);
    }

    /** @param fluidObscures when true a non-empty fluid column also blocks sight (water/lava hides ores). */
    public static boolean isVisible(final Level level, final Vec3 from, final Vec3 to, final boolean fluidObscures) {
        if (level == null) return false;
        if (from.equals(to)) return true;

        // Block containing the target endpoint — never let the ore/entity/particle block occlude itself.
        final BlockPos targetBlock = BlockPos.containing(to.x, to.y, to.z);

        final Context ctx = new Context(level, from, to, fluidObscures, targetBlock);
        final HitResult hit = BlockGetter.traverseBlocks(
            from, to, ctx,
            (c, pos) -> {
                // Skip the endpoint block: an ore is a full cube and would always self-occlude.
                if (pos.getX() == c.targetBlock.getX()
                    && pos.getY() == c.targetBlock.getY()
                    && pos.getZ() == c.targetBlock.getZ()) {
                    return null;
                }
                // NEVER Level.getBlockState here: rays run on async virtual threads, and the
                // vanilla path would sync-load (or NPE on) an unloaded chunk. Loaded-chunk-only
                // read; an unloaded chunk occludes (fail-safe: the ore stays hidden until a ray
                // through loaded terrain proves it visible). The last-chunk cache makes the
                // common same-chunk step free.
                final BlockState state = c.blockStateIfLoaded(pos);
                if (state == null || occludes(c.level, state, pos, c.fluidObscures)) {
                    // Any non-null return stops the traversal; callers only test null/non-null,
                    // so a shared sentinel avoids a BlockPos+BlockHitResult alloc per blocked ray.
                    return BLOCKED;
                }
                return null;
            },
            c -> null // reached the target with no occluder in the way -> visible
        );
        return hit == null;
    }

    /**
     * A block occludes sight when it is view-blocking (solid full cube the
     * player cannot see out of), or — with {@code fluidObscures} — when it
     * carries a non-empty fluid. Everything transparent/partial passes.
     */
    private static boolean occludes(final BlockGetter level, final BlockState state,
                                    final BlockPos pos, final boolean fluidObscures) {
        if (state.isViewBlocking(level, pos)) return true;
        if (fluidObscures) {
            final FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) return true;
        }
        return false;
    }

    /** Per-ray traversal context; caches the last chunk touched (rays cross chunks rarely per step). */
    private static final class Context {
        final Level level;
        final Vec3 from, to;
        final boolean fluidObscures;
        final BlockPos targetBlock;
        private net.minecraft.world.level.chunk.LevelChunk lastChunk;
        private int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;

        Context(Level level, Vec3 from, Vec3 to, boolean fluidObscures, BlockPos targetBlock) {
            this.level = level;
            this.from = from;
            this.to = to;
            this.fluidObscures = fluidObscures;
            this.targetBlock = targetBlock;
        }

        /** Block state via loaded-chunk lookup only; {@code null} when the chunk is not loaded. */
        BlockState blockStateIfLoaded(final BlockPos pos) {
            if (this.level.isOutsideBuildHeight(pos.getY())) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            final int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            net.minecraft.world.level.chunk.LevelChunk chunk = this.lastChunk;
            if (cx != this.lastCx || cz != this.lastCz) {
                chunk = this.level instanceof net.minecraft.server.level.ServerLevel sl
                    ? sl.getChunkIfLoaded(cx, cz) : null;
                this.lastChunk = chunk;
                this.lastCx = cx;
                this.lastCz = cz;
            }
            return chunk == null ? null : chunk.getBlockState(pos);
        }
    }
}
