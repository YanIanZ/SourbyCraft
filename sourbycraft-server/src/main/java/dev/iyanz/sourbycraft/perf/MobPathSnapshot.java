package dev.iyanz.sourbycraft.perf;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * SourbyCraft — every mob field the A* solve reads, captured on the owning region thread.
 *
 * <p>Audit ENG-8: the async path worker used to read the live {@link Mob} off its owner thread
 * ({@code WalkNodeEvaluator.getStart} and friends, {@code Mob.getPathfindingMalus} on a plain
 * {@code EnumMap}, {@code CollisionGetter.noCollision(mob, box)} building an {@code
 * EntityCollisionContext} from {@code isDescending()} and {@code getMainHandItem()}). Those reads
 * raced the region thread. The node evaluators now read only this snapshot during a solve; the
 * off-thread solve never holds a {@code Mob} reference at all ({@code
 * NodeEvaluator.prepare(PathNavigationRegion, MobPathSnapshot)}).</p>
 *
 * <p>The synchronous solve uses the same snapshot, captured lazily on first use so that the whole
 * {@code prepare} chain (the {@code onPathfindingStart} callback and {@code AmphibiousNodeEvaluator}'s
 * malus writes) has run first. On the owner thread the values are exactly what vanilla would read
 * live, so the feature-off path is unchanged; only one member is not a plain value:</p>
 *
 * <ul>
 *   <li>{@link #random()} — {@link #capture} keeps the mob's own {@link RandomSource} (same object,
 *   same thread, same draw sequence as vanilla). {@link #captureDetached} forks one from a seed drawn
 *   on the owner, so the worker never touches the mob's RNG, which is not thread-safe.</li>
 * </ul>
 *
 * <p>Two things are approximations, both documented in {@code docs/async-pathfinding-spec.md}:
 * {@code canStandOnFluid} is tabulated per registered fluid type from the mob's answer for that
 * fluid's default state (exact for the vanilla overrides, which test a fluid tag), and the collision
 * context carries no entity, so {@code PowderSnowBlock} and {@code LiquidBlock} shapes resolve as for
 * an entity-less context. The synchronous solve does not use the snapshot for collision at all; it
 * still calls {@code noCollision(mob, box)} with the live mob on the owner.</p>
 *
 * <p>Immutable: every field is a value, an immutable Minecraft type ({@link BlockPos}, {@link AABB}),
 * a private copy ({@link ItemStack}), or the forked random owned by one solve.</p>
 */
public final class MobPathSnapshot {

    private final double x;
    private final double y;
    private final double z;
    private final int blockY;
    private final BlockPos blockPosition;
    private final AABB boundingBox;
    private final float bbWidth;
    private final float bbHeight;
    private final boolean inWater;
    private final boolean onGround;
    private final boolean descending;
    private final float maxUpStep;
    private final int maxFallDistance;
    private final int seaLevel;
    private final float[] pathfindingMalus;
    private final boolean[] standsOnFluid;
    private final ItemStack heldItem;
    private final RandomSource random;
    private final EntityCollisionContext collisionContext;

    private MobPathSnapshot(final double x, final double y, final double z, final int blockY,
                            final BlockPos blockPosition, final AABB boundingBox,
                            final float bbWidth, final float bbHeight,
                            final boolean inWater, final boolean onGround, final boolean descending,
                            final float maxUpStep, final int maxFallDistance, final int seaLevel,
                            final float[] pathfindingMalus, final boolean[] standsOnFluid,
                            final ItemStack heldItem, final RandomSource random) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockY = blockY;
        this.blockPosition = blockPosition;
        this.boundingBox = boundingBox;
        this.bbWidth = bbWidth;
        this.bbHeight = bbHeight;
        this.inWater = inWater;
        this.onGround = onGround;
        this.descending = descending;
        this.maxUpStep = maxUpStep;
        this.maxFallDistance = maxFallDistance;
        this.seaLevel = seaLevel;
        this.pathfindingMalus = pathfindingMalus;
        this.standsOnFluid = standsOnFluid;
        this.heldItem = heldItem;
        this.random = random;
        this.collisionContext = new SnapshotCollisionContext();
    }

    /**
     * Captures the mob for a solve on the thread that owns it. The snapshot shares the mob's
     * {@link RandomSource}, so the draw sequence is vanilla's; use {@link #captureDetached} for a
     * solve that leaves this thread.
     */
    public static MobPathSnapshot capture(final Mob mob) {
        return capture(mob, mob.getRandom());
    }

    /**
     * Captures the mob for a solve on another thread. The random is forked from one draw made here,
     * so the worker never touches the mob's own, non-thread-safe source.
     */
    public static MobPathSnapshot captureDetached(final Mob mob) {
        return capture(mob, RandomSource.create(mob.getRandom().nextLong()));
    }

    private static MobPathSnapshot capture(final Mob mob, final RandomSource random) {
        final PathType[] types = PathType.values();
        final float[] malus = new float[types.length];
        for (final PathType type : types) {
            // Through the mob's own accessor so the controlled-vehicle inheritance
            // (Mob.getPathfindingMalus) is resolved here, on the owner.
            malus[type.ordinal()] = mob.getPathfindingMalus(type);
        }
        final List<Fluid> fluids = fluidTable();
        final boolean[] stands = new boolean[fluids.size()];
        for (int i = 0; i < stands.length; i++) {
            stands[i] = mob.canStandOnFluid(fluids.get(i).defaultFluidState());
        }
        return new MobPathSnapshot(
            mob.getX(), mob.getY(), mob.getZ(), mob.getBlockY(),
            mob.blockPosition(), mob.getBoundingBox(),
            mob.getBbWidth(), mob.getBbHeight(),
            mob.isInWater(), mob.onGround(), mob.isDescending(),
            mob.maxUpStep(), mob.getMaxFallDistance(), mob.level().getSeaLevel(),
            malus, stands,
            mob.getMainHandItem().copy(), random);
    }

    private static List<Fluid> fluidTable() {
        return BuiltInRegistries.FLUID.stream().toList();
    }

    // Accessors keep the Mob names so the evaluators read {@code this.subject().getX()} where they read
    // {@code this.mob.getX()}: the vanilla diff stays a receiver substitution.
    public double getX() { return this.x; }
    public double getY() { return this.y; }
    public double getZ() { return this.z; }
    public int getBlockY() { return this.blockY; }
    public BlockPos blockPosition() { return this.blockPosition; }
    public AABB getBoundingBox() { return this.boundingBox; }
    public float getBbWidth() { return this.bbWidth; }
    public float getBbHeight() { return this.bbHeight; }
    public boolean isInWater() { return this.inWater; }
    public boolean onGround() { return this.onGround; }
    public boolean isDescending() { return this.descending; }
    public float maxUpStep() { return this.maxUpStep; }
    public int getMaxFallDistance() { return this.maxFallDistance; }
    /** {@code mob.level().getSeaLevel()} at capture; {@code AmphibiousNodeEvaluator} reads it. */
    public int getSeaLevel() { return this.seaLevel; }
    /** The random for this solve: the mob's own after {@link #capture}, a fork after {@link #captureDetached}. */
    public RandomSource getRandom() { return this.random; }

    /** {@code Mob.getPathfindingMalus(type)} as resolved on the owner, including vehicle inheritance. */
    public float getPathfindingMalus(final PathType type) {
        return this.pathfindingMalus[type.ordinal()];
    }

    /** {@code Mob.canStandOnFluid} tabulated per fluid type; an unregistered fluid reads as not standable. */
    public boolean canStandOnFluid(final FluidState fluid) {
        final int id = BuiltInRegistries.FLUID.getId(fluid.getType());
        return id >= 0 && id < this.standsOnFluid.length && this.standsOnFluid[id];
    }

    /**
     * {@code CollisionGetter.noCollision(mob, box)} evaluated against the snapshot instead of the
     * live mob: block shapes through a snapshot-built collision context, entity collisions with no
     * source entity (a {@code PathNavigationRegion} reports none anyway), and the border test from
     * the captured position and box, as {@code WorldBorder.isInsideCloseToBorder} computes it.
     */
    public boolean noCollision(final CollisionGetter level, final AABB box) {
        for (final VoxelShape shape : level.getBlockCollisionsFromContext(this.collisionContext, box)) {
            if (!shape.isEmpty()) {
                return false;
            }
        }
        if (!level.getEntityCollisions(null, box).isEmpty()) {
            return false;
        }
        return this.noBorderCollision(level.getWorldBorder(), box);
    }

    private boolean noBorderCollision(final WorldBorder border, final AABB box) {
        final double bbMax = Math.max(absMax(box.getXsize(), box.getZsize()), 1.0);
        final boolean closeToBorder = border.getDistanceToBorder(this.x, this.z) < bbMax * 2.0
            && border.isWithinBounds(this.x, this.z, bbMax);
        if (!closeToBorder) {
            return true;
        }
        return !Shapes.joinIsNotEmpty(border.getCollisionShape(), Shapes.create(box), BooleanOp.AND);
    }

    private static double absMax(final double a, final double b) {
        return Math.max(Math.abs(a), Math.abs(b));
    }

    /** The collision context vanilla builds from the live entity, built from the snapshot instead. */
    private final class SnapshotCollisionContext extends EntityCollisionContext {
        SnapshotCollisionContext() {
            super(MobPathSnapshot.this.descending, false, MobPathSnapshot.this.y,
                MobPathSnapshot.this.heldItem, false, null);
        }

        @Override
        public boolean canStandOnFluid(final FluidState fluidStateAbove, final FluidState fluid) {
            return MobPathSnapshot.this.canStandOnFluid(fluid) && !fluidStateAbove.getType().isSame(fluid.getType());
        }

        /** No entity: block shapes that need one ({@code PowderSnowBlock}, {@code LiquidBlock}) resolve entity-less. */
        @Override
        public @Nullable Entity getEntity() {
            return null;
        }
    }
}
