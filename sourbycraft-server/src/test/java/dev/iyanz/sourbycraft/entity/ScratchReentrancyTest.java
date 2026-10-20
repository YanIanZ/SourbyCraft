package dev.iyanz.sourbycraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * DEV-A: the entity-owned scalar scratch state the feature patches keep (0009's
 * {@code lastKnownSpeed*} doubles, 0012's {@code isInWallScratch}) against re-entry.
 * Evidence table: {@code docs/architecture/scratch-reentrancy-proof.md}.
 *
 * <p>Two kinds of proof. The bytecode tests pin the call surface of the methods that write and
 * read the scratch between its write and its read: a method that can only call a field getter
 * cannot be re-entered through a callback, an event or a listener, whatever a subclass or plugin
 * does. The behavioural tests run the real methods on a bare {@link Entity}.</p>
 */
@org.bukkit.support.environment.VanillaFeature
class ScratchReentrancyTest {

    private static ClassNode entityClass() throws Exception {
        try (InputStream in = Entity.class.getResourceAsStream("Entity.class")) {
            assertNotNull(in, "Entity.class bytes");
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(final ClassNode owner, final String name, final String desc) {
        for (final MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        throw new AssertionError(owner.name + " has no " + name + desc);
    }

    /** Every call instruction in a method, as {@code owner.name desc}; an invokedynamic is reported too. */
    private static Set<String> calls(final MethodNode method) {
        final Set<String> calls = new TreeSet<>();
        for (final AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            if (insn instanceof InvokeDynamicInsnNode dyn) calls.add("invokedynamic " + dyn.name + dyn.desc);
        }
        return calls;
    }

    private static Field field(final String name) throws NoSuchFieldException {
        final Field field = Entity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Entity bareEntity(final Vec3 at) throws Exception {
        final Entity entity = mock(Entity.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        field("position").set(entity, at);
        return entity;
    }

    private static void computeSpeed(final Entity entity) throws Exception {
        final Method computeSpeed = Entity.class.getDeclaredMethod("computeSpeed");
        computeSpeed.setAccessible(true);
        computeSpeed.invoke(entity);
    }

    // ---- 0009: Entity#lastKnownSpeedX/Y/Z ----------------------------------------------------------

    @Test
    void computeSpeedCannotBeReenteredBetweenItsStores() throws Exception {
        final ClassNode entity = entityClass();
        final Set<String> calls = calls(method(entity, "computeSpeed", "()V"));
        assertEquals(Set.of("net/minecraft/world/entity/Entity.position()Lnet/minecraft/world/phys/Vec3;"), calls,
            "the only call between the three stores is position(); nothing can re-enter from it");
        assertEquals(Set.of(), calls(method(entity, "position", "()Lnet/minecraft/world/phys/Vec3;")),
            "position() is a field read: no callback, no event, no listener");
        final List<String> overrides = new ArrayList<>();
        // No subclass may turn position() into something that calls out; Entity is the only declarer.
        for (final Class<?> type : List.of(net.minecraft.world.entity.LivingEntity.class, net.minecraft.world.entity.Mob.class,
            net.minecraft.server.level.ServerPlayer.class, net.minecraft.world.entity.vehicle.minecart.AbstractMinecart.class)) {
            try {
                type.getDeclaredMethod("position");
                overrides.add(type.getName());
            } catch (final NoSuchMethodException expected) {
                // not overridden
            }
        }
        assertEquals(List.of(), overrides);
    }

    /**
     * The reader another region's thread can reach must read ONE field: two separately stored
     * doubles can be observed as x of one tick and z of another, which the Vec3 reference this
     * replaced could never be.
     */
    @Test
    void theCrossRegionReaderReadsASingleField() throws Exception {
        final MethodNode reader = method(entityClass(), "hasMovedHorizontallyRecently", "()Z");
        final List<String> fieldReads = new ArrayList<>();
        for (final AbstractInsnNode insn : reader.instructions) {
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field && insn.getOpcode() == org.objectweb.asm.Opcodes.GETFIELD) {
                fieldReads.add(field.name);
            }
        }
        assertEquals(List.of("lastKnownSpeedHorizontalSq"), fieldReads, "exactly one instance field, read once");
    }

    @Test
    void theReadersOfTheSpeedScalarsOnlyReadFields() throws Exception {
        final ClassNode entity = entityClass();
        assertEquals(Set.of("java/lang/Math.sqrt(D)D"), calls(method(entity, "hasMovedHorizontallyRecently", "()Z")));
        final Set<String> known = calls(method(entity, "getKnownSpeed", "()Lnet/minecraft/world/phys/Vec3;"));
        assertTrue(known.contains("net/minecraft/world/entity/Entity.getControllingPassenger()Lnet/minecraft/world/entity/LivingEntity;"), known.toString());
        assertTrue(known.contains("net/minecraft/world/phys/Vec3.<init>(DDD)V"), known.toString());
        assertEquals(4, known.size(), "getControllingPassenger, isAlive, Player.getKnownSpeed, new Vec3: " + known);
    }

    @Test
    void speedScalarsMatchTheVec3Arithmetic() throws Exception {
        final Entity entity = bareEntity(new Vec3(1.0, 2.0, 3.0));
        computeSpeed(entity); // first call: lastKnownPosition == position, speed zero
        assertFalse(entity.hasMovedHorizontallyRecently());
        assertEquals(Vec3.ZERO, entity.getKnownSpeed());

        field("position").set(entity, new Vec3(1.5, 2.0, 2.0));
        computeSpeed(entity);
        assertEquals(new Vec3(0.5, 0.0, -1.0), entity.getKnownSpeed());
        assertTrue(entity.hasMovedHorizontallyRecently());

        field("position").set(entity, new Vec3(1.5, 9.0, 2.0)); // vertical only
        computeSpeed(entity);
        assertEquals(new Vec3(0.0, 7.0, 0.0), entity.getKnownSpeed());
        assertFalse(entity.hasMovedHorizontallyRecently());

        // Re-entering computeSpeed itself (the only re-entry its callers could ever produce) is
        // idempotent for a stationary entity and recomputes from the latest position otherwise.
        computeSpeed(entity);
        assertEquals(Vec3.ZERO, entity.getKnownSpeed());
    }

    /**
     * The one reader another region's thread can reach — {@code hasMovedHorizontallyRecently},
     * through {@code FollowPlayerRiddenEntityGoal.canContinueToUse} — must never observe a mixed
     * tuple. The writer alternates between a speed of (a, 0, 0) and (0, 0, a): every consistent
     * state has horizontal speed a, so a {@code false} can only come from reading x from one
     * store and z from the other. A race test: it can only ever fail when a tear exists, and it
     * passed/failed as documented in the proof document.
     */
    @Test
    void aConcurrentReaderNeverSeesATornHorizontalSpeed() throws Exception {
        final Entity entity = bareEntity(new Vec3(0.0, 0.0, 0.0));
        computeSpeed(entity);                       // lastKnownPosition = origin, speed zero
        field("position").set(entity, new Vec3(1.0, 0.0, 0.0));
        computeSpeed(entity);                       // speed (1, 0, 0): every state from here on is "moved"
        assertTrue(entity.hasMovedHorizontallyRecently());
        final Vec3[] positions = {new Vec3(1.0, 0.0, 1.0), new Vec3(2.0, 0.0, 1.0), new Vec3(2.0, 0.0, 2.0), new Vec3(3.0, 0.0, 2.0)};
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicLong torn = new AtomicLong();
        final AtomicLong reads = new AtomicLong();
        final Thread reader = new Thread(() -> {
            long seen = 0;
            long bad = 0;
            while (!stop.get()) {
                if (!entity.hasMovedHorizontallyRecently()) bad++;
                seen++;
            }
            reads.set(seen);
            torn.set(bad);
        }, "ScratchReentrancyTest-reader");
        final Field position = field("position");
        reader.start();
        final long deadline = System.nanoTime() + 1_500_000_000L;
        int i = 0;
        try {
            while (System.nanoTime() < deadline) {
                position.set(entity, positions[i++ & 3]);
                computeSpeed(entity);
            }
        } finally {
            stop.set(true);
            reader.join(10_000);
        }
        assertTrue(reads.get() > 0, "the reader ran");
        assertEquals(0L, torn.get(), "torn (x from one tick, z from another) reads out of " + reads.get()
            + " after " + i + " writes");
    }

    // ---- 0012: Entity#isInWallScratch ---------------------------------------------------------------

    /**
     * {@code isInWall} iterates with the scratch position live. The calls it makes while the
     * scratch is live are block-state queries with no entity and no event: pinned here, so a call
     * added into the loop (an event, a plugin hook, anything that could tick an entity) fails this
     * test and reopens the proof.
     */
    @Test
    void isInWallCallsNothingThatCanReenterWhileItsScratchIsLive() throws Exception {
        final Set<String> calls = calls(method(entityClass(), "isInWall", "()Z"));
        final Set<String> allowed = Set.of(
            // the entity's own scalar getters (field reads) and the dimensions record
            "net/minecraft/world/entity/Entity.isSpectator()Z",
            "net/minecraft/world/entity/Entity.getX()D",
            "net/minecraft/world/entity/Entity.getEyeY()D",
            "net/minecraft/world/entity/Entity.getZ()D",
            "net/minecraft/world/entity/EntityDimensions.width()F",
            // scalar collision helpers
            "ca/spottedleaf/moonrise/patches/collisions/CollisionUtil.isEmpty(DDDDDD)Z",
            "ca/spottedleaf/moonrise/patches/collisions/CollisionUtil.voxelShapeIntersect(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/phys/AABB;)Z",
            "ca/spottedleaf/moonrise/patches/collisions/CollisionUtil.voxelShapeIntersectNoEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/AABB;)Z",
            "net/minecraft/util/Mth.floor(D)I",
            "ca/spottedleaf/moonrise/common/util/WorldUtil.getMinSection(Lnet/minecraft/world/level/Level;)I",
            // chunk/section lookups (reads of already-loaded data on the owning region)
            "net/minecraft/world/level/Level.getChunkSource()Lnet/minecraft/world/level/chunk/ChunkSource;",
            "net/minecraft/world/level/chunk/ChunkSource.getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            "net/minecraft/world/level/chunk/ChunkAccess.getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;",
            "net/minecraft/world/level/chunk/LevelChunkSection.hasOnlyAir()Z",
            "net/minecraft/world/level/chunk/LevelChunkSection.getStates()Lnet/minecraft/world/level/chunk/PalettedContainer;",
            "net/minecraft/world/level/chunk/PalettedContainer.get(I)Ljava/lang/Object;",
            // the scratch position itself
            "net/minecraft/core/BlockPos$MutableBlockPos.setX(I)Lnet/minecraft/core/BlockPos$MutableBlockPos;",
            "net/minecraft/core/BlockPos$MutableBlockPos.setY(I)Lnet/minecraft/core/BlockPos$MutableBlockPos;",
            "net/minecraft/core/BlockPos$MutableBlockPos.setZ(I)Lnet/minecraft/core/BlockPos$MutableBlockPos;",
            // block-state queries: no entity argument, no CollisionContext, no event
            "ca/spottedleaf/moonrise/patches/collisions/block/CollisionBlockState.moonrise$emptyCollisionShape()Z",
            "net/minecraft/world/level/block/state/BlockState.isSuffocating(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z",
            "net/minecraft/world/level/block/state/BlockState.getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            "net/minecraft/world/phys/shapes/VoxelShape.isEmpty()Z",
            "ca/spottedleaf/moonrise/patches/collisions/shape/CollisionVoxelShape.moonrise$getSingleAABBRepresentation()Lnet/minecraft/world/phys/AABB;",
            "net/minecraft/world/phys/AABB.<init>(DDDDDD)V");
        final Set<String> unexpected = new TreeSet<>(calls);
        unexpected.removeAll(allowed);
        assertEquals(Set.of(), unexpected, "a call that is not a scalar getter, a section lookup or a block-state query");
    }

    @Test
    void theScratchPositionIsOwnerConfinedAndFullySetBeforeEveryRead() throws Exception {
        final Field scratch = field("isInWallScratch");
        assertTrue(java.lang.reflect.Modifier.isPrivate(scratch.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isFinal(scratch.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isStatic(scratch.getModifiers()));
        // Within isInWall, each of setY, setZ, setX precedes the first read in instruction order:
        // every iteration writes all three coordinates it reads, so no value survives from an
        // earlier call. Checked on the bytecode so that a reordering of the loops reopens the proof.
        final MethodNode isInWall = method(entityClass(), "isInWall", "()Z");
        int firstRead = Integer.MAX_VALUE;
        int lastSetBeforeRead = -1;
        final Set<String> setsSeen = new TreeSet<>();
        int index = 0;
        for (final AbstractInsnNode insn : isInWall.instructions) {
            if (insn instanceof MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/core/BlockPos$MutableBlockPos") && call.name.startsWith("set") && firstRead == Integer.MAX_VALUE) {
                    setsSeen.add(call.name);
                    lastSetBeforeRead = index;
                }
                if (call.name.equals("isSuffocating") && firstRead == Integer.MAX_VALUE) firstRead = index;
            }
            index++;
        }
        assertEquals(Set.of("setX", "setY", "setZ"), setsSeen, "all three coordinates are set before the first read");
        assertTrue(lastSetBeforeRead < firstRead);
    }
}
