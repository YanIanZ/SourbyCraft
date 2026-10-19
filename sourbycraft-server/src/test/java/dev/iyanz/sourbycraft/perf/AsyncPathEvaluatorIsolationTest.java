package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.Test;

/**
 * ENG-2: building the async solver must not replace the navigation's live node evaluator (whose
 * door, float and fence flags the mob's goals set), and the solver's own evaluator must carry those
 * flags. Runs the real createPathFinder of the navigation; the mob and level are not needed.
 */
@org.bukkit.support.environment.Normal
class AsyncPathEvaluatorIsolationTest {

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
}
