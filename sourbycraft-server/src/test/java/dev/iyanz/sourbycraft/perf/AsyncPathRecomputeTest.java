package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

/**
 * The offload logic that minecraft feature patch 0006 used to carry inside {@code PathNavigation} and
 * that now lives in {@link AsyncPathRecompute}: the refusal list (formerly per-class overrides), the
 * owner-thread delivery checks, and the live-evaluator restore. Runs the real navigation methods on
 * Mockito instances ({@code CALLS_REAL_METHODS}, no world).
 */
@org.bukkit.support.environment.VanillaFeature
class AsyncPathRecomputeTest {

    private static final BlockPos TARGET = new BlockPos(4, 64, 0);

    private static Path path(final int x) {
        return new Path(List.of(new Node(0, 64, 0), new Node(x, 64, 0)), new BlockPos(x, 64, 0), true);
    }

    private static void setPath(final PathNavigation navigation, final Path path) throws Exception {
        final Field field = PathNavigation.class.getDeclaredField("path");
        field.setAccessible(true);
        field.set(navigation, path);
    }

    private static Object reachRange(final PathNavigation navigation) throws Exception {
        final Field field = PathNavigation.class.getDeclaredField("reachRange");
        field.setAccessible(true);
        return field.get(navigation);
    }

    @Test
    void navigationsAndMobsWhoseSolveWritesTheMobStaySynchronous() {
        final Mob plain = mock(Zombie.class);
        // AmphibiousPathNavigation (and the frog and turtle navigations that extend it) writes and
        // restores malus around the solve; Sniffer writes it from its pathfinding callbacks.
        assertTrue(AsyncPathRecompute.solveWritesMob(mock(AmphibiousPathNavigation.class), plain));
        assertTrue(AsyncPathRecompute.solveWritesMob(mock(GroundPathNavigation.class), mock(Sniffer.class)));
        // Every other navigation/mob pair may offload (was: the default sourbyAsyncSolveSafe = true,
        // sourbyPathfindingMutatesMob = false).
        assertFalse(AsyncPathRecompute.solveWritesMob(mock(GroundPathNavigation.class), plain));
        assertFalse(AsyncPathRecompute.solveWritesMob(mock(FlyingPathNavigation.class), plain));
        assertFalse(AsyncPathRecompute.solveWritesMob(mock(WaterBoundPathNavigation.class), plain));
    }

    @Test
    void anAcceptedResultIsAppliedAndAdvancesTheGeneration() throws Exception {
        final PathNavigation navigation = mock(GroundPathNavigation.class, CALLS_REAL_METHODS);
        final Mob mob = mock(Zombie.class);
        final Path requested = path(2);
        setPath(navigation, requested);
        final int generation = navigation.sourbyNavigationGeneration();
        final Path solved = path(4);

        AsyncPathRecompute.deliver(navigation, mob, mob, requested, generation, solved, 3);

        assertSame(solved, navigation.getPath());
        assertEquals(solved.getTarget(), navigation.getTargetPos());
        assertEquals(3, reachRange(navigation));
        assertEquals(generation + 1, navigation.sourbyNavigationGeneration(), "an applied delivery invalidates later ones");
    }

    @Test
    void aReplacedEntityAStalePathOrAnInvalidationDiscardsTheResult() throws Exception {
        final Mob mob = mock(Zombie.class);
        final Path requested = path(2);
        final Path solved = path(4);

        // Dimension transfer: the owner hands back another entity object.
        PathNavigation navigation = mock(GroundPathNavigation.class, CALLS_REAL_METHODS);
        setPath(navigation, requested);
        AsyncPathRecompute.deliver(navigation, mob, mock(Zombie.class), requested, navigation.sourbyNavigationGeneration(), solved, 3);
        assertSame(requested, navigation.getPath(), "subject check");

        // The path was replaced while solving.
        navigation = mock(GroundPathNavigation.class, CALLS_REAL_METHODS);
        final Path newer = path(3);
        setPath(navigation, newer);
        AsyncPathRecompute.deliver(navigation, mob, mob, requested, navigation.sourbyNavigationGeneration(), solved, 3);
        assertSame(newer, navigation.getPath(), "path-token check");

        // stop() on an already-null path: identity cannot see it, the generation does.
        navigation = mock(GroundPathNavigation.class, CALLS_REAL_METHODS);
        final int before = navigation.sourbyNavigationGeneration();
        navigation.stop();
        AsyncPathRecompute.deliver(navigation, mob, mob, null, before, solved, 3);
        assertNull(navigation.getPath(), "generation check");

        // No route found, or a route without a target: nothing is applied.
        navigation = mock(GroundPathNavigation.class, CALLS_REAL_METHODS);
        setPath(navigation, requested);
        final int generation = navigation.sourbyNavigationGeneration();
        AsyncPathRecompute.deliver(navigation, mob, mob, requested, generation, null, 3);
        assertSame(requested, navigation.getPath(), "content check");
        assertEquals(generation, navigation.sourbyNavigationGeneration());
    }

    @Test
    void restoringTheLiveEvaluatorCopiesItsFlagsOntoTheIsolatedOne() {
        final WalkNodeEvaluator live = new WalkNodeEvaluator();
        live.setCanOpenDoors(true);
        live.setCanPassDoors(false);
        live.setCanFloat(true);
        live.setCanWalkOverFences(true);
        final WalkNodeEvaluator isolated = new WalkNodeEvaluator();

        assertSame(live, AsyncPathRecompute.restoreLiveEvaluator(live, isolated));
        assertTrue(isolated.canOpenDoors());
        assertFalse(isolated.canPassDoors());
        assertTrue(isolated.canFloat());
        assertTrue(isolated.canWalkOverFences());

        // Nothing to copy when createPathFinder did not replace the evaluator, or there was none.
        assertSame(live, AsyncPathRecompute.restoreLiveEvaluator(live, live));
        assertNull(AsyncPathRecompute.restoreLiveEvaluator(null, isolated));
    }
}
