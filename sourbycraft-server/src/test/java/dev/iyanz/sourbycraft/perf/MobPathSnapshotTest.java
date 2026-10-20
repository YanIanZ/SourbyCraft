package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * Audit ENG-8: the off-thread A* solve must read no live mob state. Every mob field the evaluators
 * read is captured in a {@link MobPathSnapshot} on the owner, and the solve is handed the snapshot
 * instead of the mob.
 *
 * <p>The mob here is a fake that records every call and, once <em>sealed</em>, fails the test on
 * any further call from any thread (so it also fails on an owner-thread read made after submit).
 * The world is a flat stone floor at y&lt;=63 served by a stubbed {@link PathNavigationRegion}.</p>
 */
@org.bukkit.support.environment.VanillaFeature
class MobPathSnapshotTest {

    private static final int FLOOR_Y = 63;
    private static ExecutorService worker;

    @BeforeAll
    static void bootstrapPathTypes() {
        // DedicatedServer fills the per-state path-type byte at server init; the test bootstrap does not.
        BlockBehaviour.BlockStateBase.canvas$initPathTypeCache();
        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "MobPathSnapshotTest-worker"));
    }

    @AfterAll
    static void stopWorker() {
        worker.shutdownNow();
    }

    /** The mutable state behind the fake mob; mutate it to simulate the owner moving on. */
    private static final class MobState {
        double x = 0.5, y = FLOOR_Y + 1, z = 0.5;
        float width = 0.6F, height = 1.8F;
        boolean inWater, onGround = true, descending;
        float stepHeight = 0.6F;
        int maxFall = 3;
        float walkableMalus = 0.0F, waterMalus = 8.0F;
        boolean standsOnLava;
        RandomSource random = RandomSource.create(42L);
        final Level level;

        MobState() {
            this.level = mock(Level.class);
            when(this.level.getSeaLevel()).thenReturn(63);
        }

        AABB box() {
            final double half = this.width / 2.0;
            return new AABB(this.x - half, this.y, this.z - half, this.x + half, this.y + this.height, this.z + half);
        }
    }

    /** A {@link Mob} whose every call is answered from {@link MobState} and refused once sealed. */
    private static final class FakeMob {
        final MobState state = new MobState();
        final AtomicBoolean sealed = new AtomicBoolean();
        final List<String> calls = new ArrayList<>();
        final Mob mob;

        FakeMob() {
            this.mob = mock(Mob.class, withSettings().defaultAnswer(this::answer));
        }

        private Object answer(final InvocationOnMock invocation) {
            final String name = invocation.getMethod().getName();
            if (this.sealed.get()) {
                throw new AssertionError("live mob read after submit: " + name + " on " + Thread.currentThread().getName());
            }
            synchronized (this.calls) {
                this.calls.add(name);
            }
            final MobState s = this.state;
            return switch (name) {
                case "getX" -> s.x;
                case "getY" -> s.y;
                case "getZ" -> s.z;
                case "getBlockY" -> (int) Math.floor(s.y);
                case "blockPosition" -> BlockPos.containing(s.x, s.y, s.z);
                case "getBoundingBox" -> s.box();
                case "getBbWidth" -> s.width;
                case "getBbHeight" -> s.height;
                case "isInWater" -> s.inWater;
                case "onGround" -> s.onGround;
                case "isDescending" -> s.descending;
                case "maxUpStep" -> s.stepHeight;
                case "getMaxFallDistance" -> s.maxFall;
                case "level" -> s.level;
                case "getRandom" -> s.random;
                case "getType" -> net.minecraft.world.entity.EntityTypes.ZOMBIE;
                case "getMainHandItem" -> ItemStack.EMPTY;
                case "canStandOnFluid" -> s.standsOnLava && ((FluidState) invocation.getArgument(0)).is(FluidTags.LAVA);
                case "getPathfindingMalus" -> {
                    final PathType type = invocation.getArgument(0);
                    yield switch (type) {
                        case WALKABLE -> s.walkableMalus;
                        case WATER -> s.waterMalus;
                        default -> type.getMalus();
                    };
                }
                case "onPathfindingStart", "onPathfindingDone" -> null;
                case "toString" -> "FakeMob";
                case "hashCode" -> System.identityHashCode(this);
                case "equals" -> invocation.getArgument(0) == this.mob;
                default -> throw new AssertionError("the solve reached an unsnapshotted mob member: " + name);
            };
        }
    }

    private static BlockState blockAt(final BlockPos pos) {
        return pos.getY() <= FLOOR_Y ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    private static PathNavigationRegion flatWorld(final WorldBorder border) {
        final PathNavigationRegion region = mock(PathNavigationRegion.class);
        when(region.getBlockState(any(BlockPos.class))).thenAnswer(inv -> blockAt(inv.getArgument(0)));
        when(region.getBlockStateIfLoaded(any(BlockPos.class))).thenAnswer(inv -> blockAt(inv.getArgument(0)));
        when(region.getFluidState(any(BlockPos.class))).thenAnswer(inv -> blockAt(inv.getArgument(0)).getFluidState());
        when(region.getFluidIfLoaded(any(BlockPos.class))).thenAnswer(inv -> blockAt(inv.getArgument(0)).getFluidState());
        when(region.getMinY()).thenReturn(-64);
        when(region.getHeight()).thenReturn(384);
        when(region.getWorldBorder()).thenReturn(border);
        when(region.getEntityCollisions(nullable(Entity.class), any(AABB.class))).thenReturn(List.of());
        when(region.getBlockCollisionsFromContext(any(), any(AABB.class))).thenReturn(List.<VoxelShape>of());
        when(region.noCollision(nullable(Entity.class), any(AABB.class))).thenReturn(true);
        return region;
    }

    private static PathFinder newSolver() {
        return new PathFinder(new WalkNodeEvaluator(), 512);
    }

    private static Path solveOnWorker(final PathFinder solver, final PathNavigationRegion region,
                                      final MobPathSnapshot subject, final BlockPos target) throws Exception {
        final Future<Path> future = worker.submit(
            () -> solver.findPath(region, subject, Set.of(target), 32.0F, 0, 1.0F));
        return future.get(10, TimeUnit.SECONDS);
    }

    private static List<String> nodes(final Path path) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < path.getNodeCount(); i++) {
            out.add(path.getNode(i).toString() + ":" + path.getNode(i).type);
        }
        return out;
    }

    @Test
    void aDetachedSnapshotCarriesTheEntityTypeKeyAndOnlyAfterTheCaptureSucceeded() {
        AsyncPathTypeStats.resetForTest();
        try {
            final MobPathSnapshot snapshot = MobPathSnapshot.captureDetached(new FakeMob().mob);
            assertEquals("minecraft:zombie", snapshot.entityTypeKey());
            assertEquals("minecraft:zombie", AsyncPathTypeStats.takeSubject(), "fallback hand-off for the unchanged patch");
            // the synchronous capture runs on the owner and never submits: no type, no hand-off
            assertEquals(AsyncPathTypeStats.UNKNOWN, MobPathSnapshot.capture(new FakeMob().mob).entityTypeKey());
            assertEquals(AsyncPathTypeStats.UNKNOWN, AsyncPathTypeStats.takeSubject());
        } finally {
            AsyncPathTypeStats.resetForTest();
        }
    }

    @Test
    void aCaptureThatThrowsLeavesNoStaleTypeAndClearsAnAbandonedOne() {
        AsyncPathTypeStats.resetForTest();
        try {
            AsyncPathTypeStats.noteSubject("minecraft:abandoned"); // an earlier capture never submitted
            final FakeMob sealed = new FakeMob();
            sealed.sealed.set(true);                                // every mob read now throws
            assertThrows(AssertionError.class, () -> MobPathSnapshot.captureDetached(sealed.mob));
            assertEquals(AsyncPathTypeStats.UNKNOWN, AsyncPathTypeStats.takeSubject(),
                "neither the abandoned type nor the failed capture's may reach the next submit");
        } finally {
            AsyncPathTypeStats.resetForTest();
        }
    }

    @Test
    void anExplicitSnapshotSubmitIsAttributedToItsOwnTypeWhateverIsAmbient() throws Exception {
        AsyncPathTypeStats.resetForTest();
        AsyncPathProcessor.shutdown();
        try {
            AsyncPathProcessor.setEnabled(true);
            final MobPathSnapshot a = MobPathSnapshot.captureDetached(new FakeMob().mob);
            AsyncPathTypeStats.noteSubject("minecraft:intruder"); // ambient state from something else
            final var done = AsyncPathProcessor.trySubmit(a, () -> "ok");
            assertEquals("ok", done.get(5, java.util.concurrent.TimeUnit.SECONDS));
            final var rows = AsyncPathTypeStats.top(100);
            assertEquals(1, rows.stream().filter(r -> r.type().equals("minecraft:zombie") && r.admitted() == 1).count());
            assertEquals(0, rows.stream().filter(r -> r.type().equals("minecraft:intruder")).count());
            assertEquals(AsyncPathTypeStats.UNKNOWN, AsyncPathTypeStats.takeSubject(), "the ambient hand-off was cleared");
        } finally {
            AsyncPathProcessor.shutdown();
            AsyncPathTypeStats.resetForTest();
        }
    }

    @Test
    void nestedCapturesAreNeverAttributedToTheWrongSubmit() throws Exception {
        AsyncPathTypeStats.resetForTest();
        AsyncPathProcessor.shutdown();
        try {
            AsyncPathProcessor.setEnabled(true);
            MobPathSnapshot.captureDetached(new FakeMob().mob);   // outer capture ...
            MobPathSnapshot.captureDetached(new FakeMob().mob);   // ... re-entered before its submit
            AsyncPathProcessor.trySubmit(() -> 1).get(5, java.util.concurrent.TimeUnit.SECONDS);
            AsyncPathProcessor.trySubmit(() -> 2).get(5, java.util.concurrent.TimeUnit.SECONDS);
            final var rows = AsyncPathTypeStats.top(100);
            assertEquals(1, rows.stream().filter(r -> r.type().equals("minecraft:zombie") && r.admitted() == 1).count());
            assertEquals(1, rows.stream().filter(r -> r.type().equals(AsyncPathTypeStats.UNKNOWN) && r.admitted() == 1).count(),
                "the second submit has no capture of its own: unknown, not the first one's type again");
        } finally {
            AsyncPathProcessor.shutdown();
            AsyncPathTypeStats.resetForTest();
        }
    }

    @Test
    void anOffThreadSolveNeverCallsTheMob() throws Exception {
        final FakeMob fake = new FakeMob();
        final PathNavigationRegion region = flatWorld(new WorldBorder());
        final BlockPos target = new BlockPos(6, FLOOR_Y + 1, 0);

        final MobPathSnapshot subject = MobPathSnapshot.captureDetached(fake.mob);
        final int callsAtCapture = fake.calls.size();
        assertTrue(callsAtCapture > 0, "capture reads the mob on the owner");
        fake.sealed.set(true); // from here any Mob call, on any thread, fails the test

        final Path path = solveOnWorker(newSolver(), region, subject, target);

        assertNotNull(path, "the solve completes against the snapshot alone");
        assertTrue(path.canReach(), "a flat floor reaches the target");
        assertEquals(target, path.getTarget());
        assertEquals(callsAtCapture, fake.calls.size(), "no call reached the mob after the snapshot was taken");
    }

    @Test
    void theResultIsUnchangedWhenTheMobMutatesAfterSubmit() throws Exception {
        final FakeMob fake = new FakeMob();
        final PathNavigationRegion region = flatWorld(new WorldBorder());
        final BlockPos target = new BlockPos(6, FLOOR_Y + 1, 0);

        final MobPathSnapshot subject = MobPathSnapshot.captureDetached(fake.mob);
        final List<String> expected = nodes(solveOnWorker(newSolver(), region, subject, target));

        // The owner moves on while the solve is queued: position, size and the malus table all change.
        fake.state.x = 20.5;
        fake.state.z = -9.5;
        fake.state.width = 2.5F;
        fake.state.walkableMalus = -1.0F; // every walkable node now refused -- if read live, no path

        assertEquals(expected, nodes(solveOnWorker(newSolver(), region, subject, target)),
            "the queued solve still describes the mob as it was at submit");
        assertEquals(0.0F, subject.getPathfindingMalus(PathType.WALKABLE));
        assertEquals(8.0F, subject.getPathfindingMalus(PathType.WATER));
        assertEquals(0.5, subject.getX());

        // Sensitivity check: a fresh capture of the mutated mob does give a different answer.
        final Path afterMutation = solveOnWorker(newSolver(), region, MobPathSnapshot.captureDetached(fake.mob), target);
        assertTrue(afterMutation == null || !nodes(afterMutation).equals(expected),
            "the test can tell a stale snapshot from a live read");
    }

    @Test
    void theSynchronousSolveSeesWhatVanillaWouldAndKeepsTheMobsRandom() {
        final FakeMob fake = new FakeMob();
        final PathNavigationRegion region = flatWorld(new WorldBorder());
        final BlockPos target = new BlockPos(6, FLOOR_Y + 1, 0);

        // Vanilla entry point: PathFinder.findPath(region, Mob, ...) on the owner thread.
        final Path live = newSolver().findPath(region, fake.mob, Set.of(target), 32.0F, 0, 1.0F);
        final Path snap = newSolver().findPath(region, MobPathSnapshot.capture(fake.mob), Set.of(target), 32.0F, 0, 1.0F);
        assertNotNull(live);
        assertNotNull(snap);
        assertEquals(nodes(live), nodes(snap), "the lazily captured snapshot yields vanilla's path");
        assertTrue(fake.calls.contains("onPathfindingStart") && fake.calls.contains("onPathfindingDone"),
            "the synchronous solve still runs the mob's pathfinding callbacks");

        // capture() keeps the mob's RandomSource (same draw sequence as vanilla);
        // captureDetached() forks one from exactly one draw made on the owner.
        assertSame(fake.state.random, MobPathSnapshot.capture(fake.mob).getRandom());
        final RandomSource twin = RandomSource.create(42L);
        fake.state.random = RandomSource.create(42L);
        final MobPathSnapshot detached = MobPathSnapshot.captureDetached(fake.mob);
        assertNotSame(fake.state.random, detached.getRandom());
        twin.nextLong();
        assertEquals(twin.nextLong(), fake.state.random.nextLong(), "one draw was taken from the mob's random");
        assertEquals(RandomSource.create(RandomSource.create(42L).nextLong()).nextInt(1000),
            detached.getRandom().nextInt(1000), "the fork is seeded from that draw");
    }

    @Test
    void canStandOnFluidIsTabulatedPerFluidTypeOnTheOwner() {
        final FakeMob strider = new FakeMob();
        strider.state.standsOnLava = true;
        final MobPathSnapshot subject = MobPathSnapshot.captureDetached(strider.mob);
        strider.sealed.set(true);

        assertTrue(subject.canStandOnFluid(Fluids.LAVA.defaultFluidState()));
        assertTrue(subject.canStandOnFluid(Fluids.FLOWING_LAVA.getFlowing(3, false)), "flowing lava shares the type");
        assertFalse(subject.canStandOnFluid(Fluids.WATER.defaultFluidState()));
        assertFalse(subject.canStandOnFluid(Fluids.EMPTY.defaultFluidState()));

        final MobPathSnapshot walker = MobPathSnapshot.captureDetached(new FakeMob().mob);
        assertFalse(walker.canStandOnFluid(Fluids.LAVA.defaultFluidState()));
    }

    @Test
    void noCollisionTestsTheBorderFromTheCapturedPosition() {
        final WorldBorder border = new WorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(10.0); // -5..5 on both axes
        final PathNavigationRegion region = flatWorld(border);

        final FakeMob inside = new FakeMob(); // at 0.5, 0.5: more than 2*bbMax from the border
        final MobPathSnapshot centre = MobPathSnapshot.captureDetached(inside.mob);
        inside.sealed.set(true);
        assertTrue(centre.noCollision(region, centre.getBoundingBox()));

        final FakeMob edge = new FakeMob();
        edge.state.x = 4.6; // close to the +x border; the box crosses it
        final MobPathSnapshot nearBorder = MobPathSnapshot.captureDetached(edge.mob);
        edge.sealed.set(true);
        final AABB crossing = nearBorder.getBoundingBox().move(0.7, 0.0, 0.0);
        assertFalse(nearBorder.noCollision(region, crossing), "a box across the border collides, read from the snapshot");
        assertTrue(nearBorder.noCollision(region, nearBorder.getBoundingBox().move(-1.0, 0.0, 0.0)));
        assertNotEquals(centre.getX(), nearBorder.getX());
    }
}
