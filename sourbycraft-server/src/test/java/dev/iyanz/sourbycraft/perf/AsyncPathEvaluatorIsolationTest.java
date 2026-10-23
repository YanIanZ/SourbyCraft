package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * ENG-2: building the async solver must not replace the navigation's live node evaluator (whose
 * door, float and fence flags the mob's goals set), and the solver's own evaluator must carry those
 * flags. Runs the real createPathFinder of the navigation.
 *
 * <p>ENG-8 follow-up (Creaking): {@code HomeNodeEvaluator} bounds the walk by the Creaking's home
 * position, which is synched entity data. The isolated solver built on the owner at submission
 * captures it; only a solve prepared with a {@link MobPathSnapshot} reads that capture, and every
 * other call on the live evaluator (the synchronous solve and owner-thread {@code getPathType}
 * queries such as {@code MoveControl.isWalkable}) reads the live home. The Creaking tests run real
 * A* solves over a flat stone floor at y&lt;=63 (the same stub world as {@link MobPathSnapshotTest}).</p>
 */
@org.bukkit.support.environment.VanillaFeature
class AsyncPathEvaluatorIsolationTest {

    private static final int FLOOR_Y = 63;
    /** Where the solving mob stands. */
    private static final BlockPos MOB_POS = new BlockPos(0, FLOOR_Y + 1, 0);
    /** Six blocks east of the mob, on the floor. */
    private static final BlockPos TARGET = new BlockPos(6, FLOOR_Y + 1, 0);
    /**
     * A home 30 blocks west: TARGET is 36 blocks from it (36^2 = 1296 &gt; 1024) and farther from it
     * than the mob is, so HomeNodeEvaluator blocks it; with no home the target is reachable.
     */
    private static final BlockPos FAR_HOME = new BlockPos(-30, FLOOR_Y + 1, 0);

    private static ExecutorService worker;

    @BeforeAll
    static void startWorker() {
        // DedicatedServer fills the per-state path-type byte at server init; the test bootstrap does not.
        BlockBehaviour.BlockStateBase.canvas$initPathTypeCache();
        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "AsyncPathEvaluatorIsolationTest-worker"));
    }

    @AfterAll
    static void stopWorker() {
        worker.shutdownNow();
    }

    private static Field field(final Class<?> owner, final String name) throws NoSuchFieldException {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static NodeEvaluator evaluatorOf(final PathFinder finder) throws Exception {
        for (final Field candidate : PathFinder.class.getDeclaredFields()) {
            if (NodeEvaluator.class.isAssignableFrom(candidate.getType())) {
                candidate.setAccessible(true);
                return (NodeEvaluator) candidate.get(finder);
            }
        }
        throw new AssertionError("PathFinder has no NodeEvaluator field");
    }

    @Test
    void theLiveEvaluatorAndItsFlagsSurviveAndTheSolverGetsACopyOfThem() throws Exception {
        final GroundPathNavigation navigation = mock(GroundPathNavigation.class, CALLS_REAL_METHODS);
        final WalkNodeEvaluator live = new WalkNodeEvaluator();
        live.setCanOpenDoors(true);
        live.setCanPassDoors(false);
        live.setCanFloat(true);
        live.setCanWalkOverFences(true);
        field(PathNavigation.class, "nodeEvaluator").set(navigation, live);

        final PathFinder solver = navigation.sourbyIsolatedPathFinder(256);

        assertSame(live, field(PathNavigation.class, "nodeEvaluator").get(navigation),
            "the mob keeps its live evaluator; setCanOpenDoors and the sync path still use it");
        assertTrue(live.canOpenDoors() && live.canFloat() && live.canWalkOverFences() && !live.canPassDoors());
        final NodeEvaluator isolated = evaluatorOf(solver);
        assertNotSame(live, isolated, "the worker never shares the region thread's evaluator");
        assertTrue(isolated.canOpenDoors(), "a fresh WalkNodeEvaluator defaults this to false");
        assertFalse(isolated.canPassDoors(), "a fresh evaluator defaults this to true");
        assertTrue(isolated.canFloat());
        assertTrue(isolated.canWalkOverFences());
    }

    @Test
    void aNavigationThatSetsAFlagInCreatePathFinderGetsTheLiveValue() throws Exception {
        // WaterBoundPathNavigation.createPathFinder sets canPassDoors(false) itself; the live value wins.
        final WaterBoundPathNavigation navigation = mock(WaterBoundPathNavigation.class, CALLS_REAL_METHODS);
        final Field mob = field(PathNavigation.class, "mob");
        mob.set(navigation, mock(net.minecraft.world.entity.Mob.class));
        final NodeEvaluator live = new net.minecraft.world.level.pathfinder.SwimNodeEvaluator(false);
        live.setCanPassDoors(false);
        live.setCanFloat(true);
        field(PathNavigation.class, "nodeEvaluator").set(navigation, live);

        final PathFinder solver = navigation.sourbyIsolatedPathFinder(64);

        assertSame(live, field(PathNavigation.class, "nodeEvaluator").get(navigation));
        assertFalse(evaluatorOf(solver).canPassDoors());
        assertTrue(evaluatorOf(solver).canFloat());
    }

    // ---- ENG-8 follow-up: the Creaking home position ----

    /** A Creaking whose only used member is getHomePos; constructors and synched data never run. */
    private static Creaking creakingWithHome(final BlockPos home) {
        final Creaking creaking = mock(Creaking.class);
        doReturn(home).when(creaking).getHomePos();
        return creaking;
    }

    /**
     * The real CreakingPathNavigation. It is a non-static inner class, so its constructor is
     * (Creaking this$0, Creaking mob, Level level). Construction runs createPathFinder once, on this
     * thread, which builds the live HomeNodeEvaluator.
     */
    private static PathNavigation creakingNavigation(final Creaking creaking) throws Exception {
        for (final Class<?> inner : Creaking.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("CreakingPathNavigation")) {
                final Constructor<?> constructor = inner.getDeclaredConstructor(Creaking.class, Creaking.class, Level.class);
                constructor.setAccessible(true);
                final PathNavigation navigation = (PathNavigation) constructor.newInstance(creaking, creaking, mock(Level.class));
                // The mocked FOLLOW_RANGE is 0, which would leave the live solver 0 nodes to visit.
                navigation.pathFinder.setMaxVisitedNodes(512);
                return navigation;
            }
        }
        throw new AssertionError("no inner class CreakingPathNavigation in Creaking");
    }

    private static BlockPos capturedHome(final NodeEvaluator evaluator) throws Exception {
        return (BlockPos) field(evaluator.getClass(), "sourbyHomePos").get(evaluator);
    }

    private static BlockState blockAt(final BlockPos pos) {
        return pos.getY() <= FLOOR_Y ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    private static PathNavigationRegion flatWorld() {
        final PathNavigationRegion region = mock(PathNavigationRegion.class);
        when(region.getBlockState(any(BlockPos.class))).thenAnswer(inv -> blockAt(inv.getArgument(0)));
        when(region.getBlockStateIfLoaded(any(BlockPos.class))).thenAnswer(inv -> blockAt(inv.getArgument(0)));
        when(region.getFluidState(any(BlockPos.class))).thenAnswer(inv -> blockAt(inv.getArgument(0)).getFluidState());
        when(region.getFluidIfLoaded(any(BlockPos.class))).thenAnswer(inv -> blockAt(inv.getArgument(0)).getFluidState());
        when(region.getMinY()).thenReturn(-64);
        when(region.getHeight()).thenReturn(384);
        when(region.getWorldBorder()).thenReturn(new WorldBorder());
        when(region.getEntityCollisions(nullable(Entity.class), any(AABB.class))).thenReturn(List.of());
        when(region.getBlockCollisionsFromContext(any(), any(AABB.class))).thenReturn(List.<VoxelShape>of());
        when(region.noCollision(nullable(Entity.class), any(AABB.class))).thenReturn(true);
        return region;
    }

    /** A 0.6 x 1.8 walker standing at MOB_POS; the solve reads it (sync) or its snapshot (async). */
    private static Mob walker() {
        final Level level = mock(Level.class);
        when(level.getSeaLevel()).thenReturn(FLOOR_Y);
        final RandomSource random = RandomSource.create(42L);
        final double x = MOB_POS.getX() + 0.5, y = MOB_POS.getY(), z = MOB_POS.getZ() + 0.5;
        return mock(Mob.class, withSettings().defaultAnswer((InvocationOnMock invocation) -> {
            final String name = invocation.getMethod().getName();
            return switch (name) {
                case "getX" -> x;
                case "getY" -> y;
                case "getZ" -> z;
                case "getBlockY" -> MOB_POS.getY();
                case "blockPosition" -> MOB_POS;
                case "getBoundingBox" -> new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
                case "getBbWidth" -> 0.6F;
                case "getBbHeight" -> 1.8F;
                case "isInWater", "onGround", "isDescending", "canStandOnFluid" -> name.equals("onGround");
                case "maxUpStep" -> 0.6F;
                case "getMaxFallDistance" -> 3;
                case "level" -> level;
                case "getRandom" -> random;
                case "getType" -> net.minecraft.world.entity.EntityTypes.ZOMBIE;
                case "getMainHandItem" -> ItemStack.EMPTY;
                case "getPathfindingMalus" -> {
                    final PathType type = invocation.getArgument(0);
                    yield type == PathType.WALKABLE ? 0.0F : type.getMalus();
                }
                case "onPathfindingStart", "onPathfindingDone" -> null;
                case "toString" -> "walker";
                case "hashCode" -> 7;
                case "equals" -> false;
                default -> throw new AssertionError("unexpected mob call: " + name);
            };
        }));
    }

    private static Path solveOnWorker(final PathFinder solver, final PathNavigationRegion region,
                                      final MobPathSnapshot subject) throws Exception {
        return worker.submit(() -> solver.findPath(region, subject, Set.of(TARGET), 32.0F, 0, 1.0F))
            .get(10, TimeUnit.SECONDS);
    }

    private static Path solveOnOwner(final PathNavigation navigation, final PathNavigationRegion region, final Mob mob) {
        return navigation.pathFinder.findPath(region, mob, Set.of(TARGET), 32.0F, 0, 1.0F);
    }

    @Test
    void creakingAllowsTheOffload() throws Exception {
        final Creaking creaking = creakingWithHome(null);
        final PathNavigation navigation = creakingNavigation(creaking);
        assertFalse(AsyncPathRecompute.solveWritesMob(navigation, creaking), "the creaking offload is no longer refused");
    }

    @Test
    void eachIsolatedSolverCapturesTheHomeAtSubmissionAndLeavesTheLiveEvaluatorAlone() throws Exception {
        final Creaking creaking = creakingWithHome(null);
        final PathNavigation navigation = creakingNavigation(creaking);
        final NodeEvaluator live = navigation.getNodeEvaluator();
        assertSame(live, evaluatorOf(navigation.pathFinder));

        // The heart assigns a home after the navigation exists; the next submission must see it.
        final BlockPos first = new BlockPos(5, FLOOR_Y + 1, 5);
        doReturn(first).when(creaking).getHomePos();
        final NodeEvaluator firstSolver = evaluatorOf(navigation.sourbyIsolatedPathFinder(512));

        final BlockPos second = new BlockPos(-9, FLOOR_Y + 1, 2);
        doReturn(second).when(creaking).getHomePos();
        final NodeEvaluator secondSolver = evaluatorOf(navigation.sourbyIsolatedPathFinder(512));

        assertEquals(first, capturedHome(firstSolver), "the first submission captured the home it saw");
        assertEquals(second, capturedHome(secondSolver), "a later submission captures the changed home");
        assertNotSame(firstSolver, secondSolver, "one fresh evaluator per submission");
        assertNotSame(live, firstSolver);
        assertNotSame(live, secondSolver);
        assertSame(live, navigation.getNodeEvaluator(), "building a solver does not replace the live evaluator");
        assertSame(live, evaluatorOf(navigation.pathFinder), "the synchronous PathFinder still solves with it");
        assertNull(capturedHome(live), "building a solver writes nothing onto the live evaluator");
    }

    @Test
    void aSnapshotSolveOnAWorkerUsesTheHomeCapturedAtSubmissionNeverTheLiveOne() throws Exception {
        final Creaking creaking = creakingWithHome(null);
        final PathNavigation navigation = creakingNavigation(creaking);
        final PathNavigationRegion region = flatWorld();
        final Mob mob = walker();

        // Two submissions as AsyncPathRecompute.offload makes them on the owner: solver,
        // then the mob snapshot. One before the creaking has a home, one after it gets a far one.
        final PathFinder homeless = navigation.sourbyIsolatedPathFinder(512);
        final MobPathSnapshot homelessSubject = MobPathSnapshot.captureDetached(mob);
        doReturn(FAR_HOME).when(creaking).getHomePos();
        final PathFinder bounded = navigation.sourbyIsolatedPathFinder(512);
        final MobPathSnapshot boundedSubject = MobPathSnapshot.captureDetached(mob);

        // From here a live read is wrong on any thread: the home is synched entity data owned by the region.
        doAnswer(invocation -> {
            throw new AssertionError("live getHomePos() read after submission, on " + Thread.currentThread().getName());
        }).when(creaking).getHomePos();

        final Path free = solveOnWorker(homeless, region, homelessSubject);
        final Path fenced = solveOnWorker(bounded, region, boundedSubject);

        assertNotNull(free);
        assertTrue(free.canReach(), "captured with no home, the target is reachable");
        assertNotNull(fenced);
        assertFalse(fenced.canReach(), "captured with the far home, the walk stops 32 blocks from it");
    }

    @Test
    void theSynchronousSolveReadsTheLiveHome() throws Exception {
        final Creaking creaking = creakingWithHome(null);
        final PathNavigation navigation = creakingNavigation(creaking);
        final PathNavigationRegion region = flatWorld();
        final Mob mob = walker();

        // The live evaluator was built (and captured null) at construction; the solve must not use that.
        doReturn(FAR_HOME).when(creaking).getHomePos();
        assertFalse(solveOnOwner(navigation, region, mob).canReach(), "the far home set after construction applies");

        doReturn(null).when(creaking).getHomePos();
        assertTrue(solveOnOwner(navigation, region, mob).canReach(), "clearing the home applies on the next solve");
    }

    /**
     * Regression for the first version of this change, which picked the snapshot whenever the
     * evaluator had no mob. NodeEvaluator.done() clears the mob on the LIVE evaluator after every
     * synchronous solve, and owner-thread queries (MoveControl.isWalkable through
     * getPathType(Mob, BlockPos), which delegates to this overload) call getPathType without
     * preparing, so they read the construction-time capture instead of the current home.
     */
    @Test
    void anUnpreparedOwnerThreadQueryOnTheLiveEvaluatorSeesTheCurrentHome() throws Exception {
        final Creaking creaking = creakingWithHome(null);
        final NodeEvaluator live = creakingNavigation(creaking).getNodeEvaluator();
        final PathfindingContext context = new PathfindingContext(flatWorld(), MOB_POS);

        // The live evaluator captured null at construction and was never prepared; the home the heart
        // assigns afterwards must bound the query.
        doReturn(FAR_HOME).when(creaking).getHomePos();
        assertEquals(PathType.BLOCKED, live.getPathType(context, TARGET.getX(), TARGET.getY(), TARGET.getZ()),
            "an unprepared query reads the current home, not the null captured at construction");

        doReturn(null).when(creaking).getHomePos();
        assertNotEquals(PathType.BLOCKED, live.getPathType(context, TARGET.getX(), TARGET.getY(), TARGET.getZ()),
            "and clearing the home un-blocks the same node");
    }

    @Test
    void anOwnerThreadQueryAfterBothKindsOfSolveSeesTheCurrentHome() throws Exception {
        final BlockPos firstHome = new BlockPos(2, FLOOR_Y + 1, 0);
        final Creaking creaking = creakingWithHome(firstHome);
        final PathNavigation navigation = creakingNavigation(creaking);
        final NodeEvaluator live = navigation.getNodeEvaluator();
        final PathNavigationRegion region = flatWorld();
        final PathfindingContext context = new PathfindingContext(region, MOB_POS);

        // A synchronous solve on the live evaluator (done() clears its mob) and a snapshot solve on an
        // isolated one, both while the home is near and does not block TARGET.
        assertTrue(solveOnOwner(navigation, region, walker()).canReach());
        assertTrue(solveOnWorker(navigation.sourbyIsolatedPathFinder(512), region,
            MobPathSnapshot.captureDetached(walker())).canReach());

        doReturn(FAR_HOME).when(creaking).getHomePos();
        assertEquals(PathType.BLOCKED, live.getPathType(context, TARGET.getX(), TARGET.getY(), TARGET.getZ()),
            "after done() the live evaluator reads the current home, not the one captured at construction");
    }
}
